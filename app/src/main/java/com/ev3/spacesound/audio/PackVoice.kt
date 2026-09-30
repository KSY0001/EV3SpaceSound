package com.ev3.spacesound.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

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

/** Equal-power pan gains, same law as the Web Audio StereoPanner for a mono source. */
fun panL(p: Double) = cos((p.coerceIn(-1.0, 1.0) + 1) / 2 * PI / 2)
fun panR(p: Double) = sin((p.coerceIn(-1.0, 1.0) + 1) / 2 * PI / 2)
fun spreadAt(j: Int, n: Int) = if (n > 1) j.toDouble() / (n - 1) * 2 - 1 else 0.0
fun note(root: Double, semis: Double) = root * 2.0.pow(semis / 12)
fun cents(c: Double) = 2.0.pow(c / 1200)

/** One smoothed control value, updated once per block. */
internal class Sm(var v: Double) { fun to(t: Double, k: Double) { v += (t - v) * k } }

/** A set of oscillators, each with its own frequency ratio, level and pan. Phases persist between blocks. */
internal class OscBank(val wave: Wave, n: Int) {
    val ratio = DoubleArray(n) { 1.0 }
    val gL = DoubleArray(n); val gR = DoubleArray(n)
    val ph = DoubleArray(n)
    val dt = DoubleArray(n)
    val size = n
    fun setVoice(i: Int, r: Double, level: Double, pan: Double?) {
        ratio[i] = r
        if (pan == null) { gL[i] = level; gR[i] = level } else { gL[i] = level * panL(pan); gR[i] = level * panR(pan) }
    }
    fun prepare(freq: Double, sr: Double) { for (i in 0 until size) dt[i] = freq * ratio[i] / sr }
}

/**
 * The live synthesis of one sound pack, in stereo. Two can run at once while the user switches packs,
 * each with its own fade. The engine calls [update] once per block and [render] to add samples.
 */
internal class PackVoice(val pack: SoundPack, private val sr: Int, private val k: (Double) -> Double, startFade: Double) {
    private val srD = sr.toDouble()
    val fade = Sm(startFade)
    var fadeTarget = 1.0
    val finished: Boolean get() = fadeTarget == 0.0 && fade.v < 1e-4
    /** drone, tone, energy, pad, riser/brass */
    val meters = DoubleArray(5)

    private var speed = 0.0; private var s = 0.0; private var ap = 0.0; private var an = 0.0
    private var flick = 1.0; private var jitter = 1.0

    // ---------- drone ----------
    private val d = pack.drone
    private val dBank = OscBank(Wave.SINE, d.voices.size)
    private val dLP = Biquad(sr)
    private val dF = Sm(d.base); private val dCut = Sm(d.cut); private val dGain = Sm(0.0); private val dBreath = Sm(0.0)
    private val rumbleGain = Sm(0.0)
    private var breathPh = 0.0
    private val rumbleNoise = Noise(); private val rumbleLP = Biquad(sr).also { it.lowpass(22.0, 0.7) }
    private val satNorm = 1.0 / tanh(d.drive)
    private val pulse = pack.pulse
    private val pulseRate = Sm(pulse?.rate ?: 1.0); private var pulsePh = 0.0

    // ---------- whine ----------
    private val w = pack.whine
    private val wBank = w?.let { OscBank(it.wave, it.unison.size) }
    private var w2Ph = 0.0
    private val wFL = Biquad(sr); private val wFR = Biquad(sr)
    private val wBodyL = Biquad(sr); private val wBodyR = Biquad(sr)
    private val wBodyPh = DoubleArray(2)
    private val wF = Sm(w?.base ?: 100.0); private val wGain = Sm(0.0)
    private var wMotPh = 0.0

    // ---------- choir ----------
    private val ch = pack.choir
    private val chBank: OscBank? = ch?.let { c -> OscBank(Wave.SAW, c.voices * c.intervals.size) }
    private val chFL = Array(3) { Biquad(sr) }; private val chFR = Array(3) { Biquad(sr) }
    private val chFormGain = doubleArrayOf(1.6, 1.0, 0.5)
    private val chF = Sm(ch?.base ?: 100.0); private val chMorph = Sm(0.0); private val chGain = Sm(0.0)
    private var chMotPh = 0.0

