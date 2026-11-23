package com.smartwatch.watchtunes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Plays the chosen songs and keeps the watch's call channel open while needed.
 * Runs as a foreground service so music continues with the screen off.
 */
class MusicService : Service() {

    companion object {
        const val ACTION_PLAY = "watchtunes.PLAY"
        const val ACTION_TOGGLE = "watchtunes.TOGGLE"
        const val ACTION_NEXT = "watchtunes.NEXT"
        const val ACTION_PREV = "watchtunes.PREV"
        const val ACTION_STOP = "watchtunes.STOP"
        const val ACTION_SETTINGS = "watchtunes.SETTINGS"
        const val EXTRA_INDEX = "index"

        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1

        var state = PlayerState()
            private set
        private val listeners = mutableSetOf<(PlayerState) -> Unit>()

        fun addListener(l: (PlayerState) -> Unit) {
            listeners += l
            l(state)
        }

        fun removeListener(l: (PlayerState) -> Unit) {
            listeners -= l
        }

        fun send(context: Context, action: String, index: Int = 0) {
            val intent = Intent(context, MusicService::class.java)
                .setAction(action)
                .putExtra(EXTRA_INDEX, index)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private lateinit var router: WatchAudioRouter
    private val handler = Handler(Looper.getMainLooper())

    private var songs: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = -1

    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var prepared = false
    private var playing = false
    private var playerUsesWatch = true
    private var message: String? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = publish()

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (router.active && router.findWatch() == null) {
                // Watch went out of range: pause instead of blasting the phone speaker.
                message = "Watch disconnected"
                pause()
                router.stop()
            }
            publish()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        router = WatchAudioRouter(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, handler)
        loadSongs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_PLAY -> {
                loadSongs()
                playIndex(intent.getIntExtra(EXTRA_INDEX, 0))
            }
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> if (songs.isNotEmpty()) playIndex((index + 1) % songs.size)
            ACTION_PREV -> if (songs.isNotEmpty()) {
                val restart = (player?.takeIf { prepared }?.currentPosition ?: 0) > 3000
                playIndex(if (restart || index <= 0) maxOf(index, 0) else index - 1)
            }
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_SETTINGS -> applySettings()
        }
        if (player == null && !Prefs.routeAll(this)) {
            shutdown()
            return START_NOT_STICKY
        }
        updateRouting()
        publish()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        releasePlayer()
        router.stop()
        state = PlayerState()
        listeners.toList().forEach { it(state) }
        super.onDestroy()
    }

    // ---- playback ----

    private fun loadSongs() {
        songs = Prefs.songs(this)
        titles = songs.map { Prefs.title(this, it) }
        if (index >= songs.size) index = -1
    }

    private fun playIndex(i: Int, startMs: Int = 0, autoStart: Boolean = true) {
        releasePlayer()
        if (songs.isEmpty()) {
            playing = false
            return
        }
        index = i.coerceIn(songs.indices)
        playerUsesWatch = Prefs.toWatch(this)
        playing = autoStart
        updateRouting() // open the watch channel *before* audio starts

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (playerUsesWatch) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(this, songs[index])
        } catch (e: Exception) {
            message = "Can't open ${titles[index]}"
            releasePlayer()
            playing = false
            updateRouting()
            return
        }
        mp.setOnPreparedListener {
            prepared = true
            if (startMs > 0) it.seekTo(startMs)
            applyBoost()
            if (playing) it.start()
            publish()
        }
        mp.setOnCompletionListener {
            if (index + 1 < songs.size) {
                playIndex(index + 1)
            } else {
                playing = false
                it.seekTo(0)
                updateRouting()
            }
            publish()
        }
        mp.setOnErrorListener { _, _, _ ->
            message = "Playback error"
            playing = false
            releasePlayer()
            updateRouting()
            publish()
            true
        }
        mp.prepareAsync()
    }

    private fun toggle() {
        val mp = player
        when {
            mp == null -> {
                if (songs.isEmpty()) loadSongs()
                if (songs.isNotEmpty()) playIndex(maxOf(index, 0))
            }
            !prepared -> playing = !playing
            playing -> pause()
            else -> {
                playing = true
                updateRouting()
                mp.start()
            }
        }
    }

    private fun pause() {
        playing = false
        player?.takeIf { prepared && it.isPlaying }?.pause()
        updateRouting()
    }

    private fun applySettings() {
        val mp = player
        if (mp != null && prepared && playerUsesWatch != Prefs.toWatch(this)) {
            // Output changed: recreate the player with new audio attributes at the same spot.
            playIndex(index, mp.currentPosition, autoStart = playing)
        } else {
            applyBoost()
        }
    }

    private fun applyBoost() {
        enhancer?.release()
        enhancer = null
        val mp = player ?: return
        val mB = Prefs.boost(this)
        if (mB <= 0) return
        enhancer = runCatching {
            LoudnessEnhancer(mp.audioSessionId).apply {
                setTargetGain(mB)
                enabled = true
            }
        }.getOrNull()
    }

    private fun releasePlayer() {
        enhancer?.release()
        enhancer = null
        player?.release()
        player = null
        prepared = false
    }

    /** Keep the watch call channel open only while something needs it. */
    private fun updateRouting() {
        val need = (playing && Prefs.toWatch(this)) || Prefs.routeAll(this)
        if (need && !router.active) {
            if (!router.start()) {
                message = "Watch not connected – pair it in Bluetooth settings (calls must work)"
            }
        } else if (!need && router.active) {
            router.stop()
        }
    }

    private fun shutdown() {
        releasePlayer()
        playing = false
        Prefs.setRouteAll(this, false)
        router.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- UI state + notification ----

    private fun publish() {
        state = PlayerState(
            running = true,
            titles = titles,
            index = index,
            playing = playing,
            routing = router.active,
            message = message,
        )
        message = null
        listeners.toList().forEach { it(state) }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        fun action(icon: Int, title: String, act: String) = NotificationCompat.Action(
            icon,
            title,
            PendingIntent.getService(
                this, act.hashCode(),
                Intent(this, MusicService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val title = titles.getOrNull(index)
            ?: if (Prefs.routeAll(this)) "Sending phone audio to watch" else "WatchTunes"
        val text = if (router.active) "🔊 On watch speaker" else "Playing on phone"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(action(R.drawable.ic_prev, "Previous", ACTION_PREV))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE)
                else action(R.drawable.ic_play, "Play", ACTION_TOGGLE)
            )
            .addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT))
            .addAction(action(R.drawable.ic_stop, "Stop", ACTION_STOP))
            .build()
    }
}
