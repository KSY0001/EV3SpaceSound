package com.ev3.spacesound.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Spaceship-style EV sound, ported from the browser prototype.
 * Layers: core hum, turbine whine, energy noise, harmonic pad, regen sparkle (with echo),
 * boot/shutdown sweeps and a tunnel reverb. Rendered on its own thread into a low-latency AudioTrack.
 *
 * Inputs (set from any thread): speed in km/h, normalized acceleration -1..1, duck factor, tunnel flag.
 */
class SynthEngine(val sampleRate: Int, framesPerBurst: Int) {

    // ---- inputs ----
    @Volatile var inSpeedKmh = 0f
    @Volatile var inAccel = 0f
    @Volatile var inDuck = 1f
    @Volatile var inTunnel = false
    @Volatile var inLowBattery = false
    @Volatile var userVolume = 0.8f
    /** Silences the engine (not the test click) during a latency measurement. */
    @Volatile var testMute = false

    // ---- outputs for UI ----
    @Volatile var powered = false; private set
    @Volatile var meterCore = 0f; private set
    @Volatile var meterWhine = 0f; private set
    @Volatile var meterEnergy = 0f; private set
    @Volatile var meterPad = 0f; private set
    @Volatile var meterSpark = 0f; private set
    /** System.nanoTime() just before the block that starts a test click was handed to AudioTrack. */
    @Volatile var clickWrittenNanos = 0L; private set
    @Volatile var bufferFrames = 0; private set
    @Volatile var underruns = 0; private set
    val blockFrames = framesPerBurst.coerceIn(64, 480)

    private sealed class Cmd {
        object PowerOn : Cmd()
        object PowerOff : Cmd()
        object Click : Cmd()
    }
    private val cmds = ConcurrentLinkedQueue<Cmd>()

    fun powerOn() = cmds.add(Cmd.PowerOn)
    fun powerOff() = cmds.add(Cmd.PowerOff)
    /** Queue a short 2 kHz click mixed after the master gain. Resets [clickWrittenNanos] to 0 first. */
    fun requestClick() { clickWrittenNanos = 0L; cmds.add(Cmd.Click) }

    // ---- audio thread state ----
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val blockSec = blockFrames.toDouble() / sampleRate
    private var clock = 0L // samples rendered
    private val rng = java.util.Random()

    private class Sm(var v: Double) { fun to(t: Double, k: Double) { v += (t - v) * k } }
    private val kCache = HashMap<Double, Double>()
    private fun k(tc: Double) = kCache.getOrPut(tc) { smoothK(tc, blockSec) }

    private val master = Sm(0.0)
    private var masterTc = 0.6
    private val coreF = Sm(42.0); private val coreCut = Sm(250.0); private val coreGain = Sm(0.0); private val lfoDepth = Sm(0.0)
    private val whineF = Sm(180.0); private val whineGain = Sm(0.0)
    private val energyF = Sm(600.0); private val energyGain = Sm(0.0)
    private val padRoot = Sm(110.0); private val padCut = Sm(180.0); private val padGain = Sm(0.0)
    private val wet = Sm(0.0); private val dry = Sm(1.0)

    private val coreLP = Biquad(sampleRate); private val whineBP = Biquad(sampleRate)
    private val energyBP = Biquad(sampleRate); private val padLP = Biquad(sampleRate); private val sweepLP = Biquad(sampleRate)
    private val noise = Noise(); private val reverb = Reverb(sampleRate)

    private var pC1 = 0.0; private var pC2 = 0.0; private var pC3 = 0.0; private var pLfo = 0.0
    private var pW1 = 0.0; private var pW2 = 0.0
    private var pP1 = 0.0; private var pP2 = 0.0; private var pP3 = 0.0

    // regen sparkle voices + echo
    private val nVoices = 12
    private val vFreq = DoubleArray(nVoices); private val vPhase = DoubleArray(nVoices)
    private val vAmp = DoubleArray(nVoices); private val vPeak = DoubleArray(nVoices); private val vStage = IntArray(nVoices)
    private var nextVoice = 0
    private val blipDecay = exp(ln(1e-4) / (0.344 * sampleRate))
    private val blipAttack = 1.0 / (0.006 * sampleRate)
    private val echo = DoubleArray((0.18 * sampleRate).toInt()); private var echoI = 0
    private var sparkAcc = 0.0
    private var sparkLevel = 0.0
    private val scale = doubleArrayOf(1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0, 3136.0)

