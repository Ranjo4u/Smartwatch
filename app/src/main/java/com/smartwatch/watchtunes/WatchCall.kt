package com.smartwatch.watchtunes

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi

/**
 * Opens the watch's audio link the way WhatsApp does: by starting a silent,
 * self-managed VoIP "call" through Android's Telecom system.
 *
 * Many phones (e.g. OnePlus on Android 16) only connect Bluetooth call audio
 * (SCO) for a real call managed by Telecom, and ignore apps that ask directly.
 * While this call is active, anything we play as voice-communication audio
 * goes to the watch speaker.
 */
object WatchCall {

    private const val ACCOUNT_ID = "watchtunes"

    /** The active fake call, or null. */
    var connection: WatchConnection? = null
        internal set

    /** True between placing the call and Telecom creating (or rejecting) it. */
    var pending = false
        internal set

    var lastRoute = "none"
        internal set
    var lastError: String? = null
        internal set

    /** Called on the main thread when the call state or audio route changes. */
    var onChanged: (() -> Unit)? = null

    /** Called when the call ends from outside the app (e.g. the hang-up button on the watch). */
    var onEndedExternally: (() -> Unit)? = null

    val isActive get() = connection != null || pending

    internal val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var forcedDevice = false

    /**
     * Fallback when Telecom won't switch the call to Bluetooth: select the
     * watch's SCO device ourselves (it only appears once the call is active).
     */
    internal fun forceWatchDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val am = appContext?.getSystemService(AudioManager::class.java) ?: return false
        val sco = am.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return false
        if (am.communicationDevice?.id == sco.id) return true
        val ok = runCatching { am.setCommunicationDevice(sco) }.getOrDefault(false)
        if (ok) forcedDevice = true
        return ok
    }

    internal fun notifyChanged() {
        main.post { onChanged?.invoke() }
    }

    private fun handle(ctx: Context) = PhoneAccountHandle(
        ComponentName(ctx, WatchConnectionService::class.java), ACCOUNT_ID
    )

    private fun register(ctx: Context) {
        val account = PhoneAccount.builder(handle(ctx), "WatchTunes")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
            .build()
        ctx.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
    }

    /** Starts the silent call. Returns false if Android refused straight away. */
    fun start(ctx: Context): Boolean {
        if (isActive) return true
        appContext = ctx.applicationContext
        return try {
            register(ctx)
            val tm = ctx.getSystemService(TelecomManager::class.java)
            if (!tm.isOutgoingCallPermitted(handle(ctx))) {
                lastError = "Android doesn't allow a call right now (another call active?)"
                return false
            }
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(ctx))
            }
            pending = true
            lastError = null
            tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_SIP, "WatchTunes", null), extras)
            true
        } catch (e: Exception) {
            pending = false
            lastError = "Couldn't start call: ${e.message}"
            false
        }
    }

    /** Ends the silent call so the watch is free again. */
    fun stop() {
        pending = false
        if (forcedDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext?.getSystemService(AudioManager::class.java)?.clearCommunicationDevice()
        }
        forcedDevice = false
        connection?.endCall(fromUser = false)
        connection = null
        lastRoute = "none"
    }
}

class WatchConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val c = WatchConnection()
        c.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        c.audioModeIsVoip = true
        c.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        c.setCallerDisplayName("WatchTunes", TelecomManager.PRESENTATION_ALLOWED)
        c.setActive()
        WatchCall.pending = false
        WatchCall.connection = c
        WatchCall.notifyChanged()
        return c
    }

    override fun onCreateOutgoingConnectionFailed(
        account: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        WatchCall.pending = false
        WatchCall.connection = null
        WatchCall.lastError = "Android refused the call"
        WatchCall.notifyChanged()
    }
}

class WatchConnection : Connection() {

    private var ended = false
    private var btEndpoint: CallEndpoint? = null
    private var currentType = -1
    private var attempts = 0
    private var requestInFlight = false

    private val switchToWatch = Runnable { trySwitch() }

    private fun scheduleSwitch(delayMs: Long) {
        WatchCall.main.removeCallbacks(switchToWatch)
        WatchCall.main.postDelayed(switchToWatch, delayMs)
    }

    fun endCall(fromUser: Boolean) {
        if (ended) return
        ended = true
        WatchCall.main.removeCallbacks(switchToWatch)
        setDisconnected(DisconnectCause(if (fromUser) DisconnectCause.REMOTE else DisconnectCause.LOCAL))
        destroy()
        if (WatchCall.connection === this) WatchCall.connection = null
        WatchCall.lastRoute = "none"
        WatchCall.notifyChanged()
    }

    // Hang-up from the watch, the phone, or another call taking over.
    override fun onDisconnect() = endedExternally()
    override fun onAbort() = endedExternally()
    override fun onHold() = endedExternally()

    private fun endedExternally() {
        val wasOurs = WatchCall.connection === this
        endCall(fromUser = true)
        if (wasOurs) Handler(Looper.getMainLooper()).post { WatchCall.onEndedExternally?.invoke() }
    }

    // ---- push the audio route to Bluetooth (the watch) ----

    @Deprecated("Replaced by onCallEndpointChanged on API 34+")
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        WatchCall.lastRoute = CallAudioState.audioRouteToString(state.route)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0 &&
            state.route != CallAudioState.ROUTE_BLUETOOTH
        ) {
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        }
        WatchCall.notifyChanged()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(endpoints: List<CallEndpoint>) {
        btEndpoint = endpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
        // Wait a moment: Telecom is often still settling the route, and a request
        // sent now gets cancelled by its own ("canceled by another request").
        if (btEndpoint != null) scheduleSwitch(600)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(endpoint: CallEndpoint) {
        currentType = endpoint.endpointType
        WatchCall.lastRoute = "${endpoint.endpointName} (${endpointType(endpoint.endpointType)})"
        if (currentType != CallEndpoint.TYPE_BLUETOOTH && btEndpoint != null) scheduleSwitch(600)
        WatchCall.notifyChanged()
    }

    /** One request at a time; retry a few times, then select the device directly. */
    private fun trySwitch() {
        if (ended || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val bt = btEndpoint ?: return
        if (currentType == CallEndpoint.TYPE_BLUETOOTH || requestInFlight) return
        attempts++
        if (attempts > 3) {
            // Telecom keeps refusing: pick the watch's audio device ourselves.
            if (WatchCall.forceWatchDevice()) {
                WatchCall.lastRoute = "watch (selected directly)"
                WatchCall.lastError = null
                WatchCall.notifyChanged()
            }
            if (attempts > 10) return
        }
        requestInFlight = true
        requestCallEndpointChange(
            bt,
            { it.run() },
            object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) {
                    requestInFlight = false
                    WatchCall.lastError = null
                    WatchCall.notifyChanged()
                }

                override fun onError(error: CallEndpointException) {
                    requestInFlight = false
                    WatchCall.lastError = "Switch attempt $attempts failed: ${error.message}"
                    WatchCall.notifyChanged()
                    WatchCall.main.post { scheduleSwitch(1000) }
                }
            },
        )
    }

    private fun endpointType(t: Int) = when (t) {
        CallEndpoint.TYPE_BLUETOOTH -> "Bluetooth"
        CallEndpoint.TYPE_EARPIECE -> "earpiece"
        CallEndpoint.TYPE_SPEAKER -> "speaker"
        CallEndpoint.TYPE_WIRED_HEADSET -> "wired"
        CallEndpoint.TYPE_STREAMING -> "streaming"
        else -> "unknown"
    }
}
