package com.ev3.spacesound.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Speed
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ev3.spacesound.AppState
import com.ev3.spacesound.CarSpeed
import java.util.Locale

class SpaceCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = SpaceSession()
}

/**
 * One Android Auto session. Registers for vehicle speed (main input), battery and — for diagnostics —
 * the car's own accelerometer and gyroscope, and records the session lifecycle so we can see whether
 * data keeps flowing while another app (e.g. a navigation app) is on the car screen.
 */
class SpaceSession : Session() {
    private var speedListener: OnCarDataAvailableListener<Speed>? = null
    private var energyListener: OnCarDataAvailableListener<EnergyLevel>? = null
    private var accListener: OnCarDataAvailableListener<Accelerometer>? = null
    private var gyroListener: OnCarDataAvailableListener<Gyroscope>? = null

    override fun onCreateScreen(intent: Intent): Screen {
        AppState.carDataNote = "권한 확인 중"
        AppState.carApiLevel = carContext.carAppApiLevel
        AppState.hostInfo = runCatching { carContext.hostInfo?.let { "${it.packageName}" } }.getOrNull() ?: "-"
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = AppState.sessionEvent("CREATE")
            override fun onStart(owner: LifecycleOwner) = AppState.sessionEvent("START")
            override fun onResume(owner: LifecycleOwner) = AppState.sessionEvent("RESUME")
            override fun onPause(owner: LifecycleOwner) = AppState.sessionEvent("PAUSE")
            override fun onStop(owner: LifecycleOwner) = AppState.sessionEvent("STOP")
            override fun onDestroy(owner: LifecycleOwner) {
                AppState.sessionEvent("DESTROY")
                unregister()
                AppState.carDataNote = "차 화면에서 앱 미실행"
            }
        })
        AppState.sessionEvent("SCREEN")
        requestCarPermissions()
        return MainCarScreen(carContext)
    }

    private fun requestCarPermissions() {
        val wanted = listOf(
            "com.google.android.gms.permission.CAR_SPEED",
            "com.google.android.gms.permission.CAR_FUEL",
        )
        val missing = wanted.filter { carContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        AppState.carPermNote = if (missing.isEmpty()) "허용됨" else "요청 중: " + missing.joinToString { it.substringAfterLast('.') }
        if (missing.isEmpty()) { register(); return }
        try {
            carContext.requestPermissions(missing) { granted, rejected ->
                AppState.carPermNote = "허용 ${granted.map { it.substringAfterLast('.') }} · 거부 ${rejected.map { it.substringAfterLast('.') }}"
                register()
            }
        } catch (e: Exception) {
            AppState.carDataNote = "권한 요청 실패: ${e.javaClass.simpleName}"
        }
    }

    private fun register() {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) {
            AppState.carDataNote = "차량 데이터 API 미지원 (레벨 ${carContext.carAppApiLevel})"
            return
        }
        val hw = try { carContext.getCarService(CarHardwareManager::class.java) } catch (e: Exception) {
            AppState.carDataNote = "하드웨어 매니저 없음: ${e.javaClass.simpleName}"; return
        }
        val exec = ContextCompat.getMainExecutor(carContext)

        // ---- speed (main input) ----
        try {
            val sl = OnCarDataAvailableListener<Speed> { s ->
                val raw = s.rawSpeedMetersPerSecond
                val disp = s.displaySpeedMetersPerSecond
                val ts = if (raw.status == CarValue.STATUS_SUCCESS) raw.timestampMillis else disp.timestampMillis
                CarSpeed.onSample(raw.value, raw.status, disp.value, disp.status, CarValue.STATUS_SUCCESS, ts)
                val last = CarSpeed.lastRaw
                AppState.carDataNote = if (last != null && !last.used.isNaN()) "수신 중 (${last.usedField})"
                                       else "속도 값 없음 (raw 상태 ${raw.status}, display 상태 ${disp.status})"
                if (last != null && !last.used.isNaN()) {
                    AppState.carSpeedKmh = last.used * 3.6f
                    AppState.carSpeedNanos = last.tNanos
                    AppState.carSpeedRate.tick()
                }
            }
            hw.carInfo.addSpeedListener(exec, sl)
            speedListener = sl
            if (AppState.carDataNote == "권한 확인 중") AppState.carDataNote = "첫 값 기다리는 중"
        } catch (e: SecurityException) {
            AppState.carDataNote = "차량 속도 권한 거부됨"
        } catch (e: Exception) {
            AppState.carDataNote = "속도 등록 오류: ${e.javaClass.simpleName}"
        }

        // ---- battery ----
        if (carContext.checkSelfPermission("com.google.android.gms.permission.CAR_FUEL") == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val el = OnCarDataAvailableListener<EnergyLevel> { e ->
                    val b = e.batteryPercent
                    if (b.status == CarValue.STATUS_SUCCESS && b.value != null) AppState.carBatteryPct = b.value!!
                }
                hw.carInfo.addEnergyLevelListener(exec, el)
                energyListener = el
            }
        }

        // ---- car accelerometer / gyroscope (diagnostics only) ----
        try {
            val al = OnCarDataAvailableListener<Accelerometer> { a ->
                val f = a.forces
                AppState.carAccStatus = f.status
                if (f.status == CarValue.STATUS_SUCCESS && f.value != null) {
                    AppState.carAccRate.tick()
                    AppState.carAccText = f.value!!.joinToString(" ") { String.format(Locale.US, "%+.2f", it) }
                }
            }
            hw.carSensors.addAccelerometerListener(CarSensors.UPDATE_RATE_FASTEST, exec, al)
            accListener = al
        } catch (e: Exception) { AppState.carAccText = "등록 실패: ${e.javaClass.simpleName}" }
        try {
            val gl = OnCarDataAvailableListener<Gyroscope> { g ->
                val r = g.rotations
                AppState.carGyroStatus = r.status
                if (r.status == CarValue.STATUS_SUCCESS && r.value != null) {
                    AppState.carGyroRate.tick()
                    AppState.carGyroText = r.value!!.joinToString(" ") { String.format(Locale.US, "%+.2f", it) }
                }
            }
            hw.carSensors.addGyroscopeListener(CarSensors.UPDATE_RATE_FASTEST, exec, gl)
            gyroListener = gl
        } catch (e: Exception) { AppState.carGyroText = "등록 실패: ${e.javaClass.simpleName}" }
    }

    private fun unregister() {
        runCatching {
            val hw = carContext.getCarService(CarHardwareManager::class.java)
            speedListener?.let { hw.carInfo.removeSpeedListener(it) }
            energyListener?.let { hw.carInfo.removeEnergyLevelListener(it) }
            accListener?.let { hw.carSensors.removeAccelerometerListener(it) }
            gyroListener?.let { hw.carSensors.removeGyroscopeListener(it) }
        }
        speedListener = null; energyListener = null; accListener = null; gyroListener = null
    }
}
