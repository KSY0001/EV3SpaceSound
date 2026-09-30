package com.ev3.spacesound

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

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
    @Volatile var carDataNote = "차 화면에서 앱 미실행"
    @Volatile var carPermNote = "-"
    @Volatile var carApiLevel = 0
    @Volatile var hostInfo = "-"
    /** Android Auto projection state from CarConnection, set by the phone UI. */
    @Volatile var projection = "확인 중"

    // car's own sensors (diagnostics)
    val carAccRate = RateCounter(3.0)
    val carGyroRate = RateCounter(3.0)
    @Volatile var carAccStatus = -1
    @Volatile var carGyroStatus = -1
    @Volatile var carAccText = "-"
    @Volatile var carGyroText = "-"

    // car session lifecycle
    @Volatile var sessionState = "없음"
    @Volatile var sessionSince = 0L
    private val sessionLog = ArrayDeque<String>()
    /** Hook set by the service so lifecycle changes also land in the event log. */
    @Volatile var eventSink: ((String, String) -> Unit)? = null

    fun sessionEvent(name: String) {
        sessionState = name
        sessionSince = SystemClock.elapsedRealtime()
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + name
        synchronized(sessionLog) {
            sessionLog.addLast(line)
            while (sessionLog.size > 8) sessionLog.removeFirst()
        }
        eventSink?.invoke("session", name)
    }

    fun sessionHistory(): String = synchronized(sessionLog) { sessionLog.joinToString(" → ") { it.substringAfter(' ') } }

    @Volatile var latencySummary = "측정 전"
    @Volatile var latencyRunning = false

    val carSpeedAgeMs: Long
        get() = if (carSpeedNanos == 0L) Long.MAX_VALUE
                else (SystemClock.elapsedRealtimeNanos() - carSpeedNanos) / 1_000_000
}
