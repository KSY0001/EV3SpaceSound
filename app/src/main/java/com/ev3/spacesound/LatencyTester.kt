package com.ev3.spacesound

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import com.ev3.spacesound.audio.SynthEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures the real sound path delay: the synth mixes a short 2 kHz click into its own output
 * stream, and the phone microphone listens for it coming back out of the car speakers.
 * Result = time from "click handed to AudioTrack" to "click heard by the mic".
 * This covers the app buffer, Android Auto transport, the car's audio system and the air gap.
 */
class LatencyTester(
    private val context: Context,
    private val engine: SynthEngine,
    private val logger: DataLogger,
) {
    data class Result(val medianMs: Double, val minMs: Double, val maxMs: Double, val ok: Int, val total: Int, val note: String)

    @SuppressLint("MissingPermission")
    fun measure(trials: Int = 7, onProgress: (String) -> Unit): Result? {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onProgress("마이크 권한이 없어요. 폰 앱에서 권한을 허용해 주세요."); return null
        }
        val sr = engine.sampleRate
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = createRecorder(sr, max(minBuf, sr / 5 * 4)) ?: run {
            onProgress("마이크를 열 수 없어요."); return null
        }
        val chunk = FloatArray(sr / 100) // 10 ms reads
        val ts = AudioTimestamp()
        var framesRead = 0L
        var usedFallback = false

        /** Monotonic time (ns) of the frame with the given absolute index. */
        fun frameTime(frameIndex: Long, readReturnNanos: Long, framesAfterRead: Long): Long {
            val ok = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            return if (ok) ts.nanoTime + ((frameIndex - ts.framePosition) * 1e9 / sr).toLong()
            else { usedFallback = true; readReturnNanos - ((framesAfterRead - frameIndex) * 1e9 / sr).toLong() }
        }

        fun readChunk(): Int {
            val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n > 0) framesRead += n
            return n
        }

        val results = ArrayList<Double>()
        try {
            AppState.latencyRunning = true
            engine.testMute = true
            rec.startRecording()
            onProgress("준비 중… 차 안을 조용히 해주세요")
            // let the engine fade out and measure the room noise
            var sumSq = 0.0; var count = 0
            val quietUntil = System.nanoTime() + 700_000_000L
            while (System.nanoTime() < quietUntil) {
                val n = readChunk()
                if (System.nanoTime() > quietUntil - 300_000_000L) for (i in 0 until max(n, 0)) { sumSq += chunk[i] * chunk[i]; count++ }
            }
            val noiseRms = if (count > 0) sqrt(sumSq / count) else 0.0
            val threshold = max(0.02, noiseRms * 8)

            for (trial in 1..trials) {
                onProgress("측정 중 $trial/$trials")
                engine.requestClick()
                var writeNanos = 0L
                var detected = -1.0
                val deadline = System.nanoTime() + 1_500_000_000L
                while (System.nanoTime() < deadline && detected < 0) {
                    val n = readChunk()
                    val readNanos = System.nanoTime()
                    if (writeNanos == 0L) writeNanos = engine.clickWrittenNanos
                    if (n <= 0 || writeNanos == 0L) continue
                    val firstIndex = framesRead - n
                    for (i in 0 until n) {
                        if (abs(chunk[i]) > threshold) {
                            val t = frameTime(firstIndex + i, readNanos, framesRead)
                            if (t > writeNanos) { detected = (t - writeNanos) / 1e6; break }
                        }
                    }
                }
                if (detected > 0) results.add(detected)
                // let echoes die out before the next click
                val gapUntil = System.nanoTime() + 600_000_000L
                while (System.nanoTime() < gapUntil) readChunk()
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            engine.testMute = false
            AppState.latencyRunning = false
        }

        val route = outputRoute()
        if (results.isEmpty()) {
            val msg = "클릭음을 감지하지 못했어요 ($route). 볼륨을 올리고 폰을 스피커 쪽에 두고 다시 해보세요."
            AppState.latencySummary = msg; onProgress(msg); return null
        }
        results.sort()
        val median = results[results.size / 2]
        val note = if (usedFallback) "타임스탬프 대체 계산" else "하드웨어 타임스탬프"
        val r = Result(median, results.first(), results.last(), results.size, trials, note)
        val summary = String.format(Locale.US, "%.0f ms (최소 %.0f · 최대 %.0f, %d/%d회) · %s",
            median, r.minMs, r.maxMs, r.ok, r.total, route)
        AppState.latencySummary = summary
        onProgress("완료: $summary")
        logger.appendLatency(
            String.format(Locale.US, "%s,%s,%.1f,%.1f,%.1f,%d,%d,%d,%s",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                route.replace(",", " "), median, r.minMs, r.maxMs, r.ok, r.total, engine.bufferFrames, note)
        )
        return r
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(sr: Int, bytes: Int): AudioRecord? {
        for (src in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                    )
                    .setBufferSizeInBytes(bytes)
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    // Prefer the phone's own microphone so we hear the car speakers from the cabin.
                    val am = context.getSystemService(AudioManager::class.java)
                    am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        ?.let { r.setPreferredDevice(it) }
                    return r
                }
                r.release()
            } catch (_: Exception) { }
        }
        return null
    }

    private fun outputRoute(): String {
        val am = context.getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val names = outs.mapNotNull {
            when (it.type) {
                AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "블루투스"
                AudioDeviceInfo.TYPE_BUS -> "차량 버스"
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "원격 믹스(무선 AA 가능성)"
                else -> null
            }
        }.distinct()
        return if (names.isEmpty()) "폰 스피커/기타" else names.joinToString("+")
    }
}
