package com.alef.assistentea.core

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import com.alef.assistentea.command.Command
import com.alef.assistentea.command.CommandParser
import com.alef.assistentea.media.MediaControl
import com.alef.assistentea.media.TrackInfo
import com.alef.assistentea.media.VolumeController
import com.alef.assistentea.music.MusicLauncher
import com.alef.assistentea.voice.BluetoothMic
import com.alef.assistentea.voice.Speaker
import com.alef.assistentea.voice.SpeechListener

/**
 * O "cérebro" da A: ouve, entende, executa e responde.
 *
 * Roda dentro do [com.alef.assistentea.service.AssistantService] (serviço em primeiro plano),
 * então continua funcionando com a tela apagada ou com outro app aberto.
 * A tela (MainActivity) só se conecta a ele para mostrar o que está acontecendo.
 *
 * Deve ser usado sempre na thread principal.
 */
class AssistantEngine(private val context: Context, private val host: Host) :
    SpeechListener.Callback, Speaker.Listener {

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    /** Quem hospeda o motor (o serviço). */
    interface Host {
        /** Nada acontecendo e a escuta contínua está desligada: o serviço pode encerrar. */
        fun onEngineIdle()
        fun onContinuousChanged(on: Boolean)
        /** O Android não deixou usar o microfone em segundo plano. */
        fun onMicBlocked()
    }

    /** A tela, quando aberta. */
    interface Ui {
        fun onState(state: State, continuous: Boolean, manual: Boolean)
        fun onHeard(text: String)
        fun onReply(text: String, error: Boolean)
        fun onVoiceLevel(level: Float)
        fun onRefresh()
    }

    var ui: Ui? = null
        set(value) {
            field = value
            value?.onState(state, continuous, manualTrigger)
            if (lastReply.isNotEmpty()) value?.onReply(lastReply, lastReplyError)
        }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private val speech = SpeechListener(context, this)
    private val speaker = Speaker(context, this)
    private val volume = VolumeController(context)
    private val media = MediaControl(context)
    private val music = MusicLauncher(context)
    private val device = DeviceActions(context)
    private val btMic = BluetoothMic(context)

    var state = State.IDLE
        private set
    var continuous = false
        private set
    /** A escuta contínua foi ligada pela conexão da caixa Bluetooth. */
    var startedByBluetooth = false
        private set

    private var manualTrigger = true
    private var duckedIndex: Int? = null
    private var smoothedLevel = 0f
    private var errorsInARow = 0
    private var lastReply = ""
    private var lastReplyError = false

    private val relisten = Runnable {
        if (continuous && state == State.IDLE && !speech.isListening && !speaker.isSpeaking) {
            startListening(manual = false)
        }
    }

    private val idleCheck: Runnable = object : Runnable {
        override fun run() {
            if (continuous) return
            if (state == State.IDLE && !speaker.isSpeaking && !speech.isListening) {
                btMic.release()
                host.onEngineIdle()
            } else {
                main.postDelayed(this, 3000) // ainda ocupada: confere de novo depois
            }
        }
    }

    // ================================================================== comandos externos

    /** Toque no microfone (tela, widget, atalho, botão da notificação). */
    fun tap() {
        when (state) {
            State.LISTENING -> {
                speech.cancel()
                restoreDuck()
                setState(State.IDLE)
                if (continuous) main.postDelayed(relisten, 1500) else scheduleIdleCheck()
                return
            }
            State.SPEAKING -> speaker.stop()
            else -> {}
        }
        startListening(manual = true)
    }

    fun setContinuous(on: Boolean, byBluetooth: Boolean = false) {
        if (on == continuous) {
            if (on && byBluetooth) startedByBluetooth = true
            return
        }
        continuous = on
        startedByBluetooth = on && byBluetooth
        errorsInARow = 0
        host.onContinuousChanged(on)
        if (on) {
            showReply("Escuta contínua ligada. Comece a frase com \"A\". Ex.: \"A, pausar\".")
            main.removeCallbacks(idleCheck)
            if (state == State.IDLE) main.postDelayed(relisten, 300)
        } else {
            main.removeCallbacks(relisten)
            if (speech.isListening) {
                speech.cancel()
                restoreDuck()
                setState(State.IDLE)
            }
            btMic.release()
            showReply("Escuta contínua desligada.")
            scheduleIdleCheck()
        }
        ui?.onState(state, continuous, manualTrigger)
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        speech.destroy()
        speaker.shutdown()
        restoreDuck()
        btMic.release()
    }

    // ================================================================== escuta

    private fun startListening(manual: Boolean) {
        if (!speech.isAvailable()) {
            reply("Reconhecimento de voz indisponível. Instale ou atualize o app Google.", error = true)
            return
        }
        if (speech.isListening) return
        if (speaker.isSpeaking) speaker.stop()
        main.removeCallbacks(idleCheck)
        main.removeCallbacks(relisten)
        manualTrigger = manual
        // Abaixa a música para o microfone ouvir melhor
        if (manual && duckedIndex == null && media.isMusicPlaying()) {
            duckedIndex = volume.duck(15)
        }
        ui?.onHeard("")
        setState(State.LISTENING)

        if (prefs.speakerMic && !btMic.isActive) {
            val ok = btMic.acquire { if (state == State.LISTENING && !speech.isListening) speech.start() }
            if (ok) return
            showReply("Sua caixa não tem microfone compatível. Usando o microfone do celular.", error = true)
        }
        speech.start()
    }

    private fun restoreDuck() {
        duckedIndex?.let { volume.restoreIndex(it) }
        duckedIndex = null
    }

    private fun scheduleIdleCheck() {
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 4000)
    }

    private fun afterTurn(delay: Long = 400) {
        if (!continuous && btMic.isActive) btMic.release()
        if (continuous) main.postDelayed(relisten, delay) else scheduleIdleCheck()
    }

    private fun beep() {
        if (!prefs.wakeBeep) return
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {
        }
    }

    // ================================================================== SpeechListener.Callback

    override fun onListeningStarted() {
        if (state != State.LISTENING) setState(State.LISTENING)
    }

    override fun onPartialText(text: String) {
        ui?.onHeard("“$text”")
        // Na escuta contínua, ao ouvir "A, ..." já abaixa a música para entender melhor o resto da frase
        if (continuous && !manualTrigger && duckedIndex == null && media.isMusicPlaying() &&
            CommandParser.parse(text).hadWakeWord
        ) {
            duckedIndex = volume.duck(15)
        }
    }

    override fun onVoiceLevel(rmsDb: Float) {
        if (state != State.LISTENING) return
        val level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        smoothedLevel = smoothedLevel * 0.6f + level * 0.4f
        ui?.onVoiceLevel(smoothedLevel)
    }

    override fun onFinalResults(alternatives: List<String>) {
        errorsInARow = 0
        val waitingWake = continuous && !manualTrigger
        val best = CommandParser.parseBest(alternatives, preferWake = waitingWake)
        ui?.onHeard("“${alternatives.first()}”")

        // Na escuta contínua, só reage se a frase começar com "A" e for um comando
        if (waitingWake && (!best.hadWakeWord || best.command is Command.Unknown)) {
            restoreDuck()
            setState(State.IDLE)
            main.postDelayed(relisten, 250)
            return
        }
        if (best.command == Command.WakeOnly) {
            // "A" sozinho: bipe e escuta o pedido (como a Alexa)
            restoreDuck()
            setState(State.IDLE)
            beep()
            main.postDelayed({ startListening(manual = true) }, 250)
            return
        }
        restoreDuck()
        setState(State.PROCESSING)
        if (waitingWake) beep()
        execute(best.command)
    }

    override fun onListenError(message: String, silent: Boolean, code: Int) {
        restoreDuck()
        setState(State.IDLE)
        if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            showReply("O Android bloqueou o microfone. Abra a A para liberar.", error = true)
            if (continuous) setContinuous(false)
            host.onMicBlocked()
            return
        }
        if (continuous && !manualTrigger && silent) {
            main.postDelayed(relisten, 250)
            return
        }
        if (silent) showReply(message) else showReply(message, error = true)
        if (!silent && !continuous) speaker.speak(message)
        if (continuous) {
            errorsInARow++
            // Espera mais a cada erro seguido (sem internet, por exemplo), até 30 s
            val wait = (2500L shl (errorsInARow - 1).coerceAtMost(4)).coerceAtMost(30_000L)
            main.postDelayed(relisten, wait)
        } else {
            scheduleIdleCheck()
        }
    }

    // ================================================================== Speaker.Listener

    override fun onSpeakStart() = setState(State.SPEAKING)

    override fun onSpeakDone() {
        if (state == State.SPEAKING || state == State.PROCESSING) setState(State.IDLE)
    }

    override fun onTtsProblem(message: String) = showReply(message, error = true)

    // ================================================================== execução

    private fun execute(cmd: Command) {
        when (cmd) {
            is Command.Play -> playMusic(cmd)

            Command.Pause -> { media.pause(); reply("Pausado.") }
            Command.Stop -> { media.stop(); reply("Música parada.") }
            Command.Resume -> reply("Continuando.") { media.play() }
            Command.Next -> { media.next(); reply("Próxima.") }
            Command.Previous -> { media.previous(); reply("Música anterior.") }

            Command.VolumeUp -> reply("Volume em ${volume.up()} por cento.")
            Command.VolumeDown -> reply("Volume em ${volume.down()} por cento.")
            is Command.SetVolume -> reply("Volume em ${volume.setPercent(cmd.percent)} por cento.")
            Command.VolumeQuery -> reply(
                if (volume.isMuted()) "O som está no mudo." else "O volume está em ${volume.percent()} por cento."
            )
            Command.Mute -> reply("Silenciando.") { volume.mute() }
            Command.Unmute -> { volume.unmute(); reply("Som ativado.") }

            Command.NowPlaying -> {
                val t = media.nowPlaying()
                when {
                    t != null -> reply("Está tocando ${t.title}" + (t.artist?.let { ", de $it" } ?: "") + ".")
                    !media.hasNotificationAccess() ->
                        reply("Para eu saber o nome da música, abra a A e toque em \"Permitir mostrar a música atual\".")
                    else -> reply("Não tem nenhuma música tocando agora.")
                }
            }

            Command.Time -> reply(device.timeSentence())
            Command.Date -> reply(device.dateSentence())
            Command.Battery -> reply(device.batterySentence())

            is Command.Timer -> when {
                cmd.seconds <= 0 -> reply("De quanto tempo? Diga, por exemplo: timer de cinco minutos.")
                device.setTimer(cmd.seconds) -> reply("Timer de ${device.durationSentence(cmd.seconds)}, valendo!")
                else -> reply("Não consegui criar o timer. Confira se o app Relógio está instalado.", error = true)
            }

            is Command.Alarm -> when {
                cmd.hour < 0 -> reply("Para que horas? Diga, por exemplo: despertador às sete.")
                device.setAlarm(cmd.hour, cmd.minute) ->
                    reply("Despertador marcado para ${device.clockSentence(cmd.hour, cmd.minute)}.")
                else -> reply("Não consegui criar o despertador. Confira se o app Relógio está instalado.", error = true)
            }

            is Command.Flashlight -> {
                val ok = device.setFlashlight(cmd.on)
                when {
                    !ok -> reply("Não consegui mexer na lanterna.", error = true)
                    cmd.on -> reply("Lanterna ligada.")
                    else -> reply("Lanterna desligada.")
                }
            }

            is Command.OpenApp -> {
                val name = cmd.name
                reply("Abrindo $name.") {
                    if (device.openApp(name) == null) showReply("Não encontrei o app \"$name\".", error = true)
                }
            }

            Command.Joke -> reply(device.joke())
            Command.Thanks -> reply(listOf("De nada!", "Por nada!", "Imagina!", "Disponha!").random())
            Command.Greeting -> reply(greeting())

            Command.Help -> reply(
                "Você pode dizer: tocar e o nome da música ou do artista, pausar, continuar, próxima, " +
                    "volume cinquenta por cento, que horas são, timer de cinco minutos, despertador às sete, " +
                    "liga a lanterna, abrir WhatsApp, e conta uma piada."
            )

            Command.StopListening -> {
                if (continuous) reply("Tá bom, parei de ouvir. Até mais!") { setContinuous(false) }
                else reply("Até mais!")
            }

            Command.Cancel -> { setState(State.IDLE); showReply("Ok."); afterTurn() }

            Command.WakeOnly -> reply("Oi! Pode falar.") { startListening(manual = true) }

            is Command.Unknown -> reply("Não entendi. Diga \"ajuda\" para ouvir os comandos.")
        }
        ui?.onRefresh()
        main.postDelayed({ ui?.onRefresh() }, 700)
    }

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val s = when (h) { in 5..11 -> "Bom dia"; in 12..17 -> "Boa tarde"; else -> "Boa noite" }
        return "$s! Em que posso ajudar?"
    }

    private fun playMusic(cmd: Command.Play) {
        val what = cmd.artist?.let { "músicas de $it" } ?: cmd.query
        val before = media.nowPlaying()
        reply("Tocando $what.") {
            when (val result = music.play(cmd.query, cmd.artist, media)) {
                is MusicLauncher.Result.Started -> {
                    showReply("▶ $what · ${result.playerLabel}")
                    if (result.viaSession) verifySessionPlayback(cmd, before)
                }
                is MusicLauncher.Result.OpenedSearch ->
                    showReply("Abri a busca no ${result.playerLabel}. Toque na música para começar.")
                MusicLauncher.Result.NoPlayer ->
                    reply("Não encontrei um app de música. Instale o Spotify ou o YouTube Music.", error = true)
            }
        }
    }

    /**
     * O pedido "em segundo plano" pode ser ignorado por alguns players.
     * Se depois de alguns segundos nada mudou, abre o app de música do jeito tradicional.
     */
    private fun verifySessionPlayback(cmd: Command.Play, before: TrackInfo?) {
        main.postDelayed({
            val now = media.nowPlaying()
            val changed = now != null && now.playing && (before == null || now.title != before.title || !before.playing)
            if (!changed) {
                when (val r = music.play(cmd.query, cmd.artist, media = null)) {
                    is MusicLauncher.Result.Started -> showReply("▶ Abrindo no ${r.playerLabel}…")
                    is MusicLauncher.Result.OpenedSearch -> showReply("Abri a busca no ${r.playerLabel}.")
                    MusicLauncher.Result.NoPlayer -> showReply("Não consegui iniciar a música.", error = true)
                }
            }
        }, 3500)
    }

    /** Mostra e fala a resposta. [after] roda quando a fala terminar. */
    private fun reply(text: String, error: Boolean = false, after: (() -> Unit)? = null) {
        showReply(text, error)
        speaker.speak(text) {
            after?.invoke()
            ui?.onRefresh()
            if (!speech.isListening) afterTurn()
        }
    }

    private fun showReply(text: String, error: Boolean = false) {
        lastReply = text
        lastReplyError = error
        ui?.onReply(text, error)
    }

    private fun setState(s: State) {
        state = s
        ui?.onState(s, continuous, manualTrigger)
    }
}
