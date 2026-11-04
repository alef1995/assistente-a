package com.alef.assistentea.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Respostas faladas com o Text-to-Speech do próprio Android (voz pt-BR).
 *
 * Enquanto fala, pede "foco de áudio com redução" (duck): a música continua,
 * mais baixa, e volta ao volume normal sozinha quando a fala termina.
 * A saída de áudio é a que o Android escolher — inclusive a caixa Bluetooth.
 */
class Speaker(context: Context, private val listener: Listener) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onSpeakStart()
        fun onSpeakDone()
        fun onTtsProblem(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private var ready = false
    private var failed = false
    private val pending = ArrayList<Pair<String, (() -> Unit)?>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val counter = AtomicInteger(0)

    // Criado por último: o callback onInit usa os campos acima.
    private val tts = TextToSpeech(context.applicationContext, this)

    @Volatile var isSpeaking = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            listener.onTtsProblem("Não consegui iniciar a voz do Android. Instale ou ative o \"Serviços de voz do Google\".")
            flushPendingWithoutVoice()
            return
        }
        val result = tts.setLanguage(Locale.forLanguageTag("pt-BR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            listener.onTtsProblem("Voz em português não instalada. Vá em Configurações › Saída de texto para fala e baixe Português (Brasil).")
        }
        tts.setAudioAttributes(attributes)
        tts.setSpeechRate(1.05f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { isSpeaking = true; listener.onSpeakStart() }
            }

            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
        ready = true
        val copy = ArrayList(pending)
        pending.clear()
        copy.forEach { (text, cb) -> speak(text, cb) }
    }

    /** Fala o texto e, quando terminar, executa [onDone] (na thread principal). */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (failed) {
            main.post { listener.onSpeakDone(); onDone?.invoke() }
            return
        }
        if (!ready) {
            pending.add(text to onDone)
            return
        }
        val id = "a-${counter.incrementAndGet()}"
        if (onDone != null) callbacks[id] = onDone
        audioManager.requestAudioFocus(focusRequest)
        val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) finish(id)
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun finish(utteranceId: String?) {
        val cb = utteranceId?.let { callbacks.remove(it) }
        main.post {
            isSpeaking = false
            audioManager.abandonAudioFocusRequest(focusRequest)
            listener.onSpeakDone()
            cb?.invoke()
        }
    }

    private fun flushPendingWithoutVoice() {
        val copy = ArrayList(pending)
        pending.clear()
        copy.forEach { (_, cb) -> main.post { listener.onSpeakDone(); cb?.invoke() } }
    }
}