    // ---------- fm ----------
    private class FmState(val f: Fm, sr: Int) {
        var modPh = 0.0; val carPh = DoubleArray(2)
        val lpL = Biquad(sr); val lpR = Biquad(sr)
        val freq = Sm(f.base); val index = Sm(f.idx); val cut = Sm(f.cut); val gain = Sm(0.0)
        val dnL = cents(-f.detune); val upR = cents(f.detune)
        val aL = panL(-f.width); val aR = panR(-f.width); val bL = panL(f.width); val bR = panR(f.width)
    }
    private val fms = pack.fm.map { FmState(it, sr) }

    // ---------- ring ----------
    private val rg = pack.ring
    private val ringCarPh = DoubleArray(2); private var ringModPh = 0.0
    private val ringFL = Biquad(sr); private val ringFR = Biquad(sr)
    private val ringF = Sm(rg?.base ?: 100.0); private val ringCut = Sm(rg?.cut ?: 500.0); private val ringGain = Sm(0.0)

    // ---------- noise ----------
    private val nl = pack.noise
    private val noise = Noise(); private val nFilter = Biquad(sr)
    private val nF = Sm(nl?.base ?: 500.0); private val nGain = Sm(0.0)

    // ---------- pads ----------
    private class PadState(val p: Pad, sr: Int) {
        val bank = OscBank(p.wave, p.notes.size * p.unison.size)
        val lpL = Biquad(sr); val lpR = Biquad(sr)
        val root = Sm(p.root); val cut = Sm(p.cut); val gain = Sm(0.0)
        var motPh = 0.0
        val scale = 1.0 / sqrt(p.unison.size.toDouble())
        init {
            var i = 0
            for (n in p.notes) for ((j, c) in p.unison.withIndex()) {
                bank.setVoice(i++, 2.0.pow(n / 12) * cents(c), 1.0, p.width * spreadAt(j, p.unison.size))
            }
        }
    }
    private val pads = pack.pads.map { PadState(it, sr) }

    // ---------- shepard ----------
    private val sh = pack.shepard
    private val shP = DoubleArray(sh?.voices ?: 0) { it.toDouble() / max(1, sh?.voices ?: 1) }
    private val shPh = DoubleArray(shP.size); private val shDt = DoubleArray(shP.size); private val shAmp = DoubleArray(shP.size)
    private val shLP = Biquad(sr)
    private val shGain = Sm(0.0)

    // ---------- braam trigger ----------
    private var braamArmed = true
    private var braamCool = 0.0
    private var braamHold = 0.0

    init {
        d.voices.forEachIndexed { i, v -> dBank.setVoice(i, v.ratio, v.gain, null) }
        if (w != null && wBank != null) {
            val lvl = 1 / sqrt(w.unison.size.toDouble())
            w.unison.forEachIndexed { j, c -> wBank.setVoice(j, cents(c), lvl, w.width * spreadAt(j, w.unison.size)) }
        }
        if (ch != null && chBank != null) {
            var i = 0
            for ((semi, g) in ch.intervals) for (j in 0 until ch.voices) {
                chBank.setVoice(i++, 2.0.pow(semi / 12) * cents(ch.spread * spreadAt(j, ch.voices)), g / sqrt(ch.voices.toDouble()),
                    ch.width * spreadAt(j, ch.voices))
            }
        }
        sh?.let { shLP.lowpass(it.cut, 0.5) }
    }

