package com.ev3.spacesound.motion

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ev3.spacesound.RateCounter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Estimates vehicle speed and forward acceleration from the phone:
 *  - GPS speed (about 1 Hz) is the absolute reference.
 *  - The linear-acceleration sensor (about 50 Hz) gives fast response.
 * The phone's mounting direction is learned automatically: gravity gives "down", and the
 * horizontal acceleration seen while GPS speed clearly changes gives "forward".
 * The phone must stay fixed in a mount for this to work.
 */
class MotionTracker(private val context: Context) : SensorEventListener, LocationListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val lm = context.getSystemService(LocationManager::class.java)
    private var thread: HandlerThread? = null

    val gpsRate = RateCounter()
    val sensorRate = RateCounter()

    // all fields below are touched only on the motion thread, except the @Volatile outputs
    private val g = floatArrayOf(0f, 0f, 1f)
    private val hLp = FloatArray(3)
    private val fwd = FloatArray(3)
    private val fwdAcc = DoubleArray(3)
    private val sumH = DoubleArray(3)
    private var nH = 0
    private var lastGpsSpeed = Float.NaN
    private var lastGpsNanos = 0L
    private var lastSensorNanos = 0L

    @Volatile var calibCount = 0; private set
    val calibrated: Boolean get() = calibCount >= 3
    @Volatile var gpsSpeedMs = Float.NaN; private set
    @Volatile var gpsAccel = 0f; private set
    @Volatile var lastFixNanos = 0L; private set
    /** Fused speed estimate in m/s. */
    @Volatile var estSpeedMs = 0f; private set
    /** Forward acceleration in m/s² (positive = speeding up). */
    @Volatile var accelFwd = 0f; private set
    @Volatile var status = "대기"; private set

    val gpsAgeMs: Long
        get() = if (lastFixNanos == 0L) Long.MAX_VALUE
                else (SystemClock.elapsedRealtimeNanos() - lastFixNanos) / 1_000_000

    @SuppressLint("MissingPermission")
    fun start() {
        if (thread != null) return
        val t = HandlerThread("motion").also { it.start() }
        thread = t
        val h = Handler(t.looper)

        val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (grav != null) sm.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME, h)
        if (lin != null) sm.registerListener(this, lin, SensorManager.SENSOR_DELAY_GAME, h)

        val hasLoc = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        status = when {
            lin == null -> "선형 가속도 센서 없음"
            !hasLoc -> "위치 권한 없음"
            else -> "GPS 신호 기다리는 중"
        }
        if (hasLoc) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, t.looper)
    }

    fun stop() {
        sm.unregisterListener(this)
        runCatching { lm.removeUpdates(this) }
        thread?.quitSafely()
        thread = null
    }

    fun resetCalibration() {
        thread?.let { Handler(it.looper).post { fwdAcc.fill(0.0); fwd.fill(0f); calibCount = 0 } }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                val n = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                if (n > 1f) for (i in 0..2) g[i] = e.values[i] / n
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                sensorRate.tick()
                val ax = e.values[0]; val ay = e.values[1]; val az = e.values[2]
                val d = ax * g[0] + ay * g[1] + az * g[2]
                val hx = ax - d * g[0]; val hy = ay - d * g[1]; val hz = az - d * g[2]
                hLp[0] += (hx - hLp[0]) * 0.3f
                hLp[1] += (hy - hLp[1]) * 0.3f
                hLp[2] += (hz - hLp[2]) * 0.3f
                sumH[0] += hx; sumH[1] += hy; sumH[2] += hz; nH++

                val dt = if (lastSensorNanos == 0L) 0f
                         else ((e.timestamp - lastSensorNanos) / 1e9f).coerceIn(0f, 0.1f)
                lastSensorNanos = e.timestamp

                if (calibrated) {
                    val a = hLp[0] * fwd[0] + hLp[1] * fwd[1] + hLp[2] * fwd[2]
                    accelFwd = a
                    estSpeedMs = max(0f, estSpeedMs + a * dt)
                } else {
                    accelFwd = gpsAccel
                    if (!gpsSpeedMs.isNaN()) estSpeedMs = gpsSpeedMs
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        gpsRate.tick()
        if (!loc.hasSpeed()) return
        val v = loc.speed
        val t = loc.elapsedRealtimeNanos
        if (lastGpsNanos != 0L) {
            val dt = (t - lastGpsNanos) / 1e9f
            if (dt in 0.2f..3f) {
                val ga = (v - lastGpsSpeed) / dt
                gpsAccel = ga
                if (abs(ga) > 0.7f && nH >= 5) learnForward(ga)
            }
        }
        sumH.fill(0.0); nH = 0
        lastGpsSpeed = v; lastGpsNanos = t; lastFixNanos = t; gpsSpeedMs = v

        estSpeedMs = if (!calibrated || v < 0.5f) v else estSpeedMs + 0.6f * (v - estSpeedMs)
        status = if (calibrated) "보정 완료 (센서 방향 학습됨)" else "방향 학습 중 ${calibCount}/3 · 직진 가속·감속을 몇 번 해주세요"
    }

    private fun learnForward(gpsAccel: Float) {
        val ax = sumH[0] / nH; val ay = sumH[1] / nH; val az = sumH[2] / nH
        val mag = sqrt(ax * ax + ay * ay + az * az)
        if (mag < 0.25) return
        val s = sign(gpsAccel).toDouble()
        fwdAcc[0] += s * ax / mag; fwdAcc[1] += s * ay / mag; fwdAcc[2] += s * az / mag
        // keep the learned direction horizontal
        val d = fwdAcc[0] * g[0] + fwdAcc[1] * g[1] + fwdAcc[2] * g[2]
        val fx = fwdAcc[0] - d * g[0]; val fy = fwdAcc[1] - d * g[1]; val fz = fwdAcc[2] - d * g[2]
        val n = sqrt(fx * fx + fy * fy + fz * fz)
        if (n < 1e-6) return
        fwd[0] = (fx / n).toFloat(); fwd[1] = (fy / n).toFloat(); fwd[2] = (fz / n).toFloat()
        calibCount++
    }
}
