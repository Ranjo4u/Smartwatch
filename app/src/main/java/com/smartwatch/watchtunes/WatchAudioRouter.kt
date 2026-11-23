package com.smartwatch.watchtunes

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Sends audio to the watch over the Bluetooth *call* channel (HFP/SCO).
 *
 * Calling smartwatches like the Titan Heritage usually expose only the hands-free
 * (call) profile, not the music (A2DP) profile. We put the phone in
 * "communication" mode and pick the watch as the communication device, so any
 * audio tagged as voice-communication plays on the watch speaker.
 */
class WatchAudioRouter(context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)

    var active = false
        private set

    /** The watch's call-audio device, or null when no calling watch/headset is connected. */
    fun findWatch(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }

    fun watchName(): String? = findWatch()?.productName?.toString()?.takeIf { it.isNotBlank() }

    /** Opens the call channel to the watch. Returns false if no watch is connected. */
    fun start(): Boolean {
        if (active) return true
        val watch = findWatch() ?: return false
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.setCommunicationDevice(watch)
        } else {
            startScoLegacy()
            true
        }
        if (!ok) am.mode = AudioManager.MODE_NORMAL
        active = ok
        return ok
    }

    /** Closes the call channel so the watch is free again. */
    fun stop() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            stopScoLegacy()
        }
        am.mode = AudioManager.MODE_NORMAL
        active = false
    }

    /** Watch volume = the phone's call volume while routing. */
    fun adjustVolume(up: Boolean) {
        am.adjustStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    @Suppress("DEPRECATION")
    private fun startScoLegacy() {
        am.startBluetoothSco()
        am.isBluetoothScoOn = true
    }

    @Suppress("DEPRECATION")
    private fun stopScoLegacy() {
        am.isBluetoothScoOn = false
        am.stopBluetoothSco()
    }
}
