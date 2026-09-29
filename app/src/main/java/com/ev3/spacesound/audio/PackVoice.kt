package com.ev3.spacesound.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Band-limited oscillator sample for the given wave, phase in [0,1), dt = f / sampleRate. */
fun waveSample(w: Wave, phase: Double, dt: Double): Double = when (w) {
    Wave.SINE -> sin(2 * PI * phase)
    Wave.TRI -> tri(phase)
    Wave.SAW -> sawBlep(phase, dt)
    Wave.SQUARE -> {
        val p2 = if (phase + 0.5 >= 1.0) phase - 0.5 else phase + 0.5
        sawBlep(phase, dt) - sawBlep(p2, dt)
    }
}

/** One smoothed control value, updated once per block. */
internal class Sm(var v: Double) { fun to(t: Double, k: Double) { v += (t - v) * k } }

/**
 * The live synthesis of one sound pack. Two can run at once while the user switches packs,
 * each with its own fade. The engine calls [update] once per block and [render] to add samples.
 */
internal class PackVoice(val pack: SoundPack, private val sr: Int, private val k: (Double) -> Double, startFade: Double) {
    private val srD = sr.toDouble()
    val fade = Sm(startFade)
    var fadeTarget = 1.0
    val finished: Boolean get() = fadeTarget == 0.0 && fade.v < 1e-4

    // drone
    private val d = pack.drone
    private val dPh = DoubleArray(d.voices.size)
    private val dLP = Biquad(sr)
    private val dF = Sm(d.base); private val dCut = Sm(d.cut); private val dGain = Sm(0.0); private val dBreath = Sm(0.0)
    private var breathPh = 0.0
    private val satNorm = 1.0 / kotlin.math.tanh(d.drive)
    private val satBias = kotlin.math.tanh(0.25)
    private val rumbleNoise = Noise()
    private val rumbleCoef = kotlin.math.exp(-2 * PI * 22.0 / sr)
    private var rumbleLp = 0.0
    private val rumbleGain = Sm(0.0)
    private var flicker = 1.0

    // pulse
    private val pulseRate = Sm(pack.pulse?.rate ?: 1.0)
    private var pulsePh = 0.0

    // whine
    private val wLayer = pack.whine
    private var wPh1 = 0.0; private var wPh2 = 0.0; private var vibPh = 0.0
    private val wFilter = Biquad(sr)
    private val wF = Sm(wLayer?.base ?: 100.0); private val wGain = Sm(0.0); private val wVib = Sm(0.0)
    private var wJitter = 1.0

    // noise
    private val nLayer = pack.noise
    private val noise = Noise()
    private val nFilter = Biquad(sr)
    private val nF = Sm(nLayer?.base ?: 500.0); private val nGain = Sm(0.0)

    // pad
    private val pLayer = pack.pad
    private val padNotes: DoubleArray
    private val padCents: DoubleArray
    private val padPh: DoubleArray
    private val padLP = Biquad(sr)
    private val padRoot = Sm(pLayer?.root ?: 110.0); private val padCut = Sm(pLayer?.cut ?: 500.0); private val padGain = Sm(0.0)
    private val padScale: Double

    // shepard riser
    private val shLayer = pack.shepard
    private val shP = DoubleArray(shLayer?.voices ?: 0) { it.toDouble() / max(1, shLayer?.voices ?: 1) }
    private val shPh = DoubleArray(shLayer?.voices ?: 0)
    private val shLP = Biquad(sr)
    private val shGain = Sm(0.0)

    // scratch arrays reused every block (no allocation on the audio thread)
    private val dDt = DoubleArray(dPh.size)
    private val padDt: DoubleArray
    private val shDt = DoubleArray(shP.size)
    private val shAmp = DoubleArray(shP.size)

    // braam trigger state
    private var braamArmed = true
    private var braamCool = 0.0

    // meters (0..1)
    val meters = DoubleArray(5)

    init {
        val p = pLayer
        if (p != null) {
            val notes = ArrayList<Double>(); val cents = ArrayList<Double>()
            for (n in p.notes) for (c in p.unison) { notes.add(n); cents.add(c) }
            padNotes = notes.toDoubleArray(); padCents = cents.toDoubleArray()
            padScale = 1.0 / sqrt(p.unison.size.toDouble())
        } else { padNotes = DoubleArray(0); padCents = DoubleArray(0); padScale = 1.0 }
        padPh = DoubleArray(padNotes.size)
        padDt = DoubleArray(padNotes.size)
        shLayer?.let { shLP.lowpass(it.cut, 0.5) }
    }

