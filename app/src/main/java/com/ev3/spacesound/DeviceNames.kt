package com.ev3.spacesound

import android.media.AudioDeviceInfo

/** Human-readable names for audio output devices, for the diagnostics line. */
object DeviceNames {
    fun name(d: AudioDeviceInfo?): String {
        if (d == null) return "알 수 없음"
        val t = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "폰 스피커"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "통화용 수화부"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선 이어폰"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "블루투스 오디오"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스 통화"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 액세서리"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 오디오 장치"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 헤드셋"
            AudioDeviceInfo.TYPE_BUS -> "차량 오디오 버스"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "원격 믹스"
            AudioDeviceInfo.TYPE_TELEPHONY -> "전화망"
            else -> "기타 (유형 ${d.type})"
        }
        val product = d.productName?.toString()?.takeIf { it.isNotBlank() }
        return if (product != null) "$t · $product" else t
    }
}
