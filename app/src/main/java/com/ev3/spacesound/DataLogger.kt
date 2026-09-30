package com.ev3.spacesound

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes CSV logs to the app's external files folder (Android/data/com.ev3.spacesound/files/logs).
 *  - drive_*.csv  : one row every 20 ms with the controller's view of the world
 *  - events_*.csv : every Android Auto speed callback as received, plus session/input events
 */
class DataLogger(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var events: BufferedWriter? = null
    @Volatile var file: File? = null; private set
    @Volatile var eventFile: File? = null; private set
    @Volatile var rows = 0; private set
    @Volatile var eventRows = 0; private set
    private var lastFlush = 0L
    private var startMs = 0L

    val active: Boolean get() = writer != null

    fun logDir(): File = File(context.getExternalFilesDir(null), "logs").also { it.mkdirs() }

    @Synchronized fun start(): File {
        stop()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(logDir(), "drive_$stamp.csv")
        val w = f.bufferedWriter()
        w.write(
            "t_ms,source,active_input,gps_kmh,gps_age_ms,car_kmh_est,car_age_ms,car_raw_kmh,car_disp_kmh," +
            "used_kmh,car_accel_ms2,phone_accel_ms2,used_accel_ms2,accel_norm,accel_sound," +
            "phone_calibrated,yaw_rate,sensor_hz,car_hz,duck,tunnel,mode,hybrid_gate,audio_buffer_frames,underruns,session\n"
        )
        val ef = File(logDir(), "events_$stamp.csv")
        val ew = ef.bufferedWriter()
        ew.write("t_ms,type,raw_kmh,raw_status,disp_kmh,disp_status,used_field,car_ts_ms,delivery_ms,detail\n")
        writer = w; events = ew; file = f; eventFile = ef
        rows = 0; eventRows = 0
        startMs = System.currentTimeMillis(); lastFlush = startMs
        event("log", "start")
        return f
    }

    @Synchronized fun row(line: String) {
        val w = writer ?: return
        val t = System.currentTimeMillis() - startMs
        w.write(t.toString()); w.write(","); w.write(line); w.write("\n")
        rows++
        flushMaybe()
    }

    @Synchronized fun speed(r: CarSpeed.Raw) {
        val w = events ?: return
        val t = System.currentTimeMillis() - startMs
        w.write(String.format(Locale.US, "%d,speed,%.3f,%d,%.3f,%d,%s,%d,%d,\n",
            t, if (r.raw.isNaN()) -1f else r.raw * 3.6f, r.rawStatus,
            if (r.disp.isNaN()) -1f else r.disp * 3.6f, r.dispStatus, r.usedField, r.carTsMs, r.deliveryMs))
        eventRows++
        flushMaybe()
    }

    @Synchronized fun event(type: String, detail: String) {
        val w = events ?: return
        val t = System.currentTimeMillis() - startMs
        w.write("$t,$type,,,,,,,,${detail.replace(',', ' ')}\n")
        eventRows++
        flushMaybe()
    }

    private fun flushMaybe() {
        val now = System.currentTimeMillis()
        if (now - lastFlush > 1000) { writer?.flush(); events?.flush(); lastFlush = now }
    }

    @Synchronized fun stop() {
        if (events != null) event("log", "stop")
        writer?.let { runCatching { it.flush(); it.close() } }
        events?.let { runCatching { it.flush(); it.close() } }
        writer = null; events = null
    }

    /** Appends one latency-test result to latency_results.csv. */
    fun appendLatency(line: String) {
        val f = File(logDir(), "latency_results.csv")
        val header = !f.exists()
        f.appendText((if (header) "time,route,median_ms,min_ms,max_ms,ok_trials,total_trials,buffer_frames,timestamp_mode\n" else "") + line + "\n")
    }
}