    /** Per-block control update. Returns true when the pack wants a braam fired now. */
    fun update(speedKmh: Double, accel: Double, lowBattery: Boolean, flickerNow: Boolean, jitterNow: Double, blockSec: Double, braamHoldSec: Double = 0.0): Boolean {
        fade.to(fadeTarget, k(if (fadeTarget > 0) 0.3 else 0.25))
        speed = speedKmh; s = (speedKmh / 180.0).coerceIn(0.0, 1.0)
        ap = max(accel, 0.0); an = max(-accel, 0.0)
        flick = if (lowBattery && flickerNow) 0.25 else 1.0
        jitter = if (lowBattery) jitterNow else 1.0
        var tone = 0.0

        // drone
        dF.to(d.base + s * d.range, k(0.05))
        dCut.to(d.cut + ap * d.cutA + s * d.cutS, k(0.05))
        val dg = d.gain + ap * d.gainA
        dGain.to(dg, k(0.08))
        dBreath.to(if (speed < 3) d.breath else d.breath * 0.25, k(0.4))
        rumbleGain.to(d.rumble * 10 * (0.4 + ap + s * 0.5), k(0.1))
        dLP.lowpass(dCut.v, d.q)
        dBank.prepare(dF.v, srD)
        meters[0] = dg / (d.gain + d.gainA)
        pulse?.let { pulseRate.to(it.rate + s * it.rateS + ap * it.rateA, k(0.2)) }

        // whine
        if (w != null && wBank != null) {
            val wf = max(40.0, w.base + s * w.range + ap * w.aBend + an * w.rBend)
            wF.to(wf, k(0.08))
            val wg = if (speed < 1) w.idle else w.gain + s * w.gainS + ap * w.gainA + an * w.gainR
            wGain.to(wg, k(0.05))
            wBank.prepare(wF.v, srD)
            val mot = if (w.motionRate > 0) wF.v * w.motionDepth * sin(2 * PI * wMotPh) else 0.0
            wMotPh += w.motionRate * blockSec; wMotPh -= kotlin.math.floor(wMotPh)
            wFL.bandpass(wF.v + mot, w.q); wFR.bandpass(wF.v + mot, w.q)
            w.body?.let { b -> wBodyL.bandpass(wF.v * b.ratio * 2, b.q); wBodyR.bandpass(wF.v * b.ratio * 2, b.q) }
            tone = max(tone, wg / maxOf(1e-6, w.gain + w.gainS + w.gainA + w.gainR * .5, w.idle))
        }

        // choir
        if (ch != null && chBank != null) {
            chF.to(ch.base + s * ch.range + ap * ch.aBend + an * ch.rBend, k(0.08))
            chMorph.to(min(1.0, s * .7 + ap * .6), k(0.15))
            val cg = if (speed < 1) ch.idle else ch.gain + s * ch.gainS + ap * ch.gainA + an * ch.gainR
            chGain.to(cg, k(0.08))
            chBank.prepare(chF.v, srD)
            val mot = ch.motionDepth * sin(2 * PI * chMotPh)
            chMotPh += ch.motionRate * blockSec; chMotPh -= kotlin.math.floor(chMotPh)
            for (kk in 0 until 3) {
                val f = ch.from.at(kk) + (ch.to.at(kk) - ch.from.at(kk)) * chMorph.v + mot
                chFL[kk].bandpass(f, ch.q); chFR[kk].bandpass(f, ch.q)
            }
            tone = max(tone, cg / (ch.gain + ch.gainS + ch.gainA))
        }

        // fm
        fms.forEachIndexed { i, st ->
            val f = st.f
            st.freq.to(max(30.0, f.base + s * f.range + ap * f.aBend + an * f.rBend), k(0.06))
            st.index.to(f.idx + ap * f.idxA + s * f.idxS + an * f.idxR, k(0.08))
            st.cut.to(f.cut + ap * f.cutA + s * f.cutS, k(0.08))
            val g = if (speed < 1) f.idle else f.gain + s * f.gainS + ap * f.gainA + an * f.gainR
            st.gain.to(g, k(0.06))
            st.lpL.lowpass(st.cut.v, 0.8); st.lpR.lowpass(st.cut.v, 0.8)
            if (i == 0) tone = max(tone, g / (f.gain + f.gainS + f.gainA))
        }

        // ring
        if (rg != null) {
            ringF.to(max(30.0, rg.base + s * rg.range + ap * rg.aBend + an * rg.rBend), k(0.06))
            ringCut.to(rg.cut + ap * rg.cutA + s * rg.cutS, k(0.08))
            val g = if (speed < 1) rg.idle else rg.gain + s * rg.gainS + ap * rg.gainA + an * rg.gainR
            ringGain.to(g, k(0.06))
            ringFL.bandpass(ringCut.v, rg.q); ringFR.bandpass(ringCut.v, rg.q)
            tone = max(tone, g / (rg.gain + rg.gainS + rg.gainA))
        }
        meters[1] = tone

        // noise
        if (nl != null) {
            val ng = ap * nl.gainA + an * nl.gainR + s * nl.gainS
            nF.to(nl.base + ap * nl.fa + s * nl.fs, k(0.05))
            nGain.to(ng, k(0.05))
            if (nl.filter == FilterType.LOWPASS) nFilter.lowpass(nF.v, nl.q) else nFilter.bandpass(nF.v, nl.q)
            meters[2] = ng / (nl.gainA + nl.gainS)
        } else meters[2] = 0.0

        // pads
        meters[3] = 0.0; var brassLv = 0.0
        for (st in pads) {
            val p = st.p
            st.root.to(p.root * 2.0.pow(s * p.track), k(0.12))
            st.cut.to(p.cut + ap * p.cutA + s * p.cutS, k(if (p.attack > 0) 0.3 else 0.05))
            val pg = p.gain + ap * p.gainA + s * p.gainS
            st.gain.to(pg * st.scale, k(if (p.attack > 0) p.attack else 0.05))
            val mot = if (p.motionRate > 0) p.motionDepth * sin(2 * PI * st.motPh) else 0.0
            st.motPh += p.motionRate * blockSec; st.motPh -= kotlin.math.floor(st.motPh)
            st.lpL.lowpass(st.cut.v + mot, p.q); st.lpR.lowpass(st.cut.v + mot, p.q)
            st.bank.prepare(st.root.v, srD)
            val l = pg / (p.gain + p.gainA + p.gainS)
            if (p.brass) brassLv = l else meters[3] = max(meters[3], l)
        }

        // shepard riser
        meters[4] = brassLv
        if (sh != null) {
            val rate = sh.rate + ap * sh.rateA - an * sh.rateR
            for (i in shP.indices) {
                var p = shP[i] + rate * blockSec / sh.octaves; p -= kotlin.math.floor(p); shP[i] = p
                shDt[i] = sh.base * 2.0.pow(p * sh.octaves) / srD
                shAmp[i] = 0.5 - 0.5 * cos(2 * PI * p)
            }
            val sg = sh.gain + ap * sh.gainA + an * sh.gainR + s * sh.gainS
            shGain.to(sg, k(0.15))
            meters[4] = max(meters[4], sg / (sh.gain + sh.gainA + sh.gainS))
        }

        var fire = false
        pack.braam?.let { b ->
            braamCool -= blockSec
            braamHold = if (ap > b.th) braamHold + blockSec else 0.0
            if (fadeTarget > 0 && braamHold > braamHoldSec && braamArmed && braamCool <= 0) { fire = true; braamArmed = false; braamCool = 3.0 }
            if (ap < b.th * 0.4) braamArmed = true
        }
        return fire
    }

