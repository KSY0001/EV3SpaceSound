package com.ev3.spacesound.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Spaceship-style EV sound engine with switchable sound packs (see [Packs]).
 * Shared parts live here: master gain, tunnel/space reverb, regen sparkle + echo,
 * boot/shutdown sweeps, braam swells and the latency test click.
 * Each pack is rendered by a [PackVoice]; switching packs crossfades two voices.
 *
 * Inputs are set from any thread: speed in km/h, normalized acceleration -1..1, duck, tunnel.
 */
class SynthEngine(val sampleRate: Int, framesPerBurst: Int, initialPack: Int = 1) {

    // ---- inputs ----
    @Volatile var inSpeedKmh = 0f
    @Volatile var inAccel = 0f
    @Volatile var inDuck = 1f
    @Volatile var inTunnel = false
    @Volatile var inLowBattery = false
    @Volatile var userVolume = 0.8f
    /** Bass shelf boost in dB (0..15). */
    @Volatile var bassDb = 9f
    /** Silences the engine (not the test click) during a latency measurement. */
    @Volatile var testMute = false

    // ---- outputs for UI ----
    @Volatile var powered = false; private set
    @Volatile var packIndex = initialPack.coerceIn(0, Packs.all.size - 1); private set
    val pack: SoundPack get() = Packs.all[packIndex]
    /** drone, tone, energy, pad, riser, regen — each 0..1 */
    val meters = FloatArray(6)
    /** System.nanoTime() just before the block that starts a test click was handed to AudioTrack. */
    @Volatile var clickWrittenNanos = 0L; private set
    @Volatile var bufferFrames = 0; private set
    @Volatile var underruns = 0; private set
    val blockFrames = framesPerBurst.coerceIn(64, 480)

    private sealed class Cmd {
        object PowerOn : Cmd()
        object PowerOff : Cmd()
        object Click : Cmd()
        class SetPack(val index: Int) : Cmd()
    }
    private val cmds = ConcurrentLinkedQueue<Cmd>()

    fun powerOn() = cmds.add(Cmd.PowerOn)
    fun powerOff() = cmds.add(Cmd.PowerOff)
    fun selectPack(index: Int) = cmds.add(Cmd.SetPack(index.coerceIn(0, Packs.all.size - 1)))
    fun nextPack() = selectPack((packIndex + 1) % Packs.all.size)
    /** Queue a short 2 kHz click mixed after the master gain. Resets [clickWrittenNanos] to 0 first. */
    fun requestClick() { clickWrittenNanos = 0L; cmds.add(Cmd.Click) }

    // ---- audio thread state ----
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val blockSec = blockFrames.toDouble() / sampleRate
    private var clock = 0L
    private val rng = java.util.Random()

    private val kCache = HashMap<Double, Double>()
    private val k: (Double) -> Double = { tc -> kCache.getOrPut(tc) { smoothK(tc, blockSec) } }

    private val master = Sm(0.0)
    private var masterTc = 0.6
    private val wet = Sm(0.0); private val dry = Sm(1.0)
    private val reverb = Reverb(sampleRate)

    // master EQ + compressor
    private val eqBass = Biquad(sampleRate)
    private val eqMud = Biquad(sampleRate).also { it.peaking(380.0, -2.5, 0.8) }
    private val eqTop = Biquad(sampleRate).also { it.lowpass(7500.0, 0.7) }
    private var appliedBass = Float.NaN
    private val compThr = 10.0.pow(-22.0 / 20)
    private val compRatio = 5.0
    private val compAtk = exp(-1.0 / (0.005 * sampleRate))
    private val compRel = exp(-1.0 / (0.2 * sampleRate))
    private var compEnv = 0.0

    private var voice = PackVoice(pack, sampleRate, k, 1.0)
    private var oldVoice: PackVoice? = null
    private val braams = ArrayList<BraamVoice>()
    private val bus = DoubleArray(blockFrames)