    // boot / shutdown sweep
    private var swActive = false; private var swF0 = 0.0; private var swF1 = 0.0; private var swDur = 0.0
    private var swVol = 0.0; private var swPos = 0L; private var swP1 = 0.0; private var swP2 = 0.0

    // scheduled events (audio thread only)
    private data class Ev(val at: Long, val run: () -> Unit)
    private val events = ArrayList<Ev>()

    // test click
    private var clickLeft = 0; private var clickPhase = 0.0
    private val clickLen = (0.005 * sampleRate).toInt()

    private var flickerTimer = 0.0
    private var flickerCore = 1.0
    private var flickerWhine = 1.0

    fun start() {
        if (running) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBytes, blockFrames * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        // Start small; grow when underruns happen (same idea as Oboe's latency tuner).
        t.setBufferSizeInFrames(blockFrames * 2)
        bufferFrames = t.bufferSizeInFrames
        t.play()
        track = t
        running = true
        thread = Thread({ loop() }, "space-synth").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(blockFrames)
        var lastUnderruns = 0
        var tuneCounter = 0
        while (running) {
            val t = track ?: break
            val clickStarts = render(buf)
            if (clickStarts) clickWrittenNanos = System.nanoTime()
            t.write(buf, 0, blockFrames, AudioTrack.WRITE_BLOCKING)
            if (++tuneCounter * blockSec > 0.5) {
                tuneCounter = 0
                val u = t.underrunCount
                if (u > lastUnderruns && t.bufferSizeInFrames + blockFrames <= t.bufferCapacityInFrames) {
                    t.setBufferSizeInFrames(t.bufferSizeInFrames + blockFrames)
                }
                lastUnderruns = u
                underruns = u
                bufferFrames = t.bufferSizeInFrames
            }
        }
    }

    private fun startBlip(f: Double, vol: Double) {
        var idx = -1
        for (i in 0 until nVoices) if (vStage[i] == 0) { idx = i; break }
        if (idx < 0) { idx = nextVoice; nextVoice = (nextVoice + 1) % nVoices }
        vFreq[idx] = f; vPhase[idx] = 0.0; vAmp[idx] = 0.0; vPeak[idx] = vol; vStage[idx] = 1
    }

    private fun startSweep(f0: Double, f1: Double, dur: Double, vol: Double) {
        swActive = true; swF0 = f0; swF1 = f1; swDur = dur; swVol = vol; swPos = 0
    }

    private fun schedule(delaySec: Double, run: () -> Unit) {
        events.add(Ev(clock + (delaySec * sampleRate).toLong(), run))
    }

    private fun handleCommands(): Boolean {
        var click = false
        while (true) {
            when (cmds.poll() ?: break) {
                Cmd.PowerOn -> {
                    powered = true
                    masterTc = 0.35
                    startSweep(40.0, 900.0, 1.4, 0.12)
                    schedule(1.35) { startBlip(1568.0, 0.08) }
                    schedule(1.49) { startBlip(2349.3, 0.07) }
                    schedule(1.6) { masterTc = 1.2 }
                }
                Cmd.PowerOff -> {
                    powered = false
                    masterTc = 0.6
                    startSweep(900.0, 30.0, 1.6, 0.1)
                }
                Cmd.Click -> { clickLeft = clickLen; clickPhase = 0.0; click = true }
            }
        }
        if (events.isNotEmpty()) {
            val due = events.filter { it.at <= clock }
            if (due.isNotEmpty()) { events.removeAll(due.toSet()); due.forEach { it.run() } }
        }
        return click
    }

