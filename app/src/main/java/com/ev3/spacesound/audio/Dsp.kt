package com.ev3.spacesound.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** RBJ biquad in transposed direct form II. Bandpass uses constant 0 dB peak gain, like Web Audio. */
class Biquad(private val sampleRate: Int) {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0

    fun lowpass(freq: Double, q: Double) {
        val (w, alpha) = prep(freq, q)
        val c = cos(w)
        val a0 = 1 + alpha
        b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun bandpass(freq: Double, q: Double) {
        val (w, alpha) = prep(freq, q)
        val c = cos(w)
        val a0 = 1 + alpha
        b0 = alpha / a0; b1 = 0.0; b2 = -alpha / a0
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    private fun prep(freq: Double, q: Double): Pair<Double, Double> {
        val f = freq.coerceIn(10.0, sampleRate * 0.45)
        val w = 2 * PI * f / sampleRate
        return w to sin(w) / (2 * q)
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/** PolyBLEP band-limited sawtooth. phase in [0,1), dt = f / sampleRate. */
fun sawBlep(phase: Double, dt: Double): Double {
    var v = 2.0 * phase - 1.0
    if (phase < dt) {
        val t = phase / dt
        v -= t + t - t * t - 1.0
    } else if (phase > 1.0 - dt) {
        val t = (phase - 1.0) / dt
        v -= t * t + t + t + 1.0
    }
    return v
}

fun tri(phase: Double): Double = 4.0 * kotlin.math.abs(phase - 0.5) - 1.0

/** Fast xorshift white noise in [-1, 1]. */
class Noise {
    private var s = 0x2545F491
    fun next(): Double {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return s.toDouble() / Int.MAX_VALUE
    }
}

/** Small Freeverb-style reverb (4 combs + 2 allpasses) for the tunnel effect. */
class Reverb(sampleRate: Int) {
    private val scale = sampleRate / 44100.0
    private val combs = intArrayOf(1557, 1617, 1491, 1422).map { Comb((it * scale).toInt(), 0.86, 0.22) }
    private val allpasses = intArrayOf(556, 441).map { Allpass((it * scale).toInt(), 0.5) }

    fun process(x: Double): Double {
        val input = x * 0.2
        var out = 0.0
        for (c in combs) out += c.process(input)
        for (a in allpasses) out = a.process(out)
        return out
    }

    private class Comb(size: Int, val feedback: Double, val damp: Double) {
        private val buf = DoubleArray(size.coerceAtLeast(1)); private var i = 0; private var store = 0.0
        fun process(x: Double): Double {
            val y = buf[i]
            store = y * (1 - damp) + store * damp
            buf[i] = x + store * feedback
            if (++i >= buf.size) i = 0
            return y
        }
    }

    private class Allpass(size: Int, val feedback: Double) {
        private val buf = DoubleArray(size.coerceAtLeast(1)); private var i = 0
        fun process(x: Double): Double {
            val b = buf[i]
            buf[i] = x + b * feedback
            if (++i >= buf.size) i = 0
            return b - x
        }
    }
}

/** One-pole smoothing coefficient for a block of the given length. */
fun smoothK(tcSeconds: Double, blockSeconds: Double): Double = 1.0 - exp(-blockSeconds / tcSeconds)

/** Soft clipper, close to tanh for |x| < 3. */
fun softClip(x: Double): Double {
    val c = x.coerceIn(-3.0, 3.0)
    return c * (27 + c * c) / (27 + 9 * c * c)
}
