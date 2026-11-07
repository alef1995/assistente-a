package com.alef.assistentea

import android.Manifest
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
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
import com.alef.assistentea.core.Updater
import com.alef.assistentea.ui.EmbeddedYouTube
import com.alef.assistentea.ui.WaveView

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
    private lateinit var waveView: WaveView
    private lateinit var settingsPanel: View
    private lateinit var tabletSwitch: Switch
    private lateinit var batteryButton: TextView
    private lateinit var updateButton: TextView
    private lateinit var youtube: EmbeddedYouTube
    private lateinit var updater: Updater
    private var release: Updater.Release? = null
    private var checkedUpdate = false
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

    private val main = Handler(Looper.getMainLooper())
    private var engine: AssistantEngine? = null
    private var bound = false
    private var updatingSwitch = false
    private var micDeniedOnce = false
    private var shownState: AssistantEngine.State? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val e = (service as AssistantService.LocalBinder).engine
            engine = e
            e.embedded = youtube
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
        waveView = findViewById(R.id.waveView)
        settingsPanel = findViewById(R.id.settingsPanel)
        tabletSwitch = findViewById(R.id.tabletSwitch)
        batteryButton = findViewById(R.id.batteryButton)
        updateButton = findViewById(R.id.updateButton)
        updater = Updater(this)

        // Player do YouTube dentro da tela (a música toca aqui, sem abrir o app do YouTube)
        val videoCard = findViewById<View>(R.id.videoCard)
        videoCard.clipToOutline = true
        youtube = EmbeddedYouTube(findViewById(R.id.youtubeWeb), videoCard) {
            showTrack(media.nowPlaying())
        }
        youtube.init()
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

        volume = VolumeController(this)
        media = MediaControl(this)
        music = MusicLauncher(this)
        prefs = Prefs(this)

        micButton.setOnClickListener {
            withMicPermission { AssistantService.start(this, AssistantService.ACTION_TAP) }
        }
        // Tocar nas ondas também chama a Zuri
        waveView.setOnClickListener {
            withMicPermission { AssistantService.start(this, AssistantService.ACTION_TAP) }
        }

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener { showSettings(true) }
        findViewById<ImageButton>(R.id.closeSettings).setOnClickListener { showSettings(false) }

        findViewById<Switch>(R.id.screenSaverSwitch).apply {
            isChecked = prefs.screenSaver
            setOnCheckedChangeListener { _, checked -> prefs.screenSaver = checked; applyTabletMode() }
        }

        // Cérebro de IA
        val aiKeyInput = findViewById<android.widget.EditText>(R.id.aiKeyInput)
        aiKeyInput.setText(prefs.aiKey)
        findViewById<TextView>(R.id.aiHelp).setOnClickListener {
            openSettings(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }
        val aiStatus = findViewById<TextView>(R.id.aiStatus)
        fun status(text: String, error: Boolean) {
            aiStatus.setTextColor(getColor(if (error) R.color.error else R.color.cyan))
            aiStatus.text = text
        }
        if (prefs.aiKey.isNotBlank()) {
            status("Chave salva (${com.alef.assistentea.core.AiBrain.providerFor(prefs.aiKey).name}).", false)
        }
        findViewById<TextView>(R.id.aiSaveButton).setOnClickListener {
            // fecha o teclado
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(aiKeyInput.windowToken, 0)
            prefs.aiKey = aiKeyInput.text.toString().replace(Regex("\\s"), "")
            aiKeyInput.setText(prefs.aiKey)
            if (prefs.aiKey.isBlank()) {
                status("Chave apagada. A Zuri volta a usar só os comandos fixos.", false)
                return@setOnClickListener
            }
            val provider = com.alef.assistentea.core.AiBrain.providerFor(prefs.aiKey).name
            status("Chave salva. Testando o $provider…", false)
            com.alef.assistentea.core.AiBrain(prefs).ask(
                "Diga oi e se apresente em uma frase curta.", "teste da configuração"
            ) { answer, error ->
                if (answer?.text != null) status("IA funcionando ($provider)! Ela disse: ${answer.text}", false)
                else status("A IA não respondeu" + (error?.let { ": $it" } ?: "") + ".", true)
            }
        }

        tabletSwitch.isChecked = prefs.tabletMode
        tabletSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.tabletMode = checked
            applyTabletMode()
            if (checked) withMicPermission { AssistantService.start(this, AssistantService.ACTION_CONTINUOUS_ON) }
        }

        batteryButton.setOnClickListener {
            @Suppress("BatteryLife")
            openSettings(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }

        playPauseButton.setOnClickListener {
            when {
                youtube.isActive -> if (youtube.isPlaying) youtube.pause() else youtube.resume()
                media.isMusicPlaying() -> media.pause()
                else -> media.play()
            }
            main.postDelayed({ refreshPlayButton() }, 500)
        }
        findViewById<ImageButton>(R.id.nextButton).setOnClickListener {
            if (youtube.isActive) youtube.next() else media.next()
        }
        findViewById<ImageButton>(R.id.prevButton).setOnClickListener {
            if (youtube.isActive) youtube.previous() else media.previous()
        }

        updateButton.setOnClickListener { installUpdate() }
        findViewById<TextView>(R.id.checkUpdateButton).setOnClickListener {
            onReply("Procurando atualização…", false)
            updater.check { r ->
                release = r
                showUpdateButton()
                if (r == null) onReply("A Zuri já está na versão mais nova (${updater.currentVersion()}).", false)
                else installUpdate()
            }
        }
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
            onReply("Ative a \"Zuri\" na lista e volte para o app.", false)
            openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        overlayButton.setOnClickListener {
            onReply("Ative \"Permitir exibição sobre outros apps\" para a Zuri e volte.", false)
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        findViewById<TextView>(R.id.assistantButton).setOnClickListener {
            onReply("Em \"App assistente\" (ou \"Assistente digital\"), escolha a Zuri. " +
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

        findViewById<Switch>(R.id.youtubeAppSwitch).apply {
            isChecked = prefs.youtubeInApp
            setOnCheckedChangeListener { _, checked -> prefs.youtubeInApp = checked }
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
                        "Pronto! Quando a caixa conectar, a Zuri começa a ouvir sozinha."
                    else
                        "Pronto! Para ligar sem precisar tocar na notificação, permita \"exibir sobre outros apps\" abaixo.",
                    false
                )
            }
        }

        if (hasMicPermission()) askNotificationPermission()

        updatePlayerChip()
        applyTabletMode()
        onState(AssistantEngine.State.IDLE, AssistantService.continuousOn, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsPanel.visibility == View.VISIBLE) showSettings(false)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun showSettings(show: Boolean) {
        if (show) {
            val forms = engine?.wakeForms.orEmpty()
            findViewById<TextView>(R.id.wakeFormsText).text =
                if (forms.isEmpty()) "Reconhecedor offline: carregando (ou indisponível; usando o do Google)."
                else "O nome é reconhecido como: " + forms.joinToString(", ")
            settingsPanel.alpha = 0f
            settingsPanel.visibility = View.VISIBLE
            settingsPanel.animate().alpha(1f).setDuration(200).start()
        } else {
            settingsPanel.animate().alpha(0f).setDuration(200).withEndAction {
                settingsPanel.visibility = View.GONE
            }.start()
        }
    }

    /** Modo tablet: tela sempre ligada e em tela cheia (sem barras do sistema). */
    /** Economia de energia: deixa a tela apagar sozinha (tempo de tela do Android). */
    private val allowScreenOff = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    /** Mantém a tela acesa agora; com a economia ligada, libera para apagar depois de [ms]. */
    private fun keepScreenOnFor(ms: Long) {
        main.removeCallbacks(allowScreenOff)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (prefs.screenSaver) main.postDelayed(allowScreenOff, ms)
    }

    private fun applyTabletMode() {
        if (prefs.tabletMode) {
            // Tela sempre acesa, ou (economia) acesa só enquanto a Zuri é usada
            keepScreenOnFor(30_000)
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.show(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && prefs.tabletMode) applyTabletMode()
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bound = bindService(Intent(this, AssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        Activation.clearTapNotification(this)
        refreshVolume()
        refreshPlayButton()
        mediaAccessButton.visibility = if (media.hasNotificationAccess()) View.GONE else View.VISIBLE
        overlayButton.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val pm = getSystemService(PowerManager::class.java)
        batteryButton.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        // Modo tablet: a Zuri fica sempre ouvindo
        if (prefs.tabletMode && hasMicPermission() && !AssistantService.continuousOn) {
            main.postDelayed({ AssistantService.start(this, AssistantService.ACTION_CONTINUOUS_ON) }, 600)
        } else if (prefs.tabletMode && !hasMicPermission() && !micDeniedOnce) {
            withMicPermission { AssistantService.start(this, AssistantService.ACTION_CONTINUOUS_ON) }
        }
        media.observe { showTrack(it) }
        updatePlayerChip()
        // Procura versão nova uma vez por abertura
        if (!checkedUpdate) {
            checkedUpdate = true
            updater.check { r -> release = r; showUpdateButton() }
        }
    }

    override fun onPause() {
        super.onPause()
        media.stopObserving()
    }

    override fun onDestroy() {
        // A conexão com o serviço fica aberta enquanto a tela existir: o player do YouTube mora aqui
        engine?.ui = null
        engine?.embedded = null
        engine = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        youtube.destroy()
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ================================================================== permissões

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            main.postDelayed({ requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) }, 300)
        }
    }

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
            askNotificationPermission()
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
        // Enquanto ouve/fala, a tela fica acesa; depois de 20 s parada, pode apagar (economia)
        if (prefs.tabletMode) {
            if (state == AssistantEngine.State.IDLE) keepScreenOnFor(20_000)
            else keepScreenOnFor(60_000)
        }
        if (state == shownState && state != AssistantEngine.State.IDLE) return
        shownState = state
        when (state) {
            AssistantEngine.State.IDLE -> {
                waveView.mode = WaveView.Mode.IDLE
                waveView.setLevel(0f)
                statusText.text = if (continuous) "Diga “Zuri”" else getString(R.string.status_idle)
                micButton.setImageResource(R.drawable.ic_mic)
            }
            AssistantEngine.State.LISTENING -> {
                waveView.mode = WaveView.Mode.LISTENING
                statusText.text = if (continuous && !manual) "Diga “Zuri”" else getString(R.string.status_listening)
                micButton.setImageResource(R.drawable.ic_stop)
            }
            AssistantEngine.State.PROCESSING -> {
                waveView.mode = WaveView.Mode.THINKING
                statusText.text = getString(R.string.status_processing)
                micButton.setImageResource(R.drawable.ic_mic)
            }
            AssistantEngine.State.SPEAKING -> {
                waveView.mode = WaveView.Mode.SPEAKING
                statusText.text = ""
                micButton.setImageResource(R.drawable.ic_mic)
            }
        }
    }

    override fun onHeard(text: String) {
        heardText.text = text
    }

    override fun onReply(text: String, error: Boolean) {
        replyText.setTextColor(getColor(if (error) R.color.error else R.color.cyan))
        replyText.text = text
        // Com as configurações abertas, a resposta ficaria escondida atrás do painel
        if (::settingsPanel.isInitialized && settingsPanel.visibility == View.VISIBLE && text.isNotBlank()) {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onVoiceLevel(level: Float) {
        waveView.setLevel(level)
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

    // ================================================================== atualização

    private fun showUpdateButton() {
        val r = release
        updateButton.visibility = if (r != null) View.VISIBLE else View.GONE
        if (r != null) updateButton.text = "Atualizar (versão ${r.version})"
    }

    private fun installUpdate() {
        val r = release ?: return
        if (!updater.canInstall()) {
            onReply("Permita \"Instalar apps desconhecidos\" para a Zuri e toque em Atualizar de novo.", false)
            openSettings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateButton.isEnabled = false
        onReply("Baixando a versão ${r.version}…", false)
        updater.install(r,
            onProgress = { pct ->
                updateButton.text = "Baixando… $pct%"
                if (pct >= 100) onReply("Pronto! Toque em \"Atualizar\" na janela do Android.", false)
            },
            onError = { msg ->
                updateButton.isEnabled = true
                showUpdateButton()
                onReply(msg, true)
            })
    }

    private fun showTrack(t: TrackInfo?) {
        if (youtube.isActive) {
            trackTitle.text = youtube.title ?: "YouTube"
            trackArtist.text = "YouTube · na tela da Zuri"
            refreshPlayButton()
            return
        }
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
        val playing = (youtube.isActive && youtube.isPlaying) || media.isMusicPlaying()
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun refreshVolume() {
        volumeText.text = if (volume.isMuted()) "mudo" else "vol ${volume.level()}"
    }

    private fun updatePlayerChip() {
        val installed = music.installedPlayers()
        playerChip.text = when {
            installed.isEmpty() -> "Sem app de música"
            else -> "Player: " + (music.preferredPlayer?.label ?: "automático")
        }
    }
}
