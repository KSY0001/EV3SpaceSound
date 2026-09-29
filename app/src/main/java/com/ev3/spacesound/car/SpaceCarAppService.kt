package com.ev3.spacesound.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.Speed
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ev3.spacesound.AppState

class SpaceCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = SpaceSession()
}

/** One Android Auto session: asks for vehicle data permissions and feeds speed/battery into AppState. */
class SpaceSession : Session() {
    private var speedListener: OnCarDataAvailableListener<Speed>? = null
    private var energyListener: OnCarDataAvailableListener<EnergyLevel>? = null

    override fun onCreateScreen(intent: Intent): Screen {
        AppState.carDataNote = "권한 확인 중"
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { unregister(); AppState.carDataNote = "안드로이드 오토 미연결" }
        })
        requestCarPermissions()
        return MainCarScreen(carContext)
    }

    private fun requestCarPermissions() {
        val wanted = listOf(
            "com.google.android.gms.permission.CAR_SPEED",
            "com.google.android.gms.permission.CAR_FUEL",
        )
        val missing = wanted.filter { carContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { register(); return }
        try {
            carContext.requestPermissions(missing) { _, _ -> register() }
        } catch (e: Exception) {
            AppState.carDataNote = "권한 요청 실패: ${e.javaClass.simpleName}"
        }
    }

    private fun register() {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) {
            AppState.carDataNote = "차량 데이터 API 미지원 (레벨 ${carContext.carAppApiLevel})"
            return
        }
        try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            val exec = ContextCompat.getMainExecutor(carContext)

            val sl = OnCarDataAvailableListener<Speed> { s ->
                val raw = s.rawSpeedMetersPerSecond
                val disp = s.displaySpeedMetersPerSecond
                val v = when {
                    raw.status == CarValue.STATUS_SUCCESS && raw.value != null -> raw.value
                    disp.status == CarValue.STATUS_SUCCESS && disp.value != null -> disp.value
                    else -> null
                }
                if (v != null) {
                    AppState.carSpeedKmh = v * 3.6f
                    AppState.carSpeedNanos = SystemClock.elapsedRealtimeNanos()
                    AppState.carSpeedRate.tick()
                    AppState.carDataNote = "수신 중"
                } else {
                    AppState.carDataNote = "속도 값 없음 (상태 ${raw.status})"
                }
            }
            info.addSpeedListener(exec, sl)
            speedListener = sl

            if (carContext.checkSelfPermission("com.google.android.gms.permission.CAR_FUEL") == PackageManager.PERMISSION_GRANTED) {
                val el = OnCarDataAvailableListener<EnergyLevel> { e ->
                    val b = e.batteryPercent
                    if (b.status == CarValue.STATUS_SUCCESS && b.value != null) AppState.carBatteryPct = b.value!!
                }
                info.addEnergyLevelListener(exec, el)
                energyListener = el
            }
            if (AppState.carDataNote == "권한 확인 중") AppState.carDataNote = "첫 값 기다리는 중"
        } catch (e: SecurityException) {
            AppState.carDataNote = "차량 속도 권한 거부됨"
        } catch (e: Exception) {
            AppState.carDataNote = "차량 데이터 오류: ${e.javaClass.simpleName}"
        }
    }

    private fun unregister() {
        runCatching {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            speedListener?.let { info.removeSpeedListener(it) }
            energyListener?.let { info.removeEnergyLevelListener(it) }
        }
        speedListener = null; energyListener = null
    }
}
