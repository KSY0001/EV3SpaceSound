package com.ev3.spacesound

import android.os.SystemClock
import java.util.ArrayDeque

/** Counts events and reports their rate over the last few seconds. */
class RateCounter(private val windowSec: Double = 5.0) {
    private val times = ArrayDeque<Long>()

    @Synchronized fun tick() {
        val now = SystemClock.elapsedRealtimeNanos()
        times.addLast(now)
        trim(now)
    }

    @Synchronized fun hz(): Double {
        trim(SystemClock.elapsedRealtimeNanos())
        return times.size / windowSec
    }

    private fun trim(now: Long) {
        val limit = now - (windowSec * 1e9).toLong()
        while (times.isNotEmpty() && times.peekFirst() < limit) times.removeFirst()
    }
}

/** Process-wide state shared by the phone UI, the car screen and the service. */
object AppState {
    @Volatile var service: EngineService? = null

    // Android Auto vehicle data (filled by the car session)
    @Volatile var carSpeedKmh = Float.NaN
    @Volatile var carSpeedNanos = 0L
    val carSpeedRate = RateCounter()
    @Volatile var carBatteryPct = Float.NaN
    @Volatile var carDataNote = "안드로이드 오토 미연결"

    @Volatile var latencySummary = "측정 전"
    @Volatile var latencyRunning = false

    val carSpeedAgeMs: Long
        get() = if (carSpeedNanos == 0L) Long.MAX_VALUE
                else (SystemClock.elapsedRealtimeNanos() - carSpeedNanos) / 1_000_000
}