    // regen sparkle voices + echo
    private val nVoices = 12
    private val vFreq = DoubleArray(nVoices); private val vPhase = DoubleArray(nVoices)
    private val vAmp = DoubleArray(nVoices); private val vPeak = DoubleArray(nVoices); private val vStage = IntArray(nVoices)
    private val vWave = Array(nVoices) { Wave.SINE }
    private var nextVoice = 0
    private val blipDecay = exp(ln(1e-4) / (0.344 * sampleRate))
    private val blipAttack = 1.0 / (0.006 * sampleRate)
    private val echo = DoubleArray((0.5 * sampleRate).toInt()); private var echoI = 0
    private var echoLen = (0.18 * sampleRate).toInt(); private var echoFb = 0.38
    private var sparkAcc = 0.0
    private var sparkLevel = 0.0

    // boot / shutdown sweep
    private var swActive = false; private var swF0 = 0.0; private var swF1 = 0.0; private var swDur = 0.0
    private var swVol = 0.0; private var swPos = 0L; private var swP1 = 0.0; private var swP2 = 0.0
    private val sweepLP = Biquad(sampleRate).also { it.lowpass(2000.0, 2.0) }

    private data class Ev(val at: Long, val run: () -> Unit)
    private val events = ArrayList<Ev>()

    // test click
    private var clickLeft = 0; private var clickPhase = 0.0
    private val clickLen = (0.005 * sampleRate).toInt()

    private var flickerTimer = 0.0
    private var flickerNow = false
    private var jitter = 1.0

    init { applyEcho(pack) }

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

    private fun applyEcho(p: SoundPack) {
        echoLen = ((p.spark?.echo ?: 0.18) * sampleRate).toInt().coerceIn(1, echo.size)
        echoFb = p.spark?.fb ?: 0.38
    }

    private fun startBlip(f: Double, vol: Double, wave: Wave) {
        var idx = -1
        for (i in 0 until nVoices) if (vStage[i] == 0) { idx = i; break }
        if (idx < 0) { idx = nextVoice; nextVoice = (nextVoice + 1) % nVoices }
        vFreq[idx] = f; vPhase[idx] = 0.0; vAmp[idx] = 0.0; vPeak[idx] = vol; vStage[idx] = 1; vWave[idx] = wave
    }

    private fun startSweep(s: Sweep) {
        swActive = true; swF0 = s.f0; swF1 = s.f1; swDur = s.dur; swVol = s.vol; swPos = 0
    }

    private fun startBraam(b: Braam) {
        if (braams.size >= 2) braams.removeAt(0)
        braams.add(BraamVoice(b, sampleRate))
    }

    private fun schedule(delaySec: Double, run: () -> Unit) {
        events.add(Ev(clock + (delaySec * sampleRate).toLong(), run))
    }