    /**
     * Per-block control update. [speed] in km/h, [accel] -1..1.
     * Returns true when the pack wants a braam fired now.
     */
    fun update(speed: Double, accel: Double, lowBattery: Boolean, flickerNow: Boolean, jitter: Double, blockSec: Double): Boolean {
        fade.to(fadeTarget, k(if (fadeTarget > 0) 0.3 else 0.25))
        val s = (speed / 180.0).coerceIn(0.0, 1.0)
        val ap = max(accel, 0.0); val an = max(-accel, 0.0)

        // drone
        dF.to(d.base + s * d.range, k(0.05))
        dCut.to(d.cut + ap * d.cutA + s * d.cutS, k(0.05))
        val dg = d.gain + ap * d.gainA
        flicker = if (lowBattery && flickerNow) 0.25 else 1.0
        dGain.to(dg, k(0.08))
        dBreath.to(if (speed < 3) d.breath else d.breath * 0.25, k(0.4))
        rumbleGain.to(d.rumble * 10 * (0.4 + ap + s * 0.5), k(0.1))
        dLP.lowpass(dCut.v, d.q)
        meters[0] = dg / (d.gain + d.gainA)

        pack.pulse?.let { pulseRate.to(it.rate + s * it.rateS + ap * it.rateA, k(0.2)) }

        wLayer?.let { w ->
            val wf = max(40.0, w.base + s * w.range + ap * w.aBend + an * w.rBend)
            wF.to(wf, k(0.08))
            val wg = if (speed < 1) w.idle else w.gain + s * w.gainS + ap * w.gainA + an * w.gainR
            wJitter = if (lowBattery) jitter else 1.0
            wGain.to(wg, k(0.05))
            if (w.vibDepth > 0) wVib.to(wF.v * w.vibDepth * (0.6 + ap), k(0.1))
            if (w.filter == FilterType.LOWPASS) wFilter.lowpass(wF.v * 3, w.q) else wFilter.bandpass(wF.v, w.q)
            meters[1] = wg / maxOf(1e-6, w.gain + w.gainS + w.gainA, w.idle)
        }

        nLayer?.let { n ->
            val ng = ap * n.gainA + an * n.gainR + s * n.gainS
            nF.to(n.base + ap * n.fa + s * n.fs, k(0.05))
            nGain.to(ng, k(0.05))
            if (n.filter == FilterType.LOWPASS) nFilter.lowpass(nF.v, n.q) else nFilter.bandpass(nF.v, n.q)
            meters[2] = ng / (n.gainA + n.gainS)
        }

        pLayer?.let { p ->
            padRoot.to(p.root * 2.0.pow(s * p.track), k(0.12))
            padCut.to(p.cut + ap * p.cutA + s * p.cutS, k(if (p.attack > 0) 0.3 else 0.05))
            val pg = p.gain + ap * p.gainA + s * p.gainS
            padGain.to(pg * padScale, k(if (p.attack > 0) p.attack else 0.05))
            padLP.lowpass(padCut.v, p.q)
            meters[3] = pg / (p.gain + p.gainA + p.gainS)
        }

        shLayer?.let { sh ->
            val rate = sh.rate + ap * sh.rateA - an * sh.rateR
            for (i in shP.indices) { var p = shP[i] + rate * blockSec / sh.octaves; p -= kotlin.math.floor(p); shP[i] = p }
            val sg = sh.gain + ap * sh.gainA + an * sh.gainR + s * sh.gainS
            shGain.to(sg, k(0.15))
            meters[4] = sg / (sh.gain + sh.gainA + sh.gainS)
        }

        var fire = false
        pack.braam?.let { b ->
            braamCool -= blockSec
            if (fadeTarget > 0 && ap > b.th && braamArmed && braamCool <= 0) { fire = true; braamArmed = false; braamCool = 3.0 }
            if (ap < b.th * 0.4) braamArmed = true
        }
        return fire
    }

