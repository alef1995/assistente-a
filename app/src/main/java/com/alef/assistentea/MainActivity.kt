package com.alef.assistentea

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import com.alef.assistentea.core.AssistantEngine
import com.alef.assistentea.core.Prefs
import com.alef.assistentea.media.MediaControl
import com.alef.assistentea.media.TrackInfo
import com.alef.assistentea.media.VolumeController
import com.alef.assistentea.music.MusicLauncher
import com.alef.assistentea.service.Activation
import com.alef.assistentea.service.AssistantService

/**
 * Tela da assistente A.
 *
 * Quem ouve e responde é o [AssistantService] (funciona com a tela apagada).
 * Esta tela só se conecta a ele para mostrar o estado, e guarda as opções.
 */
class MainActivity : Activity(), AssistantEngine.Ui {

    companion object {
        private const val REQ_MIC = 10
        private const val REQ_NOTIF = 11
        private const val REQ_BT = 12
    }

    // Controles diretos (botões de play/volume) e opções
    private lateinit var volume: VolumeController
    private lateinit var media: MediaControl
    private lateinit var music: MusicLauncher
    private lateinit var prefs: Prefs

    // Views
    private lateinit var micButton: ImageButton
    private lateinit var micRing: View
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var replyText: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var volumeText: TextView
    private lateinit var playerChip: TextView
    private lateinit var mediaAccessButton: TextView
    private lateinit var overlayButton: TextView
    private lateinit var continuousSwitch: Switch
    private lateinit var bluetoothSwitch: Switch
    private lateinit var speakerMicSwitch: Switch

