package com.smartwatch.watchtunes

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Sends audio to the watch over the Bluetooth *call* channel (HFP/SCO).
 *
 * Calling smartwatches like the Titan Heritage usually expose only the hands-free
 * (call) profile, not the music (A2DP) profile. We put the phone in
 * "communication" mode and pick the watch as the communication device, so any
 * audio tagged as voice-communication plays on the watch speaker.
 *
 * Some phones (e.g. Xiaomi/HyperOS) don't list the watch as a communication
 * device until a call starts, so we also fall back to the legacy SCO API when
 * the Bluetooth hands-free profile reports a connected device.
 */
class WatchAudioRouter(private val context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)
    private var headset: BluetoothHeadset? = null

    var active = false
        private set
    private var usingLegacySco = false

    /** Last SCO state reported by the system (legacy path), for diagnostics. */
    var scoState = "unknown"
        private set

    /** Called when something changed (headset connected, SCO state). */
    var onChanged: (() -> Unit)? = null

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            scoState = when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> "connected"
                AudioManager.SCO_AUDIO_STATE_CONNECTING -> "connecting"
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "disconnected"
                AudioManager.SCO_AUDIO_STATE_ERROR -> "error"
                else -> "unknown"
            }
            onChanged?.invoke()
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        connectHeadsetProxy()
    }

    fun release() {
        runCatching { context.unregisterReceiver(scoReceiver) }
        headset?.let {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?.closeProfileProxy(BluetoothProfile.HEADSET, it)
            }
        }
        headset = null
    }

    fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Re-try after the user grants the Bluetooth permission. */
    fun connectHeadsetProxy() {
        if (headset != null || !hasBluetoothPermission()) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    headset = proxy as BluetoothHeadset
                    onChanged?.invoke()
                }

                override fun onServiceDisconnected(profile: Int) {
                    headset = null
                }
            }, BluetoothProfile.HEADSET)
        }
    }

    /** Devices connected over the hands-free (call) profile. */
    @SuppressLint("MissingPermission")
    private fun handsFreeDevices(): List<BluetoothDevice> =
        if (!hasBluetoothPermission()) emptyList()
        else runCatching { headset?.connectedDevices.orEmpty() }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    private fun handsFreeName(): String? =
        handsFreeDevices().firstOrNull()?.let { runCatching { it.name }.getOrNull() ?: "Bluetooth watch" }

    /** The watch's call-audio device as AudioManager sees it, or null. */
    private fun findAudioDevice(): AudioDeviceInfo? {
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: candidates.firstOrNull { it.type in BLE_TYPES }
    }

    /** True when a watch with call audio seems to be connected. */
    fun isWatchConnected() = findAudioDevice() != null || handsFreeDevices().isNotEmpty()

    fun watchName(): String? =
        findAudioDevice()?.productName?.toString()?.takeIf { it.isNotBlank() } ?: handsFreeName()

    /** Opens the call channel to the watch. With [force], tries even if nothing is detected. */
    fun start(force: Boolean = false): Boolean {
        if (active) return true
        val device = findAudioDevice()
        if (device == null && handsFreeDevices().isEmpty() && !force) return false

        am.mode = AudioManager.MODE_IN_COMMUNICATION
        var ok = false
        if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ok = runCatching { am.setCommunicationDevice(device) }.getOrDefault(false)
        }
        if (!ok) {
            // Legacy path: asks the Bluetooth stack to open SCO to the hands-free device.
            startScoLegacy()
            usingLegacySco = true
            ok = true
        }
        active = ok
        return ok
    }

    /** Closes the call channel so the watch is free again. */
    fun stop() {
        if (!active) return
        if (usingLegacySco) {
            stopScoLegacy()
            usingLegacySco = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
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

    @SuppressLint("MissingPermission")
    fun diagnostics(): String = buildString {
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Bluetooth permission: ${if (hasBluetoothPermission()) "granted" else "NOT granted"}")
        appendLine("Hands-free profile ready: ${headset != null}")
        val hf = handsFreeDevices()
        appendLine("Hands-free (call) devices: " +
            if (hf.isEmpty()) "none" else hf.joinToString { runCatching { it.name }.getOrNull() ?: "?" })
        appendLine("Detected watch audio device: ${findAudioDevice()?.let { describe(it) } ?: "none"}")
        appendLine("Routing active: $active (legacy SCO: $usingLegacySco)")
        appendLine("SCO state: $scoState")
        appendLine("Audio mode: ${am.mode}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appendLine("Current comm device: ${am.communicationDevice?.let { describe(it) } ?: "none"}")
            appendLine("\nCommunication devices:")
            am.availableCommunicationDevices.forEach { appendLine(" • ${describe(it)}") }
        }
        appendLine("\nOutput devices:")
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { appendLine(" • ${describe(it)}") }
        appendLine("\nInput devices:")
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { appendLine(" • ${describe(it)}") }
    }

    private fun describe(d: AudioDeviceInfo) =
        "${d.productName} [${typeName(d.type)}]"

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT call (SCO)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT music (A2DP)"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        26 -> "BLE headset"
        27 -> "BLE speaker"
        30 -> "BLE broadcast"
        else -> "type $t"
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

    private companion object {
        // AudioDeviceInfo.TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER (API 31)
        val BLE_TYPES = setOf(26, 27)
    }
}
