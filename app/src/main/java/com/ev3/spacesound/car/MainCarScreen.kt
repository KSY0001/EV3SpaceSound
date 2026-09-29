package com.ev3.spacesound.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ev3.spacesound.AppState
import com.ev3.spacesound.PackPrefs
import com.ev3.spacesound.audio.Packs
import com.ev3.spacesound.EngineService
import java.util.Locale

/** The car screen: live status plus sound on/off and latency test. Refreshes once per second. */
class MainCarScreen(carContext: CarContext) : Screen(carContext) {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 1000) }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { handler.post(tick) }
            override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
        })
    }

    override fun onGetTemplate(): Template {
        val svc = AppState.service
        val pane = Pane.Builder()

        if (svc == null) {
            pane.addRow(Row.Builder().setTitle("서비스가 꺼져 있어요").addText("폰에서 앱을 열고 '서비스 시작'을 눌러주세요").build())
            pane.addAction(
                Action.Builder().setTitle("여기서 시작").setOnClickListener {
                    try { EngineService.start(carContext) } catch (e: Exception) {
                        CarToast.makeText(carContext, "폰에서 시작해 주세요", CarToast.LENGTH_LONG).show()
                    }
                    invalidate()
                }.build()
            )
        } else {
            val c = svc.controller; val e = svc.engine; val m = svc.motion
            val gps = m.gpsSpeedMs; val car = AppState.carSpeedKmh
            pane.addRow(
                Row.Builder().setTitle("${e.pack.name} · ${c.modeLabel}")
                    .addText("정속 ${c.cruiseMode.label} · 터널 ${c.tunnelMode.label} · 입력 ${c.source.label}")
                    .build()
            )
            pane.addRow(
                Row.Builder().setTitle(String.format(Locale.US, "속도 %.0f km/h", c.speedKmh))
                    .addText(
                        "GPS " + (if (gps.isNaN()) "-" else String.format(Locale.US, "%.0f", gps * 3.6f)) +
                        " · 차량 " + (if (car.isNaN()) "-" else String.format(Locale.US, "%.0f", car)) + " km/h"
                    ).build()
            )
            pane.addRow(
                Row.Builder().setTitle("갱신 주기")
                    .addText(String.format(Locale.US, "GPS %.1fHz · 차량 %.1fHz · 가속센서 %.0fHz",
                        m.gpsRate.hz(), AppState.carSpeedRate.hz(), m.sensorRate.hz()))
                    .build()
            )
            pane.addRow(
                Row.Builder().setTitle(if (AppState.latencyRunning) "지연 측정 중…" else "지연")
                    .addText(AppState.latencySummary)
                    .build()
            )
            pane.addAction(
                Action.Builder().setTitle(if (e.powered) "사운드 끄기" else "사운드 켜기")
                    .setOnClickListener { svc.togglePower(); invalidate() }
                    .build()
            )
            pane.addAction(
                Action.Builder().setTitle("지연 측정")
                    .setOnClickListener {
                        CarToast.makeText(carContext, "주차 중에 조용히 측정하세요", CarToast.LENGTH_SHORT).show()
                        svc.runLatencyTest { }
                        invalidate()
                    }.build()
            )
        }

        val strip = ActionStrip.Builder().addAction(
            Action.Builder().setTitle("사운드 팩").setOnClickListener {
                AppState.service?.engine?.let {
                    val next = (it.packIndex + 1) % Packs.all.size
                    it.selectPack(next)
                    PackPrefs.save(carContext, next)
                    CarToast.makeText(carContext, Packs.all[next].name, CarToast.LENGTH_SHORT).show()
                }
                invalidate()
            }.build()
        ).build()

        return PaneTemplate.Builder(pane.build())
            .setTitle("EV3 우주선 사운드")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(strip)
            .build()
    }
}