    /** Renders one block. Returns true when a test click starts in this block. */
    private fun render(out: FloatArray): Boolean {
        val clickStarts = handleCommands()
        updateParams()

        val n = out.size
        val sr = sampleRate.toDouble()
        val silentEngine = master.v < 1e-5 && !powered && !swActive && vStage.all { it == 0 }

        if (silentEngine) {
            java.util.Arrays.fill(out, 0f)
        } else {
            val cf = coreF.v
            val dC1 = cf / sr; val dC2 = cf * 1.012 / sr; val dC3 = cf * 2 / sr
            val dLfo = 0.35 / sr
            val wf = whineF.v
            val dW1 = wf / sr; val dW2 = wf * 1.5 / sr
            val root = padRoot.v
            val dP1 = root / sr; val dP2 = root * 1.5 / sr; val dP3 = root * 2.003 / sr
            coreLP.lowpass(coreCut.v, 0.7)
            whineBP.bandpass(wf, 6.0)
            energyBP.bandpass(energyF.v, 1.2)
            padLP.lowpass(padCut.v, 4.0)
            sweepLP.lowpass(2000.0, 2.0)
            val cg = coreGain.v * flickerCore; val ld = lfoDepth.v
            val wg = whineGain.v * flickerWhine; val eg = energyGain.v; val pg = padGain.v
            val mg = master.v; val wetG = wet.v; val dryG = dry.v

            for (i in 0 until n) {
                // core hum + breathing LFO on its gain
                val core = sin(2 * PI * pC1) + tri(pC2) + 0.3 * sin(2 * PI * pC3)
                val coreOut = coreLP.process(core) * (cg + ld * sin(2 * PI * pLfo))
                pC1 += dC1; if (pC1 >= 1) pC1 -= 1
                pC2 += dC2; if (pC2 >= 1) pC2 -= 1
                pC3 += dC3; if (pC3 >= 1) pC3 -= 1
                pLfo += dLfo; if (pLfo >= 1) pLfo -= 1

                // turbine whine
                val w = sawBlep(pW1, dW1) + 0.5 * sin(2 * PI * pW2)
                val whineOut = whineBP.process(w) * wg
                pW1 += dW1; if (pW1 >= 1) pW1 -= 1
                pW2 += dW2; if (pW2 >= 1) pW2 -= 1

                // energy noise
                val energyOut = energyBP.process(noise.next()) * eg

                // harmonic pad
                val pad = sawBlep(pP1, dP1) + sawBlep(pP2, dP2) + sawBlep(pP3, dP3)
                val padOut = padLP.process(pad) * pg
                pP1 += dP1; if (pP1 >= 1) pP1 -= 1
                pP2 += dP2; if (pP2 >= 1) pP2 -= 1
                pP3 += dP3; if (pP3 >= 1) pP3 -= 1

                // sparkle voices + echo
                var blip = 0.0
                for (v in 0 until nVoices) {
                    val st = vStage[v]
                    if (st == 0) continue
                    if (st == 1) {
                        vAmp[v] += vPeak[v] * blipAttack
                        if (vAmp[v] >= vPeak[v]) { vAmp[v] = vPeak[v]; vStage[v] = 2 }
                    } else {
                        vAmp[v] *= blipDecay
                        if (vAmp[v] < 1e-5) { vAmp[v] = 0.0; vStage[v] = 0 }
                    }
                    blip += sin(2 * PI * vPhase[v]) * vAmp[v]
                    vPhase[v] += vFreq[v] / sr; if (vPhase[v] >= 1) vPhase[v] -= 1
                }
                val echoOut = echo[echoI]
                echo[echoI] = blip + echoOut * 0.38
                if (++echoI >= echo.size) echoI = 0
                val sparkleOut = blip + echoOut

                // boot / shutdown sweep
                var sweepOut = 0.0
                if (swActive) {
                    val t = swPos / sr
                    val f = swF0 * (swF1 / swF0).pow(min(t / swDur, 1.0))
                    val half = swDur * 0.5
                    val g = if (t < half) swVol * (t / half)
                            else swVol * exp(ln(1e-4 / swVol) * (t - half) / (half + 0.3))
                    val raw = sawBlep(swP1, f / sr) + sin(2 * PI * swP2)
                    sweepOut = sweepLP.process(raw) * g
                    swP1 += f / sr; if (swP1 >= 1) swP1 -= 1
                    swP2 += f * 0.5 / sr; if (swP2 >= 1) swP2 -= 1
                    swPos++
                    if (t > swDur + 0.3) swActive = false
                }

                val bus = coreOut + whineOut + energyOut + padOut + sparkleOut + sweepOut
                val mixed = bus * dryG + reverb.process(bus) * wetG
                var s = softClip(mixed * mg * 1.2) * 0.85

                if (clickLeft > 0) {
                    s += 0.9 * sin(2 * PI * clickPhase)
                    clickPhase += 2000.0 / sr; if (clickPhase >= 1) clickPhase -= 1
                    clickLeft--
                }
                out[i] = s.toFloat()
            }
        }
        if (silentEngine && clickLeft > 0) {
            val sr2 = sampleRate.toDouble()
            for (i in 0 until n) {
                if (clickLeft <= 0) break
                out[i] = (0.9 * sin(2 * PI * clickPhase)).toFloat()
                clickPhase += 2000.0 / sr2; if (clickPhase >= 1) clickPhase -= 1
                clickLeft--
            }
        }
        clock += n
        return clickStarts
    }

