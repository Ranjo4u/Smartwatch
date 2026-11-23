package com.smartwatch.watchtunes

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var router: WatchAudioRouter
    private lateinit var watchStatus: TextView
    private lateinit var routeStatus: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var songList: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var savedTitles: List<String> = emptyList()

    private val pickSongs =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            uris.forEach {
                runCatching {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            Prefs.setSongs(this, uris)
            savedTitles = uris.map { Prefs.title(this, it) }
            MusicService.send(this, MusicService.ACTION_PLAY, 0)
        }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            router.connectHeadsetProxy()
            refreshWatch()
        }

    private val stateListener: (PlayerState) -> Unit = { render(it) }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshWatch()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshWatch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        router = WatchAudioRouter(this)
        router.onChanged = { refreshWatch() }

        watchStatus = findViewById(R.id.watchStatus)
        routeStatus = findViewById(R.id.routeStatus)
        nowPlaying = findViewById(R.id.nowPlaying)
        playBtn = findViewById(R.id.playBtn)
        songList = findViewById(R.id.songList)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, mutableListOf())
        songList.adapter = adapter
        songList.setOnItemClickListener { _, _, pos, _ ->
            MusicService.send(this, MusicService.ACTION_PLAY, pos)
        }
        savedTitles = Prefs.songs(this).map { Prefs.title(this, it) }

        findViewById<Button>(R.id.pickBtn).setOnClickListener {
            pickSongs.launch(arrayOf("audio/*"))
        }
        playBtn.setOnClickListener { MusicService.send(this, MusicService.ACTION_TOGGLE) }
        findViewById<ImageButton>(R.id.nextBtn).setOnClickListener {
            MusicService.send(this, MusicService.ACTION_NEXT)
        }
        findViewById<ImageButton>(R.id.prevBtn).setOnClickListener {
            MusicService.send(this, MusicService.ACTION_PREV)
        }
        findViewById<ImageButton>(R.id.stopBtn).setOnClickListener {
            if (MusicService.state.running) MusicService.send(this, MusicService.ACTION_STOP)
        }

        findViewById<Button>(R.id.diagBtn).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.volUp).setOnClickListener { router.adjustVolume(up = true) }
        findViewById<Button>(R.id.volDown).setOnClickListener { router.adjustVolume(up = false) }

        findViewById<MaterialSwitch>(R.id.watchSwitch).apply {
            isChecked = Prefs.toWatch(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setToWatch(this@MainActivity, on)
                updateVolumeStream()
                if (MusicService.state.running) MusicService.send(this@MainActivity, MusicService.ACTION_SETTINGS)
            }
        }
        findViewById<MaterialSwitch>(R.id.routeAllSwitch).apply {
            isChecked = Prefs.routeAll(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setRouteAll(this@MainActivity, on)
                if (on || MusicService.state.running) {
                    MusicService.send(this@MainActivity, MusicService.ACTION_SETTINGS)
                }
                if (on) {
                    Toast.makeText(
                        this@MainActivity,
                        "Now play YouTube/Spotify. Works on many phones, not all.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        val boostLabel = findViewById<TextView>(R.id.boostLabel)
        findViewById<SeekBar>(R.id.boostBar).apply {
            progress = Prefs.boost(this@MainActivity) / 100
            boostLabel.text = boostText(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    boostLabel.text = boostText(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    Prefs.setBoost(this@MainActivity, bar.progress * 100)
                    if (MusicService.state.running) {
                        MusicService.send(this@MainActivity, MusicService.ACTION_SETTINGS)
                    }
                }
            })
        }

        requestNeededPermissions()
        updateVolumeStream()
    }

    override fun onStart() {
        super.onStart()
        MusicService.addListener(stateListener)
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshWatch()
    }

    override fun onStop() {
        MusicService.removeListener(stateListener)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        super.onStop()
    }

    override fun onDestroy() {
        router.onChanged = null
        router.release()
        super.onDestroy()
    }

    private fun showDiagnostics() {
        val text = (MusicService.diagnostics ?: router::diagnostics).invoke()
        AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("WatchTunes diagnostics", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun boostText(step: Int) = "Loudness boost: +${step} dB" +
        if (step >= 10) " (may distort)" else ""

    /** Hardware volume keys control the watch (call) volume when watch output is on. */
    private fun updateVolumeStream() {
        volumeControlStream =
            if (Prefs.toWatch(this)) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    }

    private fun requestNeededPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (perms.isNotEmpty()) askPermissions.launch(perms.toTypedArray())
    }

    private fun refreshWatch() {
        watchStatus.text = when {
            router.isWatchConnected() ->
                "⌚ Watch connected" + (router.watchName()?.let { ": $it" } ?: "")
            !router.hasBluetoothPermission() ->
                "⌚ Allow the Bluetooth (Nearby devices) permission so the app can find your watch"
            else ->
                "⌚ Watch call audio not detected – you can still press Play to try. Tap Diagnostics for details."
        }
    }

    private fun render(s: PlayerState) {
        val titles = s.titles.ifEmpty { savedTitles }
        adapter.clear()
        adapter.addAll(titles)
        if (s.index in titles.indices) songList.setItemChecked(s.index, true)

        nowPlaying.text = titles.getOrNull(s.index)?.let { (if (s.playing) "▶ " else "⏸ ") + it }
            ?: if (titles.isEmpty()) "Tap “Choose songs” to add music" else ""
        playBtn.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        routeStatus.text = when {
            s.routing -> "🔊 Audio is going to the watch speaker"
            s.playing -> "📱 Playing on phone speaker"
            else -> ""
        }
        // Stop also turns off "send all audio"; keep the switch in sync.
        findViewById<MaterialSwitch>(R.id.routeAllSwitch).isChecked = Prefs.routeAll(this)
        s.message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        refreshWatch()
    }
}