    /** Adds this pack's samples (already faded) into [out]. */
    fun render(out: DoubleArray, n: Int) {
        val fg = fade.v
        if (fg < 1e-5) return

        // per-block constants
        val df = dF.v
        for (v in dDt.indices) dDt[v] = df * d.voices[v].ratio / srD
        val dg = dGain.v * flicker; val br = dBreath.v
        val dBr = 0.35 / srD
        val rg = rumbleGain.v
        val drive = d.drive
        val pulse = pack.pulse
        val pDt = pulseRate.v / srD

        val w = wLayer
        val wf = wF.v
        val wg = wGain.v * wJitter
        val vibDt = (w?.vibRate ?: 0.0) / srD
        val vib = wVib.v

        val nl = nLayer; val ng = nGain.v

        val pl = pLayer
        val root = padRoot.v
        for (v in padDt.indices) padDt[v] = root * 2.0.pow(padNotes[v] / 12 + padCents[v] / 1200) / srD
        val pg = padGain.v

        val sh = shLayer
        if (sh != null) for (i in shP.indices) {
            shDt[i] = sh.base * 2.0.pow(shP[i] * sh.octaves) / srD
            shAmp[i] = 0.5 - 0.5 * cos(2 * PI * shP[i])
        }
        val sg = shGain.v

        for (i in 0 until n) {
            // drone + breathing
            var dsum = 0.0
            for (v in dPh.indices) {
                val vo = d.voices[v]
                dsum += waveSample(vo.wave, dPh[v], dDt[v]) * vo.gain
                var p = dPh[v] + dDt[v]; if (p >= 1) p -= 1; dPh[v] = p
            }
            rumbleLp = rumbleNoise.next() * (1 - rumbleCoef) + rumbleLp * rumbleCoef
            val lowpassed = dLP.process(dsum)
            val saturated = (kotlin.math.tanh(drive * lowpassed + 0.25) - satBias) * satNorm
            val droneOut = saturated * (dg + br * sin(2 * PI * breathPh) + rg * rumbleLp)
            breathPh += dBr; if (breathPh >= 1) breathPh -= 1

            val pulseGain = if (pulse != null) {
                val g = (1 - pulse.depth / 2) + pulse.depth / 2 * sin(2 * PI * pulsePh)
                pulsePh += pDt; if (pulsePh >= 1) pulsePh -= 1
                g
            } else 1.0

            var sample = droneOut * pulseGain

            if (w != null) {
                val f = wf + vib * sin(2 * PI * vibPh)
                vibPh += vibDt; if (vibPh >= 1) vibPh -= 1
                val dt1 = f / srD; val dt2 = f * w.second / srD
                val raw = waveSample(w.wave, wPh1, dt1) + w.secondGain * waveSample(w.wave, wPh2, dt2)
                wPh1 += dt1; if (wPh1 >= 1) wPh1 -= 1
                wPh2 += dt2; if (wPh2 >= 1) wPh2 -= 1
                sample += wFilter.process(raw) * wg * 0.75
            }

            if (nl != null) sample += nFilter.process(noise.next()) * ng

            if (pl != null) {
                var psum = 0.0
                for (v in padPh.indices) {
                    psum += waveSample(pl.wave, padPh[v], padDt[v])
                    var p = padPh[v] + padDt[v]; if (p >= 1) p -= 1; padPh[v] = p
                }
                val padOut = padLP.process(psum) * pg
                sample += if (pulse != null && pulse.pad) padOut * pulseGain else padOut
            }

            if (sh != null) {
                var ssum = 0.0
                for (v in shPh.indices) {
                    ssum += sin(2 * PI * shPh[v]) * shAmp[v]
                    var p = shPh[v] + shDt[v]; if (p >= 1) p -= 1; shPh[v] = p
                }
                sample += shLP.process(ssum) * sg
            }

            out[i] += sample * fg
        }
    }
}

/** A cinematic brass swell: detuned saw chord through an opening low-pass, plus a sub octave. */
internal class BraamVoice(b: Braam, private val sr: Int) {
    private val freqs: DoubleArray
    private val ph: DoubleArray
    private val lp = Biquad(sr)
    private var t = 0.0
    private val peakGain = b.gain
    private val peakCut = b.peak
    private val subDt = b.root / 2 / sr
    private var subPh = 0.0
    private var g = 0.0
    val done: Boolean get() = t > 5.0

    init {
        val f = ArrayList<Double>()
        for (n in b.notes) for (c in doubleArrayOf(-12.0, 0.0, 9.0)) f.add(b.root * 2.0.pow(n / 12 + c / 1200))
        freqs = f.toDoubleArray(); ph = DoubleArray(freqs.size)
    }

    fun render(out: DoubleArray, n: Int) {
        val blockSec = n.toDouble() / sr
        // filter envelope: 120 -> peak (0.9 s, exponential) -> 180 (by 3.8 s)
        val cut = if (t < 0.9) 120.0 * (peakCut / 120.0).pow(t / 0.9)
                  else peakCut * (180.0 / peakCut).pow(min(1.0, (t - 0.9) / 2.9))
        lp.lowpass(cut, 1.5)
        // gain envelope: linear rise to peak in 0.35 s, settle to 70%, release after 2.4 s
        val target = when {
            t < 0.35 -> peakGain * (t / 0.35)
            t < 2.4 -> peakGain * 0.7 + (peakGain * 0.3) * kotlin.math.exp(-(t - 0.35) / 0.8)
            else -> 0.0
        }
        val kk = if (t < 0.35) 1.0 else 1 - kotlin.math.exp(-blockSec / (if (t < 2.4) 0.8 else 0.6))
        g += (target - g) * kk
        val scale = 0.6
        for (i in 0 until n) {
            var s = 0.0
            for (v in freqs.indices) {
                val dt = freqs[v] / sr
                s += sawBlep(ph[v], dt)
                var p = ph[v] + dt; if (p >= 1) p -= 1; ph[v] = p
            }
            val sub = sin(2 * PI * subPh); subPh += subDt; if (subPh >= 1) subPh -= 1
            out[i] += (lp.process(s * scale) + sub) * g
        }
        t += blockSec
    }
}
