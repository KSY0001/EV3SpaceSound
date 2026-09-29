package com.ev3.spacesound

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/** Phone-side control panel: permissions, sound on/off, simulator, latency test, logging. */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var meters: TextView
    private lateinit var progressText: TextView
    private lateinit var btnService: Button
    private lateinit var btnPower: Button
    private lateinit var btnSource: Button
    private lateinit var btnDemo: Button
    private lateinit var btnCruise: Button
    private lateinit var btnPack: Button
    private lateinit var btnFocus: Button
    private lateinit var packInfo: TextView
    private lateinit var btnTunnel: Button
    private lateinit var btnBattery: Button
    private lateinit var btnLog: Button
    private lateinit var btnLatency: Button

    private val bg = Color.parseColor("#0A0E16")
    private val panel = Color.parseColor("#101725")
    private val fg = Color.parseColor("#DDE6F2")
    private val muted = Color.parseColor("#7C8AA3")
    private val ion = Color.parseColor("#8CCFFF")
    private val regen = Color.parseColor("#F0B25A")

    private val svc get() = AppState.service

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        setContentView(buildUi())
        requestNeededPermissions()
        carConnection = androidx.car.app.connection.CarConnection(this).also { it.type.observeForever(connObserver) }
    }

    private var carConnection: androidx.car.app.connection.CarConnection? = null
    private val connObserver = androidx.lifecycle.Observer<Int> { t ->
        AppState.projection = when (t) {
            androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION -> "안드로이드 오토 연결됨"
            androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NATIVE -> "차량 자체 OS"
            else -> "연결 안 됨"
        }
    }

    override fun onDestroy() {
        carConnection?.type?.removeObserver(connObserver)
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    // ---------- permissions ----------
    private val perms = arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun requestNeededPermissions() {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            toast("위치 권한이 없으면 실제 주행 모드를 쓸 수 없어요 (시뮬레이션은 가능)")
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            toast("마이크 권한이 없으면 지연 측정을 할 수 없어요")
    }

    // ---------- UI ----------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        root.addView(text("EV3 우주선 사운드", 24f, fg, bold = true))
        root.addView(text("엔진음·반응 지연 테스트 버전", 13f, muted))

        status = text("", 14f, fg, mono = true).also { it.background = card(); it.setPadding(dp(14), dp(12), dp(14), dp(12)) }
        root.addView(status, lp(top = 14))

        meters = text("", 13f, ion, mono = true)
        root.addView(meters, lp(top = 8))

        root.addView(section("서비스"))
        btnService = button("서비스 시작") {
            if (svc == null) { requestNeededPermissions(); EngineService.start(this) } else EngineService.stop(this)
        }
        btnPower = button("사운드 켜기") { svc?.togglePower() ?: needService() }
        root.addView(row(btnService, btnPower))

        root.addView(section("사운드 팩"))
        btnPack = button("") {
            svc?.engine?.let {
                val next = (it.packIndex + 1) % com.ev3.spacesound.audio.Packs.all.size
                it.selectPack(next); PackPrefs.save(this, next)
            } ?: needService()
        }
        root.addView(btnPack, lp(top = 8))
        packInfo = text("", 12f, muted)
        root.addView(packInfo, lp(top = 4))

        root.addView(section("입력"))
        btnSource = button("") {
            svc?.controller?.let {
                it.stopDemo()
                it.source = if (it.source == Source.SIM) Source.REAL else Source.SIM
            } ?: needService()
        }
        btnDemo = button("데모 주행") {
            svc?.controller?.let { if (it.demoRunning) it.stopDemo() else it.startDemo() } ?: needService()
        }
        root.addView(row(btnSource, btnDemo))

        val go = holdButton("가속 (누르고 있기)", 1f, ion)
        val brake = holdButton("회생제동 (누르고 있기)", -0.8f, regen)
        root.addView(row(go, brake))
        root.addView(text("가속·회생 버튼은 시뮬레이션 모드에서만 작동해요. 실제 주행 모드에서는 GPS와 가속도 센서 값을 씁니다.", 12f, muted), lp(top = 4))

        root.addView(section("사운드 설정"))
        btnCruise = button("") { svc?.controller?.let { it.cruiseMode = next(it.cruiseMode) } ?: needService() }
        btnTunnel = button("") { svc?.controller?.let { it.tunnelMode = next(it.tunnelMode) } ?: needService() }
        root.addView(row(btnCruise, btnTunnel))
        btnBattery = button("") { svc?.controller?.let { it.lowBatteryManual = !it.lowBatteryManual } ?: needService() }
        root.addView(btnBattery, lp(top = 8))
        btnFocus = button("") { svc?.let { it.setFocus(!it.focusMode) } ?: needService() }
        root.addView(btnFocus, lp(top = 8))
        root.addView(text("차에서 소리가 안 나면 켜두세요. 켜면 차가 안드로이드 오토 오디오로 전환되고, 재생 중인 음악은 멈추지 않고 작아집니다.", 12f, muted), lp(top = 4))

        root.addView(text("볼륨", 13f, muted), lp(top = 12))
        val vol = SeekBar(this).apply {
            max = 100; progress = 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { svc?.engine?.userVolume = p / 100f }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(vol)

        val bassLabel = text("저음 강도  +${PackPrefs.loadBass(this)} dB", 13f, muted)
        root.addView(bassLabel, lp(top = 12))
        val bass = SeekBar(this).apply {
            max = 15; progress = PackPrefs.loadBass(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    bassLabel.text = "저음 강도  +$p dB"
                    svc?.engine?.bassDb = p.toFloat()
                    if (fromUser) PackPrefs.saveBass(this@MainActivity, p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(bass)

        root.addView(section("반응 지연 측정"))
        root.addView(text("차에 연결한 상태에서, 주차 중에 조용히 측정하세요. 폰을 평소 거치 위치에 두고 음악은 끄세요. 짧은 '삑' 소리가 7번 납니다.", 12f, muted))
        btnLatency = button("지연 측정 시작") {
            val s = svc ?: return@button needService()
            s.runLatencyTest { msg -> ui.post { progressText.text = msg } }
        }
        root.addView(btnLatency, lp(top = 8))
        progressText = text("", 13f, fg)
        root.addView(progressText, lp(top = 6))

        root.addView(section("주행 기록"))
        root.addView(text("GPS·차량 속도·가속도의 갱신 주기와 값을 CSV로 저장해요. 실제 주행 모드에서 기록하면 센서 반응을 비교할 수 있어요.", 12f, muted))
        btnLog = button("기록 시작") {
            val s = svc ?: return@button needService()
            if (s.logger.active) { s.logger.stop(); toast("저장됨: ${s.logger.file?.name}") } else s.logger.start()
        }
        val share = button("최근 기록 공유") { shareLatest() }
        val recal = button("센서 방향 다시 학습") { svc?.motion?.resetCalibration() ?: needService() }
        root.addView(row(btnLog, share))
        root.addView(recal, lp(top = 8))

        return ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
    }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 200)
        }
    }

    private fun render() {
        val s = svc
        btnService.text = if (s == null) "서비스 시작" else "서비스 종료"
        if (s == null) {
            status.text = "서비스가 꺼져 있어요.\n'서비스 시작'을 누르면 엔진음 준비가 됩니다."
            meters.text = ""
            listOf(btnPower, btnPack, btnSource, btnDemo, btnCruise, btnTunnel, btnBattery, btnFocus, btnLog, btnLatency).forEach { it.alpha = 0.4f }
            btnPack.text = "팩: " + com.ev3.spacesound.audio.Packs.all[PackPrefs.load(this)].name; packInfo.text = ""
            btnSource.text = "입력: -"; btnCruise.text = "정속: -"; btnTunnel.text = "터널: -"; btnBattery.text = "배터리 부족 연출: -"; btnFocus.text = "차 오디오 전환: -"
            return
        }
        listOf(btnPower, btnPack, btnSource, btnDemo, btnCruise, btnTunnel, btnBattery, btnFocus, btnLog, btnLatency).forEach { it.alpha = 1f }
        val c = s.controller; val e = s.engine; val m = s.motion
        btnPower.text = if (e.powered) "사운드 끄기" else "사운드 켜기"
        btnSource.text = "입력: ${c.source.label}"
        btnDemo.text = if (c.demoRunning) "데모 중지" else "데모 주행"
        btnCruise.text = "정속: ${c.cruiseMode.label}"
        btnPack.text = "팩: ${e.pack.name}  (눌러서 다음 팩)"
        btnFocus.text = "차 오디오 전환: " + if (s.focusMode) "켜짐" else "꺼짐 (음악과 섞기)"
        packInfo.text = e.pack.desc
        btnTunnel.text = "터널: ${c.tunnelMode.label}"
        btnBattery.text = "배터리 부족 연출: " + if (c.lowBatteryManual) "켜짐" else "자동(차량 20% 미만)"
        btnLog.text = if (s.logger.active) "기록 중지 (${s.logger.rows}행)" else "기록 시작"
        btnLatency.isEnabled = !AppState.latencyRunning

        val gps = m.gpsSpeedMs
        val car = AppState.carSpeedKmh
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%-6s %s\n", "AA", AppState.projection))
        sb.append(String.format(Locale.US, "%-6s %s\n", "모드", c.modeLabel))
        sb.append(String.format(Locale.US, "%-6s %s\n", "오디오", s.focusNote))
        c.demoStep?.let { sb.append(String.format(Locale.US, "%-6s %s\n", "데모", it)) }
        sb.append(String.format(Locale.US, "%-6s %.0f km/h  (가속 %+.2f)\n", "속도", c.speedKmh, c.accelN))
        sb.append(String.format(Locale.US, "%-6s %s · 갱신 %.1fHz · %s\n", "GPS",
            if (gps.isNaN()) "-" else String.format(Locale.US, "%.0f km/h", gps * 3.6f), m.gpsRate.hz(), ageText(m.gpsAgeMs)))
        sb.append(String.format(Locale.US, "%-6s %s · 갱신 %.1fHz · %s\n", "차량",
            if (car.isNaN()) "-" else String.format(Locale.US, "%.0f km/h", car), AppState.carSpeedRate.hz(), AppState.carDataNote))
        sb.append(String.format(Locale.US, "%-6s %.0fHz · %s\n", "가속센서", m.sensorRate.hz(), m.status))
        if (!AppState.carBatteryPct.isNaN()) sb.append(String.format(Locale.US, "%-6s %.0f%%\n", "배터리", AppState.carBatteryPct))
        sb.append(String.format(Locale.US, "%-6s %d프레임 (%.1f ms) · 언더런 %d\n", "버퍼",
            e.bufferFrames, e.bufferFrames * 1000.0 / e.sampleRate, e.underruns))
        sb.append(String.format(Locale.US, "%-6s %s", "지연", AppState.latencySummary))
        if (s.fgsNote.isNotEmpty()) sb.append("\n⚠ ").append(s.fgsNote)
        status.text = sb.toString()

        val mm = e.meters
        meters.text = "드론 ${bar(mm[0])}\n톤   ${bar(mm[1])}\n에너지 ${bar(mm[2])}\n패드 ${bar(mm[3])}\n라이저 ${bar(mm[4])}\n충전 ${bar(mm[5])}"
    }

    private fun ageText(ms: Long) = if (ms == Long.MAX_VALUE) "수신 없음" else String.format(Locale.US, "%.1fs 전", ms / 1000.0)
    private fun bar(v: Float): String { val n = (v.coerceIn(0f, 1f) * 20).toInt(); return "█".repeat(n) + "·".repeat(20 - n) }

    private fun shareLatest() {
        val s = svc
        val dir = s?.logger?.logDir() ?: File(getExternalFilesDir(null), "logs")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) { toast("저장된 기록이 없어요"); return }
        val uris = ArrayList(files.take(2).map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "기록 공유"))
    }

    private fun needService() = toast("먼저 '서비스 시작'을 눌러주세요")
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun next(m: CruiseMode) = CruiseMode.entries[(m.ordinal + 1) % CruiseMode.entries.size]
    private fun next(m: TunnelMode) = TunnelMode.entries[(m.ordinal + 1) % TunnelMode.entries.size]

    // ---------- small view helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
    }

    private fun section(title: String) = text(title, 13f, muted, bold = true).also {
        it.letterSpacing = 0.08f
        it.layoutParams = lp(top = 22)
    }

    private fun card() = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat() }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(fg); textSize = 14f
        background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#1F2B40")) }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { onClick() }
    }

    private fun holdButton(label: String, pedal: Float, color: Int) = button(label) {}.apply {
        setOnTouchListener { v, ev ->
            val c = svc?.controller
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (c == null) { needService() } else if (c.source != Source.SIM) {
                        toast("시뮬레이션 모드로 바꿔주세요")
                    } else { c.stopDemo(); c.simPedal = pedal; (v as Button).setTextColor(color) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    c?.simPedal = 0f; (v as Button).setTextColor(fg)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(4) })
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(4) })
        layoutParams = lp(top = 8)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = dp(top) }
}
