package com.ev3.spacesound

import com.ev3.spacesound.audio.SynthEngine
import com.ev3.spacesound.motion.MotionTracker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

enum class Source(val label: String) { REAL("실제 주행 (GPS+센서)"), SIM("시뮬레이션") }
enum class CruiseMode(val label: String, val duck: Float) {
    KEEP("유지 55%", 0.55f), QUIET("거의 무음 15%", 0.15f), OFF("무음", 0f)
}
enum class TunnelMode(val label: String) { AUTO("자동"), ON("켜짐"), OFF("꺼짐") }

/**
 * Runs at 50 Hz: reads either the real motion estimate or the simulator, derives the sound
 * inputs (speed, acceleration, cruise/idle ducking, tunnel) and feeds them to the synth.
 */
class DriveController(
    private val engine: SynthEngine,
    private val motion: MotionTracker,
    private val logger: DataLogger,
) {
    @Volatile var source = Source.SIM
    @Volatile var cruiseMode = CruiseMode.KEEP
    @Volatile var tunnelMode = TunnelMode.AUTO
    @Volatile var lowBatteryManual = false
    /** Simulator pedal: -1 (full regen) .. 1 (full accelerator). */
    @Volatile var simPedal = 0f

    @Volatile var speedKmh = 0f; private set
    @Volatile var accelN = 0f; private set
    @Volatile var tunnelActive = false; private set
    @Volatile var modeLabel = "OFF"; private set
    @Volatile var demoStep: String? = null; private set

    private var simSpeed = 0f
    private var throttle = 0f
    private var cruiseTime = 0f
    private var idleTime = 0f
    @Volatile private var running = false
    private var thread: Thread? = null

    private data class Step(val dur: Float, val pedal: Float = 0f, val hold: Boolean = false, val tunnel: Boolean? = null, val label: String)
    private val steps = listOf(
        Step(2.5f, 0f, label = "신호 대기"),
        Step(3f, 0.35f, label = "부드러운 출발"),
        Step(4f, 1f, label = "급가속 · 워프"),
        Step(6f, hold = true, label = "고속 정속 · 크루즈"),
        Step(5f, hold = true, tunnel = true, label = "터널 진입"),
        Step(4.5f, -0.75f, tunnel = false, label = "회생제동 · 충전"),
        Step(3f, -0.3f, label = "감속"),
        Step(4f, 0f, label = "정차"),
    )
    @Volatile private var demoIndex = -1
    private var demoT = 0f
    private var demoTunnel = false

    fun startDemo() { source = Source.SIM; demoIndex = 0; demoT = 0f }
    fun stopDemo() { demoIndex = -1; demoStep = null; demoTunnel = false }
    val demoRunning: Boolean get() = demoIndex >= 0

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "drive-controller").also { it.start() }
    }

    fun stop() { running = false; thread?.join(300); thread = null }

    private fun loop() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            tick(dt)
            Thread.sleep(20)
        }
    }

    private fun tick(dt: Float) {
        var a: Float
        if (source == Source.SIM) {
            var target = simPedal
            var hold = false
            val di = demoIndex
            if (di >= 0) {
                val st = steps[di]
                demoStep = "데모 ${di + 1}/${steps.size} · ${st.label}"
                st.tunnel?.let { demoTunnel = it }
                if (st.hold) hold = true else target = st.pedal
                demoT += dt
                if (demoT > st.dur) {
                    demoT = 0f
                    if (di + 1 >= steps.size) stopDemo() else demoIndex = di + 1
                }
            }
            throttle += (target - throttle) * min(1f, dt * 6f)
            if (hold) a = 0f else {
                val drag = if (simSpeed > 0.5f) 0.15f + 0.00008f * simSpeed * simSpeed else 0f
                a = (if (throttle >= 0) throttle * 4f else throttle * 4.5f) - drag
                if (simSpeed <= 0f && a < 0f) a = 0f
            }
            simSpeed = (simSpeed + a * dt * 3.6f).coerceIn(0f, 180f)
            if (simSpeed >= 180f && a > 0f) a = 0f
            speedKmh = simSpeed
        } else {
            speedKmh = motion.estSpeedMs * 3.6f
            a = motion.accelFwd
            simSpeed = speedKmh
        }
        accelN += ((a / 3.6f).coerceIn(-1f, 1f) - accelN) * min(1f, dt * 8f)

        if (speedKmh > 50f && abs(accelN) < 0.12f) cruiseTime += dt else cruiseTime = 0f
        if (speedKmh < 1f) idleTime += dt else idleTime = 0f
        val duck = (if (cruiseTime > 4f) cruiseMode.duck else 1f) * (if (idleTime > 8f) 0.55f else 1f)

        tunnelActive = when (tunnelMode) {
            TunnelMode.ON -> true
            TunnelMode.OFF -> false
            TunnelMode.AUTO -> if (source == Source.SIM) demoTunnel
                               else motion.gpsAgeMs in 3000L..60000L && speedKmh > 20f
        }
        val battery = AppState.carBatteryPct
        val lowBattery = lowBatteryManual || (!battery.isNaN() && battery < 20f)

        engine.inSpeedKmh = speedKmh
        engine.inAccel = accelN
        engine.inDuck = duck
        engine.inTunnel = tunnelActive
        engine.inLowBattery = lowBattery

        modeLabel = when {
            !engine.powered -> "OFF"
            accelN < -0.08f -> "REGEN · 충전"
            accelN > 0.6f -> "WARP · 워프"
            accelN > 0.1f -> "BOOST · 가속"
            speedKmh < 1f -> "IDLE · 대기"
            cruiseTime > 4f -> "CRUISE · 정속"
            else -> "DRIVE · 주행"
        } + if (tunnelActive && engine.powered) " · 터널" else ""

        if (logger.active) {
            val gps = motion.gpsSpeedMs
            logger.row(
                String.format(
                    Locale.US, "%s,%.2f,%d,%.2f,%d,%.2f,%.3f,%.3f,%d,%.2f,%.1f,%.2f,%.2f,%d,%s,%d,%d",
                    source.name,
                    if (gps.isNaN()) -1f else gps * 3.6f, min(motion.gpsAgeMs, 999_999L),
                    if (AppState.carSpeedKmh.isNaN()) -1f else AppState.carSpeedKmh, min(AppState.carSpeedAgeMs, 999_999L),
                    speedKmh, a, accelN, if (motion.calibrated) 1 else 0,
                    motion.gpsRate.hz(), motion.sensorRate.hz(), AppState.carSpeedRate.hz(),
                    duck, if (tunnelActive) 1 else 0, modeLabel.substringBefore(" "),
                    engine.bufferFrames, engine.underruns,
                )
            )
        }
    }
}
