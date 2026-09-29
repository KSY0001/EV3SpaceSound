package com.ev3.spacesound

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes CSV logs to the app's external files folder (Android/data/com.ev3.spacesound/files/logs). */
class DataLogger(private val context: Context) {
    private var writer: BufferedWriter? = null
    @Volatile var file: File? = null; private set
    @Volatile var rows = 0; private set
    private var lastFlush = 0L
    private var startMs = 0L

    val active: Boolean get() = writer != null

    fun logDir(): File = File(context.getExternalFilesDir(null), "logs").also { it.mkdirs() }

    @Synchronized fun start(): File {
        stop()
        val name = "drive_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val f = File(logDir(), name)
        val w = f.bufferedWriter()
        w.write(
            "t_ms,source,gps_kmh,gps_age_ms,car_kmh,car_age_ms,est_kmh,accel_fwd_ms2,accel_norm," +
            "calibrated,gps_hz,sensor_hz,car_hz,duck,tunnel,mode,audio_buffer_frames,underruns\n"
        )
        writer = w; file = f; rows = 0; startMs = System.currentTimeMillis(); lastFlush = startMs
        return f
    }

    @Synchronized fun row(line: String) {
        val w = writer ?: return
        val t = System.currentTimeMillis() - startMs
        w.write(t.toString()); w.write(","); w.write(line); w.write("\n")
        rows++
        val now = System.currentTimeMillis()
        if (now - lastFlush > 1000) { w.flush(); lastFlush = now }
    }

    @Synchronized fun stop() {
        writer?.let { runCatching { it.flush(); it.close() } }
        writer = null
    }

    /** Appends one latency-test result to latency_results.csv. */
    fun appendLatency(line: String) {
        val f = File(logDir(), "latency_results.csv")
        val header = !f.exists()
        f.appendText((if (header) "time,route,median_ms,min_ms,max_ms,ok_trials,total_trials,buffer_frames,timestamp_mode\n" else "") + line + "\n")
    }
}