    private fun handleCommands(): Boolean {
        var click = false
        while (true) {
            when (val c = cmds.poll() ?: break) {
                Cmd.PowerOn -> {
                    powered = true
                    val p = pack
                    masterTc = 0.35
                    p.boot.sweep?.let { startSweep(it) }
                    if (p.boot.braam) p.braam?.let { b -> schedule(0.3) { startBraam(b) } }
                    for (ch in p.boot.chime) schedule(ch.at) { startBlip(ch.f, ch.vol, p.boot.chimeWave) }
                    val bootLen = max(1.6, (p.boot.chime.maxOfOrNull { it.at } ?: 0.0) + 0.2)
                    schedule(bootLen) { masterTc = 1.2 }
                }
                Cmd.PowerOff -> {
                    powered = false
                    val s = pack.shutdown
                    masterTc = (s.dur + 0.3) / 3
                    startSweep(s)
                }
                Cmd.Click -> { clickLeft = clickLen; clickPhase = 0.0; click = true }
                is Cmd.SetPack -> if (c.index != packIndex) {
                    packIndex = c.index
                    val p = pack
                    if (powered) {
                        oldVoice = voice.also { it.fadeTarget = 0.0 }
                        voice = PackVoice(p, sampleRate, k, 0.0)
                        p.boot.chime.firstOrNull()?.let { startBlip(it.f, 0.05, p.boot.chimeWave) }
                    } else {
                        oldVoice = null
                        voice = PackVoice(p, sampleRate, k, 1.0)
                    }
                    applyEcho(p)
                }
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
        val silentEngine = master.v < 1e-5 && !powered && !swActive && braams.isEmpty() && vStage.all { it == 0 }

        if (silentEngine) {
            java.util.Arrays.fill(out, 0f)
        } else {
            java.util.Arrays.fill(bus, 0.0)
            voice.render(bus, n)
            oldVoice?.render(bus, n)
            for (b in braams) b.render(bus, n)
            braams.removeAll { it.done }

            val mg = master.v; val wetG = wet.v; val dryG = dry.v
            val bdb = bassDb
            if (bdb != appliedBass) { eqBass.lowshelf(90.0, bdb.toDouble()); appliedBass = bdb }
            for (i in 0 until n) {
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
                    val dt = vFreq[v] / sr
                    blip += waveSample(vWave[v], vPhase[v], dt) * vAmp[v]
                    vPhase[v] += dt; if (vPhase[v] >= 1) vPhase[v] -= 1
                }
                val echoOut = echo[echoI]
                echo[echoI] = blip + echoOut * echoFb
                if (++echoI >= echoLen) echoI = 0

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

                val sum = eqTop.process(eqMud.process(eqBass.process(bus[i] + blip + echoOut + sweepOut)))
                val mixed = (sum * dryG + reverb.process(sum) * wetG) * mg
                // compressor: glue the layers and lift perceived loudness
                val lvl = kotlin.math.abs(mixed)
                compEnv = if (lvl > compEnv) lvl + (compEnv - lvl) * compAtk else lvl + (compEnv - lvl) * compRel
                val gr = if (compEnv > compThr) compThr * (compEnv / compThr).pow(1 / compRatio) / compEnv else 1.0
                var s = softClip(mixed * gr * 1.5) * 0.9

                if (clickLeft > 0) {
                    s += 0.9 * sin(2 * PI * clickPhase)
                    clickPhase += 2000.0 / sr; if (clickPhase >= 1) clickPhase -= 1
                    clickLeft--
                }
                out[i] = s.toFloat()
            }
            if (oldVoice?.finished == true) oldVoice = null
        }
        if (silentEngine && clickLeft > 0) {
            for (i in 0 until n) {
                if (clickLeft <= 0) break
                out[i] = (0.9 * sin(2 * PI * clickPhase)).toFloat()
                clickPhase += 2000.0 / sr; if (clickPhase >= 1) clickPhase -= 1
                clickLeft--
            }
        }
        clock += n
        return clickStarts
    }

    private fun updateParams() {
        val speed = inSpeedKmh.toDouble()
        val a = inAccel.toDouble().coerceIn(-1.0, 1.0)
        val an = max(-a, 0.0)
        val on = powered && !testMute
        val p = pack

        master.to(if (on) 0.8 * inDuck * userVolume else 0.0, k(if (testMute) 0.03 else masterTc))
        wet.to(min(1.0, p.space + if (inTunnel) 0.6 else 0.0), k(0.5))
        dry.to(if (inTunnel) 1.1 else 1.0, k(0.5))

        // low battery: occasional flicker, checked roughly every 16 ms like the web version
        flickerTimer += blockSec
        if (flickerTimer >= 0.016) {
            flickerTimer = 0.0
            flickerNow = rng.nextDouble() < 0.05
            jitter = 0.8 + rng.nextDouble() * 0.4
        }

        if (voice.update(speed, a, inLowBattery, flickerNow, jitter, blockSec) && on) p.braam?.let { startBraam(it) }
        oldVoice?.update(speed, a, inLowBattery, flickerNow, jitter, blockSec)

        // regen sparkle
        val spark = p.spark
        if (on && an > 0.08 && speed > 4) {
            if (spark != null) {
                sparkAcc += an * blockSec * 9
                while (sparkAcc > 1) {
                    startBlip(spark.scale[rng.nextInt(spark.scale.size)], (0.02 + an * 0.05) * spark.vol, spark.wave)
                    sparkAcc -= 1
                }
                sparkLevel = min(1.0, sparkLevel + blockSec * 4)
            } else sparkLevel = min(1.0, an * 1.5)
        } else sparkLevel = max(0.0, sparkLevel - blockSec * 2)

        for (i in 0 until 5) meters[i] = voice.meters[i].coerceIn(0.0, 1.0).toFloat()
        meters[5] = sparkLevel.toFloat()
    }
}
