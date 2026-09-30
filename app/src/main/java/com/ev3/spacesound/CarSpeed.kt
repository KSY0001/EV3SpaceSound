package com.ev3.spacesound

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Collects Android Auto speed callbacks, keeps statistics for debugging and estimates
 * acceleration from the speed history (least-squares slope over a short window).
 *
 * All callbacks arrive on the main thread; estimates are read from the controller thread,
 * so the public entry points are synchronized.
 */
object CarSpeed {
    /** One speed callback as delivered by the host. Speeds in m/s, NaN when not provided. */
    data class Raw(val tNanos: Long, val raw: Float, val rawStatus: Int, val disp: Float, val dispStatus: Int, val used: Float, val usedField: String, val carTsMs: Long, val deliveryMs: Long)

    data class Estimate(
        val speedMs: Float,      // extrapolated to "now"
        val accel: Float,        // m/s², slope of the recent window
        val ageMs: Long,         // time since the last usable sample
        val samplesInWindow: Int,
        val fresh: Boolean,
    )

    // ---------- configuration ----------
    /** Samples older than this are ignored for the slope. */
    @Volatile var windowSec = 0.7
    /** Speed is treated as lost after this long without a usable sample. */
    @Volatile var staleMs = 1500L

    // ---------- statistics (read by the UI) ----------
    @Volatile var total = 0L; private set
    @Volatile var usable = 0L; private set
    @Volatile var repeats = 0L; private set
    @Volatile var lastRaw: Raw? = null; private set
    @Volatile var intervalAvgMs = 0.0; private set
    @Volatile var intervalMinMs = Double.NaN; private set
    @Volatile var intervalMaxMs = 0.0; private set
    @Volatile var jitterMs = 0.0; private set
    /** Smallest non-zero change seen between consecutive samples, in km/h. Tells the resolution. */
    @Volatile var minStepKmh = Double.NaN; private set
    /** True once any sample had a fractional km/h value. */
    @Volatile var fractional = false; private set
    @Volatile var rawSeen = 0L; private set
    @Volatile var dispSeen = 0L; private set
    @Volatile var firstSampleNanos = 0L; private set
    /** Time from the value's own timestamp to our callback (host + transport delay), smoothed. */
    @Volatile var deliveryAvgMs = Double.NaN; private set
    val rate = RateCounter(3.0)

    private const val CAP = 128
    private val tBuf = LongArray(CAP)
    private val vBuf = FloatArray(CAP)
    private var head = 0
    private var count = 0
    private var lastUsedNanos = 0L
    private var lastUsedValue = Float.NaN

    /** Optional sink for every callback (debug event log). */
    @Volatile var eventSink: ((Raw) -> Unit)? = null

    @Synchronized fun reset() {
        total = 0; usable = 0; repeats = 0; lastRaw = null
        intervalAvgMs = 0.0; intervalMinMs = Double.NaN; intervalMaxMs = 0.0; jitterMs = 0.0
        minStepKmh = Double.NaN; fractional = false; rawSeen = 0; dispSeen = 0; firstSampleNanos = 0; deliveryAvgMs = Double.NaN
        head = 0; count = 0; lastUsedNanos = 0; lastUsedValue = Float.NaN
    }

    @Synchronized fun onSample(raw: Float?, rawStatus: Int, disp: Float?, dispStatus: Int, successCode: Int, carTsMs: Long) {
        val now = SystemClock.elapsedRealtimeNanos()
        val delivery = if (carTsMs > 0) now / 1_000_000 - carTsMs else -1L
        total++
        val rawOk = rawStatus == successCode && raw != null
        val dispOk = dispStatus == successCode && disp != null
        if (rawOk) rawSeen++
        if (dispOk) dispSeen++
        val used: Float; val field: String
        when {
            rawOk -> { used = raw!!; field = "raw" }
            dispOk -> { used = disp!!; field = "display" }
            else -> { used = Float.NaN; field = "none" }
        }
        val r = Raw(now, raw ?: Float.NaN, rawStatus, disp ?: Float.NaN, dispStatus, used, field, carTsMs, delivery)
        if (delivery in 0..10_000) deliveryAvgMs = if (deliveryAvgMs.isNaN()) delivery.toDouble() else deliveryAvgMs * 0.9 + delivery * 0.1
        lastRaw = r
        eventSink?.invoke(r)
        if (used.isNaN()) return

        usable++
        rate.tick()
        if (firstSampleNanos == 0L) firstSampleNanos = now
        val kmh = used * 3.6
        if (abs(kmh - Math.rint(kmh)) > 0.05) fractional = true

        if (lastUsedNanos != 0L) {
            val dtMs = (now - lastUsedNanos) / 1e6
            intervalAvgMs = if (intervalAvgMs == 0.0) dtMs else intervalAvgMs * 0.9 + dtMs * 0.1
            jitterMs = jitterMs * 0.9 + abs(dtMs - intervalAvgMs) * 0.1
            intervalMinMs = if (intervalMinMs.isNaN()) dtMs else min(intervalMinMs, dtMs)
            intervalMaxMs = max(intervalMaxMs * 0.999, dtMs)
            val step = abs(used - lastUsedValue) * 3.6
            if (step < 1e-4) repeats++
            else if (minStepKmh.isNaN() || step < minStepKmh) minStepKmh = step
        }
        lastUsedNanos = now
        lastUsedValue = used

        tBuf[head] = now; vBuf[head] = used
        head = (head + 1) % CAP
        if (count < CAP) count++
    }

    /** Least-squares slope over the recent window, then extrapolates the speed to now. */
    @Synchronized fun estimate(): Estimate {
        val now = SystemClock.elapsedRealtimeNanos()
        if (count == 0) return Estimate(0f, 0f, Long.MAX_VALUE, 0, false)
        val ageMs = (now - lastUsedNanos) / 1_000_000
        val lastV = lastUsedValue
        val limit = lastUsedNanos - (windowSec * 1e9).toLong()

        var n = 0; var st = 0.0; var sv = 0.0; var stt = 0.0; var stv = 0.0
        var i = (head - 1 + CAP) % CAP
        for (k in 0 until count) {
            val t = tBuf[i]
            if (t < limit && n >= 3) break
            val x = (t - lastUsedNanos) / 1e9
            val y = vBuf[i].toDouble()
            n++; st += x; sv += y; stt += x * x; stv += x * y
            if (n >= 24) break
            i = (i - 1 + CAP) % CAP
        }
        var slope = 0.0
        if (n >= 3) {
            val den = n * stt - st * st
            if (abs(den) > 1e-9) slope = (n * stv - st * sv) / den
        }
        val fresh = ageMs < staleMs
        val a = if (fresh) slope.coerceIn(-12.0, 12.0).toFloat() else 0f
        val ahead = min(ageMs, 400L) / 1000f
        val v = max(0f, lastV + (if (fresh) a * ahead else 0f))
        return Estimate(v, a, ageMs, n, fresh)
    }

    val hasData: Boolean get() = usable > 0
}