    private val main = Handler(Looper.getMainLooper())
    private var engine: AssistantEngine? = null
    private var bound = false
    private var updatingSwitch = false
    private var micDeniedOnce = false
    private var pulse: ValueAnimator? = null
    private var shownState: AssistantEngine.State? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val e = (service as AssistantService.LocalBinder).engine
            engine = e
            e.ui = this@MainActivity
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
        }
    }

    // ================================================================== ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        micButton = findViewById(R.id.micButton)
        micRing = findViewById(R.id.micRing)
        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        replyText = findViewById(R.id.replyText)
        trackTitle = findViewById(R.id.trackTitle)
        trackArtist = findViewById(R.id.trackArtist)
        playPauseButton = findViewById(R.id.playPauseButton)
        volumeText = findViewById(R.id.volumeText)
        playerChip = findViewById(R.id.playerChip)
        mediaAccessButton = findViewById(R.id.mediaAccessButton)
        overlayButton = findViewById(R.id.overlayButton)
        continuousSwitch = findViewById(R.id.continuousSwitch)
        bluetoothSwitch = findViewById(R.id.bluetoothSwitch)
        speakerMicSwitch = findViewById(R.id.speakerMicSwitch)

        volume = VolumeController(this)
        media = MediaControl(this)
        music = MusicLauncher(this)
        prefs = Prefs(this)

        micButton.setOnClickListener {
            withMicPermission { AssistantService.start(this, AssistantService.ACTION_TAP) }
        }

        playPauseButton.setOnClickListener {
            if (media.isMusicPlaying()) media.pause() else media.play()
            main.postDelayed({ refreshPlayButton() }, 500)
        }
        findViewById<ImageButton>(R.id.nextButton).setOnClickListener { media.next() }
        findViewById<ImageButton>(R.id.prevButton).setOnClickListener { media.previous() }
        findViewById<ImageButton>(R.id.volUpButton).setOnClickListener { volume.up(); refreshVolume() }
        findViewById<ImageButton>(R.id.volDownButton).setOnClickListener { volume.down(); refreshVolume() }

        playerChip.setOnClickListener {
            if (music.installedPlayers().isEmpty()) {
                onReply("Nenhum app de música encontrado. Instale o Spotify ou o YouTube Music.", true)
            } else {
                music.cyclePreferred()
                updatePlayerChip()
            }
        }

        mediaAccessButton.setOnClickListener {
            onReply("Ative a \"Alexa\" na lista e volte para o app.", false)
            openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        overlayButton.setOnClickListener {
            onReply("Ative \"Permitir exibição sobre outros apps\" para a Alexa e volte.", false)
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        findViewById<TextView>(R.id.assistantButton).setOnClickListener {
            onReply("Em \"App assistente\" (ou \"Assistente digital\"), escolha a Alexa. " +
                "Depois é só segurar o botão de início para chamá-la.", false)
            openSettings(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }

        continuousSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) {
                withMicPermission { AssistantService.start(this, AssistantService.ACTION_CONTINUOUS_ON) }
            } else {
                engine?.setContinuous(false)
            }
        }

        bluetoothSwitch.isChecked = prefs.autoBluetooth
        bluetoothSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoBluetooth = checked
            if (checked) {
                if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
                }
                onReply(
                    if (Settings.canDrawOverlays(this))
                        "Pronto! Quando a caixa conectar, a Alexa começa a ouvir sozinha."
                    else
                        "Pronto! Para ligar sem precisar tocar na notificação, permita \"exibir sobre outros apps\" abaixo.",
                    false
                )
            }
        }

        speakerMicSwitch.isChecked = prefs.speakerMic
        speakerMicSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.speakerMic = checked
            if (checked && Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        updatePlayerChip()
        onState(AssistantEngine.State.IDLE, AssistantService.continuousOn, true)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        Activation.clearTapNotification(this)
        refreshVolume()
        refreshPlayButton()
        mediaAccessButton.visibility = if (media.hasNotificationAccess()) View.GONE else View.VISIBLE
        overlayButton.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        media.observe { showTrack(it) }
        updatePlayerChip()
    }

    override fun onPause() {
        super.onPause()
        media.stopObserving()
    }

    override fun onStop() {
        super.onStop()
        engine?.ui = null
        engine = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        pulse?.cancel()
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ================================================================== permissões

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var afterPermission: (() -> Unit)? = null

    private fun withMicPermission(action: () -> Unit) {
        if (hasMicPermission()) {
            action()
            return
        }
        if (micDeniedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            onReply("Libere o microfone em Permissões › Microfone.", true)
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            setContinuousChecked(false)
            return
        }
        afterPermission = action
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            afterPermission?.invoke()
        } else {
            micDeniedOnce = true
            setContinuousChecked(false)
            onReply("Sem o microfone eu não consigo ouvir você. Toque no botão para permitir.", true)
        }
        afterPermission = null
    }

    // ================================================================== AssistantEngine.Ui

    override fun onState(state: AssistantEngine.State, continuous: Boolean, manual: Boolean) {
        setContinuousChecked(continuous)
        if (state == shownState && state != AssistantEngine.State.IDLE) return
        shownState = state
        pulse?.cancel()
        pulse = null
        when (state) {
            AssistantEngine.State.IDLE -> {
                statusText.text = if (continuous) "Aguardando \"Alexa…\"" else getString(R.string.status_idle)
                micButton.setImageResource(R.drawable.ic_mic)
                micRing.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            AssistantEngine.State.LISTENING -> {
                statusText.text = if (continuous && !manual) "Ouvindo… (diga \"Alexa, …\")"
                else getString(R.string.status_listening)
                micButton.setImageResource(R.drawable.ic_stop)
                micRing.animate().cancel()
                micRing.alpha = 1f
            }
            AssistantEngine.State.PROCESSING -> {
                statusText.text = getString(R.string.status_processing)
                micButton.setImageResource(R.drawable.ic_mic)
                micRing.alpha = 0.4f
            }
            AssistantEngine.State.SPEAKING -> {
                statusText.text = getString(R.string.status_speaking)
                micButton.setImageResource(R.drawable.ic_mic)
                micRing.animate().cancel()
                micRing.alpha = 0.7f
                pulse = ValueAnimator.ofFloat(0.95f, 1.15f).apply {
                    duration = 450
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        val v = it.animatedValue as Float
                        micRing.scaleX = v
                        micRing.scaleY = v
                    }
                    start()
                }
            }
        }
    }

    override fun onHeard(text: String) {
        heardText.text = text
    }

    override fun onReply(text: String, error: Boolean) {
        replyText.setTextColor(getColor(if (error) R.color.error else R.color.cyan))
        replyText.text = text
    }

    override fun onVoiceLevel(level: Float) {
        val scale = 0.9f + level * 0.35f
        micRing.scaleX = scale
        micRing.scaleY = scale
    }

    override fun onRefresh() {
        refreshVolume()
        refreshPlayButton()
    }

    // ================================================================== interface

    private fun setContinuousChecked(on: Boolean) {
        if (continuousSwitch.isChecked == on) return
        updatingSwitch = true
        continuousSwitch.isChecked = on
        updatingSwitch = false
    }

    private fun showTrack(t: TrackInfo?) {
        if (t == null) {
            trackTitle.text = getString(R.string.nothing_playing)
            trackArtist.text = if (media.hasNotificationAccess()) "" else "Permita o acesso abaixo para ver o nome da música"
        } else {
            trackTitle.text = t.title
            trackArtist.text = listOfNotNull(t.artist, t.appName).joinToString(" · ")
        }
        refreshPlayButton()
    }

    private fun refreshPlayButton() {
        playPauseButton.setImageResource(if (media.isMusicPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun refreshVolume() {
        volumeText.text = if (volume.isMuted()) "Volume: mudo" else "Volume: ${volume.percent()}%"
    }

    private fun updatePlayerChip() {
        val installed = music.installedPlayers()
        playerChip.text = when {
            installed.isEmpty() -> "Sem app de música"
            else -> "Player: " + (music.preferredPlayer?.label ?: "automático")
        }
    }
}
