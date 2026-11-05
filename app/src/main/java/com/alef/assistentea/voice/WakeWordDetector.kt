package com.alef.assistentea.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Espera a palavra "A" com um reconhecedor de voz OFFLINE (Vosk, modelo pequeno em português),
 * que roda no próprio celular.
 *
 * Por que não usar o reconhecedor do Google o tempo todo? Ele precisa ser religado a cada
 * poucos segundos e, em muitos aparelhos, cada religada mexe no áudio (bipe, música abaixando) —
 * eram os "engasgos". O Vosk lê o microfone direto e não interfere na música.
 *
 * Ele só serve para perceber o "A". O pedido em si continua com o Google, que entende melhor
 * nomes de músicas e artistas.
 */
class WakeWordDetector(private val context: Context, private val onText: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var loading = false

    /** O modelo não pôde ser carregado: a A volta a usar o Google para esperar o "A". */
    var failed = false
        private set

    val isReady: Boolean get() = model != null
    val isRunning: Boolean get() = service != null

    /** Prepara o modelo (leva alguns segundos na primeira vez). */
    fun load(onDone: (Boolean) -> Unit) {
        if (model != null) { onDone(true); return }
        if (loading || failed) { if (failed) onDone(false); return }
        loading = true
        try {
            StorageService.unpack(context, "model-pt", "model",
                { m: Model ->
                    model = m
                    loading = false
                    onDone(true)
                },
                { _: java.io.IOException ->
                    loading = false
                    failed = true
                    onDone(false)
                })
        } catch (_: Throwable) {
            loading = false
            failed = true
            onDone(false)
        }
    }

    /** Começa a esperar o "A". Retorna false se não for possível. */
    fun start(): Boolean {
        if (service != null) return true
        val m = model ?: return false
        return try {
            val rec = Recognizer(m, 16000.0f)
            val s = SpeechService(rec, 16000.0f)
            s.startListening(listener)
            recognizer = rec
            service = s
            true
        } catch (_: Throwable) {
            stop()
            false
        }
    }

    /** Para de ouvir e libera o microfone (para o Google ouvir o pedido). */
    fun stop() {
        val s = service
        service = null
        try { s?.stop() } catch (_: Throwable) {}
        try { s?.shutdown() } catch (_: Throwable) {}
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
    }

    fun destroy() {
        stop()
        try { model?.close() } catch (_: Throwable) {}
        model = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}

        override fun onResult(hypothesis: String?) = deliver(hypothesis)

        override fun onFinalResult(hypothesis: String?) = deliver(hypothesis)

        override fun onError(exception: Exception?) {
            main.post {
                stop()
                failed = true
                onText("")
            }
        }

        override fun onTimeout() {}
    }

    private fun deliver(json: String?) {
        val text = try {
            JSONObject(json ?: return).optString("text")
        } catch (_: Exception) {
            return
        }
        if (text.isBlank()) return
        main.post { if (service != null) onText(text) }
    }
}