    private fun updateParams() {
        val speed = inSpeedKmh.toDouble()
        val s = (speed / 180.0).coerceIn(0.0, 1.0)
        val a = inAccel.toDouble().coerceIn(-1.0, 1.0)
        val ap = max(a, 0.0); val an = max(-a, 0.0)
        val on = powered && !testMute

        val mTarget = if (on) 0.8 * inDuck * userVolume else 0.0
        master.to(mTarget, k(if (testMute) 0.03 else masterTc))

        val kFast = k(0.05)
        coreF.to(42 + s * 70, kFast)
        coreCut.to(250 + ap * 900 + s * 300, kFast)
        coreGain.to(0.28 + ap * 0.1, k(0.08))
        lfoDepth.to(if (speed < 3) 0.12 else 0.03, k(0.4))

        whineF.to(180 + s * 1700 + ap * 120 - an * 90, k(0.08))
        whineGain.to(if (speed < 1) 0.004 else 0.012 + s * 0.05 + ap * 0.05 + an * 0.03, kFast)

        energyF.to(600 + ap * 3500 + s * 800, kFast)
        energyGain.to(ap * 0.22 + an * 0.05, kFast)

        padRoot.to(110 * 2.0.pow(s), k(0.1))
        padCut.to(180 + ap * 2600 + s * 500, kFast)
        padGain.to(0.035 + ap * 0.06 + s * 0.02, kFast)

        wet.to(if (inTunnel) 0.65 else 0.0, k(0.5))
        dry.to(if (inTunnel) 1.1 else 1.0, k(0.5))

        // low battery: occasional flicker, checked roughly every 16 ms like the web version
        flickerTimer += blockSec
        if (flickerTimer >= 0.016) {
            flickerTimer = 0.0
            if (inLowBattery) {
                flickerCore = if (rng.nextDouble() < 0.05) 0.25 else 1.0
                flickerWhine = 0.8 + rng.nextDouble() * 0.4
            } else { flickerCore = 1.0; flickerWhine = 1.0 }
        }

        // regen sparkle
        if (on && an > 0.08 && speed > 4) {
            sparkAcc += an * blockSec * 9
            while (sparkAcc > 1) {
                startBlip(scale[rng.nextInt(scale.size)], 0.02 + an * 0.05)
                sparkAcc -= 1
            }
            sparkLevel = min(1.0, sparkLevel + blockSec * 4)
        } else sparkLevel = max(0.0, sparkLevel - blockSec * 2)

        meterCore = min(1.0, coreGain.v / 0.4).toFloat()
        meterWhine = min(1.0, whineGain.v / 0.11).toFloat()
        meterEnergy = min(1.0, energyGain.v / 0.22).toFloat()
        meterPad = min(1.0, padGain.v / 0.115).toFloat()
        meterSpark = sparkLevel.toFloat()
    }

    /** Level of the master bus target, for UI only. */
    val masterLevel: Float get() = abs(master.v).toFloat()
}
