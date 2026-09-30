package com.ev3.spacesound

import com.ev3.spacesound.audio.SynthEngine
import com.ev3.spacesound.motion.MotionTracker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign

enum class Source(val label: String) {
    AUTO("자동 (차량 속도 우선)"),
    CAR("차량 속도만"),
    HYBRID("차량 속도 + 폰 센서"),
    PHONE("폰 GPS + 센서"),
    SIM("시뮬레이션"),
}
enum class CruiseMode(val label: String, val duck: Float) {
    KEEP("유지 55%", 0.55f), QUIET("거의 무음 15%", 0.15f), OFF("무음", 0f)
}
enum class TunnelMode(val label: String) { AUTO("자동"), ON("켜짐"), OFF("꺼짐") }

/**
 * Runs at 50 Hz: picks the input (Android Auto car speed, phone GPS+sensors or the simulator),
 * derives speed and acceleration, shapes the throttle response (spool-up) and feeds the synth.
 */
class DriveController(
    private val engine: SynthEngine,
    private val motion: MotionTracker,
    private val logger: DataLogger,
) {
    @Volatile var source = Source.AUTO
    @Volatile var cruiseMode = CruiseMode.KEEP
    @Volatile var tunnelMode = TunnelMode.AUTO
    @Volatile var lowBatteryManual = false
    /** Spool-up: the throttle response swells in over ~0.35 s, which hides output latency. */
    @Volatile var spool = true
    /** Simulator pedal: -1 (full regen) .. 1 (full accelerator). */
    @Volatile var simPedal = 0f

    // ---- outputs for UI / logging ----
    @Volatile var speedKmh = 0f; private set
    /** Raw acceleration from the active input (m/s²). */
    @Volatile var accelMs2 = 0f; private set
    /** Normalized, smoothed acceleration -1..1 (before spool shaping). */
    @Volatile var accelN = 0f; private set
    /** What the synth actually receives, after spool shaping. */
    @Volatile var accelSound = 0f; private set
    @Volatile var activeInput = "-"; private set
    @Volatile var inputNote = ""; private set
    @Volatile var carAccel = 0f; private set
    @Volatile var hybridGate = ""; private set
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

    private fun simulate(dt: Float): Float {
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
        var a: Float
        if (hold) a = 0f else {
            val drag = if (simSpeed > 0.5f) 0.15f + 0.00008f * simSpeed * simSpeed else 0f
            a = (if (throttle >= 0) throttle * 4f else throttle * 4.5f) - drag
            if (simSpeed <= 0f && a < 0f) a = 0f
        }
        simSpeed = (simSpeed + a * dt * 3.6f).coerceIn(0f, 180f)
        if (simSpeed >= 180f && a > 0f) a = 0f
        speedKmh = simSpeed
        return a
    }

    private fun usePhone(): Float {
        speedKmh = motion.estSpeedMs * 3.6f
        return motion.accelFwd
    }

    private fun tick(dt: Float) {
        val car = CarSpeed.estimate()
        carAccel = car.accel
        val carOk = car.fresh && car.samplesInWindow >= 3
        var a: Float
        hybridGate = ""

        when (source) {
            Source.SIM -> { a = simulate(dt); activeInput = "시뮬레이션"; inputNote = "" }
            Source.PHONE -> { a = usePhone(); activeInput = "폰"; inputNote = "" }
            Source.CAR -> {
                if (carOk) { speedKmh = car.speedMs * 3.6f; a = car.accel; inputNote = "" }
                else {
                    speedKmh = if (CarSpeed.hasData) car.speedMs * 3.6f else 0f; a = 0f
                    inputNote = if (!CarSpeed.hasData) "차량 속도 수신 없음" else "차량 속도 끊김 ${car.ageMs}ms"
                }
                activeInput = "차량"
            }
            Source.AUTO -> {
                if (carOk) { speedKmh = car.speedMs * 3.6f; a = car.accel; activeInput = "차량"; inputNote = "" }
                else {
                    a = usePhone(); activeInput = "폰(대체)"
                    inputNote = if (!CarSpeed.hasData) "차량 속도 수신 없음" else "차량 속도 끊김 ${car.ageMs}ms"
                }
            }
            Source.HYBRID -> {
                if (!carOk) {
                    a = usePhone(); activeInput = "폰(대체)"
                    inputNote = if (!CarSpeed.hasData) "차량 속도 수신 없음" else "차량 속도 끊김 ${car.ageMs}ms"
                } else {
                    speedKmh = car.speedMs * 3.6f
                    val pa = motion.accelFwd
                    val turning = abs(motion.yawRate) > 0.15f
                    val disagree = abs(car.accel) > 0.3f && sign(pa) != sign(car.accel)
                    a = when {
                        !motion.calibrated -> { hybridGate = "폰 미보정"; car.accel }
                        turning -> { hybridGate = "회전 중"; car.accel }
                        disagree -> { hybridGate = "방향 불일치"; car.accel }
                        else -> { hybridGate = "혼합"; 0.5f * car.accel + 0.5f * pa }
                    }
                    activeInput = "차량+폰"; inputNote = ""
                }
            }
        }
        if (source != Source.SIM) simSpeed = speedKmh
        accelMs2 = a
        accelN += ((a / 3.6f).coerceIn(-1f, 1f) - accelN) * min(1f, dt * 8f)

        // spool-up shaping: rising throttle swells in, everything else follows quickly
        val rising = abs(accelN) > abs(accelSound)
        val tc = if (!spool) 0.04f else if (rising) (if (accelN > 0) 0.35f else 0.22f) else 0.2f
        accelSound += (accelN - accelSound) * (1f - exp(-dt / tc))

        if (speedKmh > 50f && abs(accelSound) < 0.12f) cruiseTime += dt else cruiseTime = 0f
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
        engine.inAccel = accelSound
        engine.inDuck = duck
        engine.inTunnel = tunnelActive
        engine.inLowBattery = lowBattery
        engine.braamHoldSec = if (spool) 0.6f else 0f

        modeLabel = when {
            !engine.powered -> "OFF"
            accelSound < -0.08f -> "REGEN · 제동"
            accelSound > 0.6f -> "WARP · 워프"
            accelSound > 0.1f -> "BOOST · 가속"
            speedKmh < 1f -> "IDLE · 대기"
            cruiseTime > 4f -> "CRUISE · 정속"
            else -> "DRIVE · 주행"
        } + if (tunnelActive && engine.powered) " · 터널" else ""

        if (logger.active) {
            val gps = motion.gpsSpeedMs
            val lr = CarSpeed.lastRaw
            logger.row(
                String.format(
                    Locale.US,
                    "%s,%s,%.2f,%d,%.2f,%d,%.3f,%.3f,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.2f,%.1f,%.2f,%.2f,%d,%s,%s,%d,%d,%s",
                    source.name, activeInput,
                    if (gps.isNaN()) -1f else gps * 3.6f, min(motion.gpsAgeMs, 999_999L),
                    if (CarSpeed.hasData) car.speedMs * 3.6f else -1f, min(car.ageMs, 999_999L),
                    if (lr == null || lr.raw.isNaN()) -1f else lr.raw * 3.6f,
                    if (lr == null || lr.disp.isNaN()) -1f else lr.disp * 3.6f,
                    speedKmh, car.accel, motion.accelFwd, a, accelN, accelSound,
                    if (motion.calibrated) 1 else 0, motion.yawRate,
                    motion.sensorRate.hz(), CarSpeed.rate.hz(), duck,
                    if (tunnelActive) 1 else 0, modeLabel.substringBefore(" "), hybridGate.ifEmpty { "-" },
                    engine.bufferFrames, engine.underruns, AppState.sessionState,
                )
            )
        }
    }
}
