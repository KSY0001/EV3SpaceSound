package com.ev3.spacesound.audio

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class RegenStyle(val label: String) {
    ABSORB("에너지 흡수"), GLIDE("파워 다운"), CHARGE("충전 험"), CLASSIC("기존 반짝임"), OFF("없음")
}

/**
 * Braking (regeneration) sounds shared by all packs, ported from the web prototype.
 *  - ABSORB: soft wide chord + airy noise, sinking slowly while regen continues
 *  - GLIDE : detuned saws + sub that wind down with speed through a resonant low-pass
 *  - CHARGE: two slowly beating sines whose pitch creeps up while charging, plus faint shimmer
 */
internal class RegenVoice(private val sr: Int, private val k: (Double) -> Double) {
    private val srD = sr.toDouble()
    var style = RegenStyle.ABSORB
    /** 0..1, for the UI meter. */
    var level = 0.0; private set
    private var charge = 0.0

    // absorb
    private val abSemis = doubleArrayOf(0.0, 3.0, 7.0, 14.0)
    private val abBank = OscBank(Wave.TRI, 8)
    private val abLpL = Biquad(sr); private val abLpR = Biquad(sr)
    private val abRoot = Sm(146.8); private val abCut = Sm(600.0); private val abG = Sm(0.0)
    private val airNoise = Noise(); private val airBP = Biquad(sr)
    private val airF = Sm(2000.0); private val airG = Sm(0.0)

    // glide
    private val glBank = OscBank(Wave.SAW, 3)
    private val glLpL = Biquad(sr); private val glLpR = Biquad(sr)
    private var glSubPh = 0.0
    private val glF = Sm(120.0); private val glCut = Sm(300.0); private val glG = Sm(0.0)

    // charge
    private val chBank = OscBank(Wave.SINE, 3)
    private val chF = Sm(196.0); private val chG = Sm(0.0)
    private val shNoise = Noise(); private val shHP = Biquad(sr).also { it.highpass(6000.0, 0.7) }
    private val shG = Sm(0.0)

    init {
        var i = 0
        abSemis.forEachIndexed { idx, semi ->
            for (j in 0..1) {
                val c = if (j == 0) -6.0 else 6.0
                val pan = (if (j == 1) 1 else -1) * (0.3 + idx * 0.15)
                abBank.setVoice(i++, 2.0.pow(semi / 12) * cents(c), 0.35, pan)
            }
        }
        doubleArrayOf(-10.0, 0.0, 10.0).forEachIndexed { j, c -> glBank.setVoice(j, cents(c), 0.6, spreadAt(j, 3) * 0.7) }
        chBank.setVoice(0, 1.0, 1.0, -0.8)
        chBank.setVoice(1, cents(4.0), 1.0, 0.8)
        chBank.setVoice(2, 2.0, 0.3, 0.0)
    }

    /** [an] = regen amount 0..1, [speed] km/h, [on] engine audible. */
    fun update(an: Double, speed: Double, on: Boolean, blockSec: Double) {
        val s01 = (speed / 180.0).coerceIn(0.0, 1.0)
        val lvl = if (on && speed > 4) min(1.0, an * 1.4) else 0.0
        val tc = if (lvl > 0) 0.25 else 0.5
        charge = if (lvl > 0.05) min(1.0, charge + blockSec * 0.25 * lvl) else max(0.0, charge - blockSec * 0.8)
        level = if (style == RegenStyle.OFF || style == RegenStyle.CLASSIC) 0.0 else lvl

        abRoot.to(146.8 * 2.0.pow(-charge * 2 / 12), k(0.3))
        abCut.to(350 + lvl * 1800 - charge * 250, k(0.2))
        abG.to(if (style == RegenStyle.ABSORB) lvl * 0.09 else 0.0, k(tc))
        airF.to(900 + s01 * 3000, k(0.3))
        airG.to(if (style == RegenStyle.ABSORB) lvl * 0.05 else 0.0, k(tc))
        abLpL.lowpass(abCut.v, 0.6); abLpR.lowpass(abCut.v, 0.6)
        airBP.bandpass(airF.v, 1.4)
        abBank.prepare(abRoot.v, srD)

        glF.to(55 + s01 * 240, k(0.08))
        glCut.to(glF.v * (2.5 + lvl * 5), k(0.1))
        glG.to(if (style == RegenStyle.GLIDE) lvl * 0.11 else 0.0, k(tc))
        glLpL.lowpass(glCut.v, 5.0); glLpR.lowpass(glCut.v, 5.0)
        glBank.prepare(glF.v, srD)

        chF.to(196 * 2.0.pow(charge * 7 / 12), k(0.15))
        chG.to(if (style == RegenStyle.CHARGE) lvl * 0.06 else 0.0, k(tc))
        shG.to(if (style == RegenStyle.CHARGE) lvl * 0.012 else 0.0, k(tc))
        chBank.prepare(chF.v, srD)
    }

    fun render(outL: DoubleArray, outR: DoubleArray, n: Int) {
        val ab = abG.v; val air = airG.v; val gl = glG.v; val ch = chG.v; val sh = shG.v
        if (ab + air + gl + ch + sh < 1e-6) return
        val subDt = glF.v / 2 / srD
        for (i in 0 until n) {
            var l = 0.0; var r = 0.0
            if (ab > 1e-6) {
                var al = 0.0; var ar = 0.0
                for (v in 0 until abBank.size) {
                    val x = tri(abBank.ph[v])
                    al += x * abBank.gL[v]; ar += x * abBank.gR[v]
                    var p = abBank.ph[v] + abBank.dt[v]; if (p >= 1) p -= 1; abBank.ph[v] = p
                }
                l += abLpL.process(al) * ab; r += abLpR.process(ar) * ab
            }
            if (air > 1e-6) { val x = airBP.process(airNoise.next()) * air; l += x; r += x }
            if (gl > 1e-6) {
                var sl = 0.0; var srr = 0.0
                for (v in 0 until glBank.size) {
                    val x = sawBlep(glBank.ph[v], glBank.dt[v])
                    sl += x * glBank.gL[v]; srr += x * glBank.gR[v]
                    var p = glBank.ph[v] + glBank.dt[v]; if (p >= 1) p -= 1; glBank.ph[v] = p
                }
                val sub = sin(2 * PI * glSubPh) * 0.5
                glSubPh += subDt; if (glSubPh >= 1) glSubPh -= 1
                l += (glLpL.process(sl) + sub) * gl; r += (glLpR.process(srr) + sub) * gl
            }
            if (ch > 1e-6) {
                var cl = 0.0; var cr = 0.0
                for (v in 0 until chBank.size) {
                    val x = sin(2 * PI * chBank.ph[v])
                    cl += x * chBank.gL[v]; cr += x * chBank.gR[v]
                    var p = chBank.ph[v] + chBank.dt[v]; if (p >= 1) p -= 1; chBank.ph[v] = p
                }
                l += cl * ch; r += cr * ch
            }
            if (sh > 1e-6) { val x = shHP.process(shNoise.next()) * sh; l += x; r += x }
            outL[i] += l; outR[i] += r
        }
    }
}
