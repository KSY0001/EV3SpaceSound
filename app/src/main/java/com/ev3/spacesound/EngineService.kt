package com.ev3.spacesound

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import com.ev3.spacesound.audio.SynthEngine
import com.ev3.spacesound.motion.MotionTracker

/** Foreground service that owns the synth, the motion tracker, the controller and the logger. */
class EngineService : Service() {

    lateinit var engine: SynthEngine; private set
    lateinit var motion: MotionTracker; private set
    lateinit var controller: DriveController; private set
    lateinit var logger: DataLogger; private set
    lateinit var latency: LatencyTester; private set
    @Volatile var fgsNote = ""; private set
    /** Ask for audio focus so the car switches its audio source to Android Auto (music is ducked, not stopped). */
    @Volatile var focusMode = true; private set
    @Volatile var focusNote = "없음"; private set
    private var focusRequest: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val am = getSystemService(AudioManager::class.java)
        val sr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
        engine = SynthEngine(sr, burst, PackPrefs.load(this)).also { it.bassDb = PackPrefs.loadBass(this).toFloat() }
        motion = MotionTracker(this)
        logger = DataLogger(this)
        controller = DriveController(engine, motion, logger)
        latency = LatencyTester(this, engine, logger)
        startInForeground()
        engine.start()
        motion.start()
        controller.start()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ev3spacesound:engine")
            .also { it.acquire(4 * 60 * 60 * 1000L) }
        CarSpeed.eventSink = { logger.speed(it) }
        AppState.eventSink = { t, d -> logger.event(t, d) }
        AppState.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        AppState.service = null
        CarSpeed.eventSink = null
        AppState.eventSink = null
        abandonFocus()
        controller.stop()
        motion.stop()
        logger.stop()
        engine.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun togglePower() = setPower(!engine.powered)

    fun setPower(on: Boolean) {
        if (on) {
            if (focusMode) requestFocus()
            engine.powerOn()
        } else {
            engine.powerOff()
            main.postDelayed({ if (!engine.powered) abandonFocus() }, 3500)
        }
    }

    fun setFocus(on: Boolean) {
        focusMode = on
        if (engine.powered) { if (on) requestFocus() else abandonFocus() }
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) { focusRequest = null; focusNote = "다른 앱이 가져감" }
            }
            .build()
        val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = if (granted) req else null
        focusNote = if (granted) "요청됨 (차 오디오 전환)" else "거부됨"
    }

    private fun abandonFocus() {
        val am = getSystemService(AudioManager::class.java)
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
        focusNote = "없음"
    }

    fun runLatencyTest(onProgress: (String) -> Unit) {
        if (AppState.latencyRunning) return
        Thread({ latency.measure(onProgress = onProgress) }, "latency-test").start()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "엔진 사운드", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("EV3 우주선 사운드 실행 중")
            .setContentText("안드로이드 오토 화면이나 이 알림에서 제어할 수 있어요")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "종료", stop).build())
            .setOngoing(true)
            .build()

        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (granted(android.Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        fgsNote = try {
            startForeground(NOTIFICATION_ID, n, types); ""
        } catch (e: Exception) {
            // e.g. started from the background: fall back to audio only
            runCatching { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            "위치/마이크 없이 시작됨. 폰 앱에서 서비스를 다시 시작해 주세요."
        }
    }

    companion object {
        private const val CHANNEL = "engine"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "com.ev3.spacesound.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EngineService::class.java).setAction(ACTION_STOP))
        }
    }
}
