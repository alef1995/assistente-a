package com.alef.assistentea.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import com.alef.assistentea.command.Command
import com.alef.assistentea.command.CommandParser
import com.alef.assistentea.media.MediaControl
import com.alef.assistentea.media.TrackInfo
import com.alef.assistentea.media.VolumeController
import com.alef.assistentea.music.BackgroundPlayer
import com.alef.assistentea.music.MusicLauncher
import com.alef.assistentea.music.YouTubePlayer
import com.alef.assistentea.voice.PhoneMic
import com.alef.assistentea.voice.Speaker
import com.alef.assistentea.voice.SpeechListener
import com.alef.assistentea.voice.WakeWordDetector

/**
 * O "cérebro" da Alexa: ouve, entende, executa e responde.
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

    /** Player do YouTube dentro da tela da Alexa (quando a tela está aberta). */
    var embedded: EmbeddedPlayer? = null

    /** O player embutido está com músicas? Então pausar/próxima/etc. vão para ele. */
    private val usingEmbedded: Boolean get() = embedded?.isActive == true

    private fun musicPlaying(): Boolean = (embedded?.isPlaying == true) || media.isMusicPlaying()

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
    private val phoneMic = PhoneMic(context)
    private val wake = WakeWordDetector(context) { text, final -> onWakeText(text, final) }

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
    /** O próximo pedido vem logo depois do "Oi": uma frase solta ("Alcione") vira "tocar Alcione". */
    private var afterGreeting = false

    /** Volta a esperar o "A". */
    private val relisten = Runnable {
        if (continuous && state == State.IDLE && !speech.isListening && !speaker.isSpeaking) {
            if (wake.isReady && !wake.failed) {
                if (!wake.start()) startListening(manual = false)
                else ui?.onState(state, continuous, manualTrigger)
            } else {
                startListening(manual = false) // sem o modelo offline: espera o "A" com o Google
            }
        }
    }

    init {
        phoneMic.ensure()
        // Carrega o reconhecedor offline do "A" em segundo plano
        wake.load { ok -> if (ok && continuous && state == State.IDLE && !speech.isListening) main.post(relisten) }
    }

    private val idleCheck: Runnable = object : Runnable {
        override fun run() {
            if (continuous) return
            if (state == State.IDLE && !speaker.isSpeaking && !speech.isListening) {
                host.onEngineIdle()
            } else {
                main.postDelayed(this, 3000) // ainda ocupada: confere de novo depois
            }
        }
    }

    // ================================================================== comandos externos

    /** Toque no microfone (tela, widget, atalho, botão da notificação). */
    fun tap() {
        wake.stop()
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
            showReply("Escuta contínua ligada. Comece a frase com \"Alexa\". Ex.: \"Alexa, pausar\".")
            main.removeCallbacks(idleCheck)
            if (state == State.IDLE) main.postDelayed(relisten, 300)
        } else {
            main.removeCallbacks(relisten)
            main.removeCallbacks(wakeTrigger)
            wake.stop()
            if (speech.isListening) {
                speech.cancel()
                restoreDuck()
                setState(State.IDLE)
            }
            showReply("Escuta contínua desligada.")
            scheduleIdleCheck()
        }
        ui?.onState(state, continuous, manualTrigger)
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        wake.destroy()
        speech.destroy()
        speaker.shutdown()
        restoreDuck()
    }

    // ================================================================== escuta

    private fun startListening(manual: Boolean) {
        if (!speech.isAvailable()) {
            reply("Reconhecimento de voz indisponível. Instale ou atualize o app Google.", error = true)
            return
        }
        if (speech.isListening) return
        wake.stop()
        if (speaker.isSpeaking) speaker.stop()
        main.removeCallbacks(idleCheck)
        main.removeCallbacks(relisten)
        manualTrigger = manual
        // Abaixa a música para o microfone ouvir melhor
        if (manual && duckedIndex == null && musicPlaying()) {
            duckedIndex = volume.duck(15)
        }
        ui?.onHeard("")
        setState(State.LISTENING)

        phoneMic.ensure() // sempre o microfone do celular, nunca o da caixa
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
        if (continuous) main.postDelayed(relisten, delay) else scheduleIdleCheck()
    }

    // ================================================================== "A" e "Oi"

    /** Disparo do "Oi" logo depois de ouvir só "Alexa" (sem esperar o silêncio). */
    private val wakeTrigger = Runnable {
        if (continuous && state == State.IDLE && wake.isRunning) {
            wake.stop()
            greetAndListen()
        }
    }

    /** Texto ouvido pelo reconhecedor offline enquanto espera "Alexa". */
    private fun onWakeText(text: String, final: Boolean) {
        if (text.isEmpty() && wake.failed) {
            // O reconhecedor offline falhou: continua esperando "Alexa" com o Google
            main.postDelayed(relisten, 500)
            return
        }
        if (!continuous || state != State.IDLE || speech.isListening || speaker.isSpeaking) return
        // Conversa ou música sem o nome: ignora e a música continua normalmente
        val rest = CommandParser.findWake(text) ?: return
        ui?.onHeard("“Alexa”")
        if (rest.isBlank()) {
            // Só "Alexa": responde rápido, sem esperar a pessoa ficar em silêncio
            main.removeCallbacks(wakeTrigger)
            if (final) wakeTrigger.run() else main.postDelayed(wakeTrigger, 350)
            return
        }
        // "Alexa, pausar" de uma vez: espera a frase terminar
        main.removeCallbacks(wakeTrigger)
        if (!final) return
        wake.stop()
        when (val cmd = CommandParser.parse(rest).command) {
            is Command.Play -> greetAndListen("Oi! Qual música?") // nomes de músicas: melhor ouvir com o Google
            is Command.Unknown, Command.WakeOnly -> greetAndListen()
            else -> {
                setState(State.PROCESSING)
                execute(cmd)
            }
        }
    }

    /** "Oi, boa noite!" e escuta o pedido (a música abaixa só agora). */
    private fun greetAndListen(text: String = greetingShort()) {
        main.removeCallbacks(wakeTrigger)
        setState(State.PROCESSING)
        reply(text) {
            afterGreeting = true
            startListening(manual = true)
        }
    }

    private fun greetingShort(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val period = when (h) { in 5..11 -> "Oi, bom dia!"; in 12..17 -> "Oi, boa tarde!"; else -> "Oi, boa noite!" }
        return listOf(period, "Oi!").random()
    }

    // ================================================================== SpeechListener.Callback

    override fun onListeningStarted() {
        if (state != State.LISTENING) setState(State.LISTENING)
    }

    override fun onPartialText(text: String) {
        ui?.onHeard("“$text”")
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
        val greeted = afterGreeting
        afterGreeting = false
        var best = CommandParser.parseBest(alternatives, preferWake = waitingWake)
        ui?.onHeard("“${alternatives.first()}”")
        // Depois do "Oi", um nome solto ("Alcione", "Raça Negra") é pedido de música
        if (greeted && best.command is Command.Unknown) {
            val name = alternatives.first().trim().trimEnd('.', '!', '?')
            if (name.isNotEmpty()) best = best.copy(command = Command.Play(name))
        }

        // Na escuta contínua, só reage se a frase começar com "A" e for um comando
        if (waitingWake && (!best.hadWakeWord || best.command is Command.Unknown)) {
            restoreDuck()
            setState(State.IDLE)
            main.postDelayed(relisten, 250)
            return
        }
        restoreDuck()
        if (best.command == Command.WakeOnly) {
            // "A" sozinho: "Oi!" e escuta o pedido (como a Alexa)
            greetAndListen()
            return
        }
        setState(State.PROCESSING)
        execute(best.command)
    }

    override fun onListenError(message: String, silent: Boolean, code: Int) {
        afterGreeting = false
        restoreDuck()
        setState(State.IDLE)
        if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            showReply("O Android bloqueou o microfone. Abra a Alexa para liberar.", error = true)
            if (continuous) setContinuous(false)
            host.onMicBlocked()
            return
        }
        if (continuous && silent) {
            // Ninguém falou (inclusive depois do "Oi"): volta a esperar o "A", sem falar nada
            if (manualTrigger) showReply("Não ouvi nada. Diga \"Alexa\" quando quiser.")
            main.postDelayed(relisten, 300)
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

            Command.Pause -> { if (usingEmbedded) embedded?.pause() else media.pause(); reply("Pausado.") }
            Command.Stop -> { if (usingEmbedded) embedded?.stop() else media.stop(); reply("Música parada.") }
            Command.Resume -> reply("Continuando.") { if (usingEmbedded) embedded?.resume() else media.play() }
            Command.Next -> reply("Próxima.") { if (usingEmbedded) embedded?.next() else media.next() }
            Command.Previous -> reply("Música anterior.") { if (usingEmbedded) embedded?.previous() else media.previous() }

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
                val e = embedded
                when {
                    e != null && e.isActive && e.title != null -> reply("Está tocando ${e.title}.")
                    t != null -> reply("Está tocando ${t.title}" + (t.artist?.let { ", de $it" } ?: "") + ".")
                    !media.hasNotificationAccess() ->
                        reply("Para eu saber o nome da música, abra a Alexa e toque em \"Permitir mostrar a música atual\".")
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

            Command.WakeOnly -> greetAndListen()

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
        // Repete o pedido antes de tocar: "Tocando Alcione."
        val what = cmd.artist ?: cmd.query
        val before = media.nowPlaying()
        reply("Tocando $what.") {
            if (music.usesYouTube()) {
                playOnYouTube(cmd, what, before)
                return@reply
            }
            // 1º: manda o player (ex.: Spotify) tocar em segundo plano, sem abrir a tela dele
            val player = music.preferredPlayer ?: music.installedPlayers().firstOrNull()
            if (player != null) {
                showReply("Pedindo ao ${player.label}…")
                BackgroundPlayer(context).play(player.packageName, cmd.query, cmd.artist) { ok ->
                    if (ok) {
                        showReply("▶ $what · ${player.label}")
                        ui?.onRefresh()
                    } else {
                        launchPlayer(cmd, what, before) // 2º: abre o app do jeito tradicional
                    }
                }
            } else {
                launchPlayer(cmd, what, before)
            }
        }
    }

    /** Abre o app de música com o pedido; insiste no play e volta para a tela da Alexa quando tocar. */
    private fun launchPlayer(cmd: Command.Play, what: String, before: TrackInfo?) {
        run {
            when (val result = music.play(cmd.query, cmd.artist, media)) {
                is MusicLauncher.Result.Started -> {
                    showReply("▶ $what · ${result.playerLabel}")
                    if (result.viaSession) verifySessionPlayback(cmd, before)
                    // Abriu a tela do player: se não começar sozinho, insiste no play; volta para a Alexa quando tocar
                    if (!result.viaSession) returnToAlexaWhenPlaying(before) {
                        val extras = android.os.Bundle().apply {
                            putString(android.app.SearchManager.QUERY, cmd.query)
                            putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        }
                        if (media.playFromSearch(cmd.query, extras, null) == null) media.play()
                    }
                }
                is MusicLauncher.Result.OpenedSearch ->
                    showReply("Abri a busca no ${result.playerLabel}. Toque na música para começar.")
                MusicLauncher.Result.NoPlayer ->
                    reply("Não encontrei um app de música. Instale o Spotify ou o YouTube Music.", error = true)
            }
        }
    }

    /** YouTube (Morphe): a Alexa pesquisa, toca sem abrir o app se der, ou abre direto no vídeo. */
    private fun playOnYouTube(cmd: Command.Play, what: String, before: TrackInfo?) {
        showReply("Procurando $what no YouTube…")
        val player = embedded
        if (player != null && player.isReady && !prefs.youtubeInApp) {
            // Toca dentro da tela da Alexa, sem abrir o app do YouTube
            val yt = music.youTube()
            val term = music.youTubeTerm(cmd.query, cmd.artist)
            Thread {
                val videos = try { yt.searchMany(term, if (cmd.artist != null) 15 else 8) } catch (_: Exception) { emptyList() }
                main.post {
                    if (videos.isEmpty()) {
                        reply("Não consegui pesquisar no YouTube. Confira a internet do tablet.", error = true)
                    } else {
                        if (media.isMusicPlaying()) media.pause() // para outro player que esteja tocando
                        player.playQueue(videos.map { it.id }, videos.map { it.title })
                        showReply("▶ " + (videos.first().title ?: what))
                    }
                    ui?.onRefresh()
                }
            }.start()
            return
        }
        music.youTube().play(music.youTubeTerm(cmd.query, cmd.artist)) { outcome ->
            when (outcome) {
                YouTubePlayer.Outcome.PlayingInBackground -> showReply("▶ $what · YouTube")
                is YouTubePlayer.Outcome.OpenedVideo -> {
                    showReply("▶ " + (outcome.title ?: what) + " · YouTube")
                    returnToAlexaWhenPlaying(before)
                }
                YouTubePlayer.Outcome.OpenedSearch ->
                    showReply("Não consegui pesquisar (sem internet?). Abri a busca do YouTube.", error = true)
                YouTubePlayer.Outcome.Failed ->
                    reply("Não consegui abrir o YouTube.", error = true)
            }
            ui?.onRefresh()
        }
    }

    /**
     * O YouTube abre por cima da tela. No modo tablet, quando a música já estiver tocando,
     * a tela da Alexa (ondas) volta para a frente e o YouTube (Morphe) continua tocando em segundo plano.
     * Se ainda não estiver tocando (ex.: abriu só a lista de resultados), deixa o YouTube na tela.
     */
    private fun returnToAlexaWhenPlaying(before: TrackInfo?, attempt: Int = 0, retry: (() -> Unit)? = null) {
        if (!prefs.tabletMode) return
        main.postDelayed({
            val now = media.nowPlaying()
            val started = now != null && now.playing && (before == null || now.title != before.title || !before.playing)
            when {
                started -> try {
                    context.startActivity(
                        android.content.Intent(context, com.alef.assistentea.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                } catch (_: Exception) {
                }
                attempt < 12 -> {
                    // O app abriu mas não deu play: pede de novo (já com ele aberto, costuma funcionar)
                    if (attempt == 3 || attempt == 7) retry?.invoke()
                    returnToAlexaWhenPlaying(before, attempt + 1, retry)
                }
                else -> showReply("Se a música não começou, toque nela no app de música.")
            }
        }, if (attempt == 0) 2000L else 1000L)
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