    /** Adds this pack's stereo samples (already faded) into [outL]/[outR]. */
    fun render(outL: DoubleArray, outR: DoubleArray, n: Int) {
        val fg = fade.v
        if (fg < 1e-5) return
        val dg = dGain.v * flick; val br = dBreath.v; val rgn = rumbleGain.v
        val dBr = 0.35 / srD
        val pDt = pulseRate.v / srD
        val wg = wGain.v * jitter * 0.75
        val w2Dt = wF.v * (w?.second ?: 1.0) / srD
        val bodyDt = wF.v * (w?.body?.ratio ?: 0.5) / srD
        val bodyDt0 = bodyDt * cents(-7.0); val bodyDt1 = bodyDt * cents(7.0)
        val bL0 = panL(-0.4); val bR0 = panR(-0.4); val bL1 = panL(0.4); val bR1 = panR(0.4)
        val wSat = w?.sat ?: 0.0
        val wSatNorm = if (wSat > 0) 1.0 / tanh(wSat) else 1.0
        val cgn = chGain.v * flick
        val ringG = ringGain.v * flick
        val ringCarDt0 = ringF.v * cents(-7.0) / srD; val ringCarDt1 = ringF.v * cents(7.0) / srD
        val ringModDt = ringF.v * (rg?.ratio ?: 1.0) / srD
        val rWidth = rg?.width ?: 0.0
        val rL0 = panL(-rWidth); val rR0 = panR(-rWidth); val rL1 = panL(rWidth); val rR1 = panR(rWidth)
        val ng = nGain.v; val sg = shGain.v

        for (i in 0 until n) {
            var l = 0.0; var r = 0.0

            // drone (centre) with saturation, rumble, breathing and pulse
            var dsum = 0.0
            for (v in 0 until dBank.size) {
                dsum += waveSample(d.voices[v].wave, dBank.ph[v], dBank.dt[v]) * dBank.gL[v]
                var p = dBank.ph[v] + dBank.dt[v]; if (p >= 1) p -= 1; dBank.ph[v] = p
            }
            val rum = rumbleLP.process(rumbleNoise.next())
            val droneAmp = dg + br * sin(2 * PI * breathPh) + rgn * rum
            breathPh += dBr; if (breathPh >= 1) breathPh -= 1
            val lp = dLP.process(dsum).coerceIn(-1.0, 1.0)
            var drone = (tanh(d.drive * lp + 0.25) - tanh(0.25)) * satNorm * droneAmp
            val pulseGain = if (pulse != null) {
                val g = (1 - pulse.depth / 2) + pulse.depth / 2 * sin(2 * PI * pulsePh)
                pulsePh += pDt; if (pulsePh >= 1) pulsePh -= 1
                g
            } else 1.0
            drone *= pulseGain
            l += drone; r += drone

            // whine: panned unison + centre partial -> band-pass -> saturation; body tone joins after
            if (w != null && wBank != null) {
                var wl = 0.0; var wr = 0.0
                for (v in 0 until wBank.size) {
                    val x = waveSample(w.wave, wBank.ph[v], wBank.dt[v])
                    wl += x * wBank.gL[v]; wr += x * wBank.gR[v]
                    var p = wBank.ph[v] + wBank.dt[v]; if (p >= 1) p -= 1; wBank.ph[v] = p
                }
                val x2 = waveSample(w.wave, w2Ph, w2Dt) * w.secondGain
                w2Ph += w2Dt; if (w2Ph >= 1) w2Ph -= 1
                var fl = wFL.process(wl + x2); var fr = wFR.process(wr + x2)
                if (wSat > 0) {
                    fl = tanh(wSat * fl.coerceIn(-1.0, 1.0)) * wSatNorm
                    fr = tanh(wSat * fr.coerceIn(-1.0, 1.0)) * wSatNorm
                }
                val b = w.body
                if (b != null) {
                    val b0 = sawBlep(wBodyPh[0], bodyDt0); val b1 = sawBlep(wBodyPh[1], bodyDt1)
                    var p0 = wBodyPh[0] + bodyDt0; if (p0 >= 1) p0 -= 1; wBodyPh[0] = p0
                    var p1 = wBodyPh[1] + bodyDt1; if (p1 >= 1) p1 -= 1; wBodyPh[1] = p1
                    fl += wBodyL.process(b0 * bL0 + b1 * bL1) * b.gain
                    fr += wBodyR.process(b0 * bR0 + b1 * bR1) * b.gain
                }
                l += fl * wg; r += fr * wg
            }

            // choir: panned saw unison -> three formant band-passes
            if (ch != null && chBank != null) {
                var cl = 0.0; var cr = 0.0
                for (v in 0 until chBank.size) {
                    val x = sawBlep(chBank.ph[v], chBank.dt[v])
                    cl += x * chBank.gL[v]; cr += x * chBank.gR[v]
                    var p = chBank.ph[v] + chBank.dt[v]; if (p >= 1) p -= 1; chBank.ph[v] = p
                }
                var ol = 0.0; var orr = 0.0
                for (kk in 0 until 3) { ol += chFL[kk].process(cl) * chFormGain[kk]; orr += chFR[kk].process(cr) * chFormGain[kk] }
                val g = cgn * (if (ch.pulsed) pulseGain else 1.0)
                l += ol * g; r += orr * g
            }

            // fm: one modulator, two detuned carriers panned apart
            for (st in fms) {
                val f = st.f
                val fr0 = st.freq.v
                val mod = sin(2 * PI * st.modPh)
                st.modPh += fr0 * f.ratio / srD; if (st.modPh >= 1) st.modPh -= 1
                val inst = fr0 + st.index.v * fr0 * f.ratio * mod
                val c0 = sin(2 * PI * st.carPh[0]); val c1 = sin(2 * PI * st.carPh[1])
                var p0 = st.carPh[0] + inst * st.dnL / srD; p0 -= kotlin.math.floor(p0); st.carPh[0] = p0
                var p1 = st.carPh[1] + inst * st.upR / srD; p1 -= kotlin.math.floor(p1); st.carPh[1] = p1
                val g = st.gain.v * flick
                l += st.lpL.process(c0 * st.aL + c1 * st.bL) * g
                r += st.lpR.process(c0 * st.aR + c1 * st.bR) * g
            }

            // ring modulator
            if (rg != null) {
                val m = sin(2 * PI * ringModPh)
                ringModPh += ringModDt; if (ringModPh >= 1) ringModPh -= 1
                val a0 = sawBlep(ringCarPh[0], ringCarDt0) * m; val a1 = sawBlep(ringCarPh[1], ringCarDt1) * m
                var p0 = ringCarPh[0] + ringCarDt0; if (p0 >= 1) p0 -= 1; ringCarPh[0] = p0
                var p1 = ringCarPh[1] + ringCarDt1; if (p1 >= 1) p1 -= 1; ringCarPh[1] = p1
                l += ringFL.process(a0 * rL0 + a1 * rL1) * ringG
                r += ringFR.process(a0 * rR0 + a1 * rR1) * ringG
            }

            // noise (centre)
            if (nl != null) { val x = nFilter.process(noise.next()) * ng; l += x; r += x }

            // pads
            for (st in pads) {
                val bank = st.bank
                var pl = 0.0; var pr = 0.0
                for (v in 0 until bank.size) {
                    val x = waveSample(st.p.wave, bank.ph[v], bank.dt[v])
                    pl += x * bank.gL[v]; pr += x * bank.gR[v]
                    var p = bank.ph[v] + bank.dt[v]; if (p >= 1) p -= 1; bank.ph[v] = p
                }
                val g = st.gain.v
                l += st.lpL.process(pl) * g; r += st.lpR.process(pr) * g
            }

            // shepard riser (centre)
            if (sh != null) {
                var ssum = 0.0
                for (v in shPh.indices) {
                    ssum += waveSample(sh.wave, shPh[v], shDt[v]) * shAmp[v]
                    var p = shPh[v] + shDt[v]; if (p >= 1) p -= 1; shPh[v] = p
                }
                val x = shLP.process(ssum) * sg
                l += x; r += x
            }

            outL[i] += l * fg; outR[i] += r * fg
        }
    }
}

/** A cinematic brass swell: detuned saw chord spread across the stereo field through an opening low-pass, plus a sub octave. */
internal class BraamVoice(b: Braam, private val sr: Int) {
    private val dts: DoubleArray
    private val gL: DoubleArray; private val gR: DoubleArray
    private val ph: DoubleArray
    private val lpL = Biquad(sr); private val lpR = Biquad(sr)
    private var t = 0.0
    private val peakGain = b.gain
    private val peakCut = b.peak
    private val subDt = b.root / 2 / sr
    private var subPh = 0.0
    private var g = 0.0
    val done: Boolean get() = t > 5.0

    init {
        val f = ArrayList<Double>(); val l = ArrayList<Double>(); val r = ArrayList<Double>()
        val det = doubleArrayOf(-12.0, -4.0, 4.0, 12.0)
        for (n in b.notes) for ((j, c) in det.withIndex()) {
            f.add(note(b.root, n) * cents(c) / sr)
            val pan = spreadAt(j, det.size) * 0.6
            l.add(panL(pan)); r.add(panR(pan))
        }
        dts = f.toDoubleArray(); gL = l.toDoubleArray(); gR = r.toDoubleArray(); ph = DoubleArray(dts.size)
    }

    fun render(outL: DoubleArray, outR: DoubleArray, n: Int) {
        val blockSec = n.toDouble() / sr
        val cut = if (t < 0.9) 120.0 * (peakCut / 120.0).pow(t / 0.9)
                  else peakCut * (180.0 / peakCut).pow(min(1.0, (t - 0.9) / 2.9))
        lpL.lowpass(cut, 1.5); lpR.lowpass(cut, 1.5)
        val target = when {
            t < 0.35 -> peakGain * (t / 0.35)
            t < 2.4 -> peakGain * 0.7 + (peakGain * 0.3) * exp(-(t - 0.35) / 0.8)
            else -> 0.0
        }
        val kk = if (t < 0.35) 1.0 else 1 - exp(-blockSec / (if (t < 2.4) 0.8 else 0.6))
        g += (target - g) * kk
        for (i in 0 until n) {
            var l = 0.0; var r = 0.0
            for (v in dts.indices) {
                val x = sawBlep(ph[v], dts[v])
                l += x * gL[v]; r += x * gR[v]
                var p = ph[v] + dts[v]; if (p >= 1) p -= 1; ph[v] = p
            }
            val sub = sin(2 * PI * subPh); subPh += subDt; if (subPh >= 1) subPh -= 1
            outL[i] += (lpL.process(l) + sub) * g
            outR[i] += (lpR.process(r) + sub) * g
        }
        t += blockSec
    }
}
