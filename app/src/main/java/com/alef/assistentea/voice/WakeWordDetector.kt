package com.alef.assistentea.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
/** [onText] recebe o texto ouvido e se é o resultado final da frase (false = parcial, ainda falando). */
class WakeWordDetector(private val context: Context, private val onText: (String, Boolean) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var loading = false

    /** O modelo não pôde ser carregado: a Zuri volta a usar o Google para esperar o "A". */
    var failed = false
        private set

    val isReady: Boolean get() = model != null

    /** Formas do nome que o reconhecedor conhece (para mostrar na tela, se precisar). */
    var wakeForms: List<String> = emptyList()
        private set

    companion object {
        /**
         * Como "Zuri" pode aparecer para um reconhecedor em português.
         * Só as que existirem no vocabulário do modelo são usadas.
         */
        private val CANDIDATES = listOf(
            "zuri", "zury", "zúri", "suri", "júri", "juri", "zurique", "zurich",
            "zu ri", "su ri", "ei zuri", "oi zuri"
        )

        /**
         * Comandos simples que podem vir logo depois do nome, de uma vez só ("Zuri, volume três").
         * Pedidos livres ("tocar Alcione") continuam com o Google, depois do "Oi".
         */
        private val COMMANDS = buildList {
            addAll(listOf(
                "pausar", "pausa", "pausa a música", "pausar a música", "parar", "para", "para a música",
                "parar a música", "stop", "continuar", "continua", "volta a tocar", "próxima", "próxima música",
                "pula", "pular", "pula essa", "anterior", "música anterior", "aumentar", "aumenta",
                "aumentar o volume", "aumenta o volume", "diminuir", "diminui", "diminuir o volume",
                "diminui o volume", "abaixa", "abaixa o volume", "mais alto", "mais baixo", "silenciar",
                "volume máximo", "volume no máximo", "que horas são", "bom dia", "boa tarde", "boa noite",
                "tchau", "obrigado", "obrigada", "liga a lanterna", "desliga a lanterna"
            ))
            val numbers = listOf("um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez")
            numbers.forEach { add("volume $it"); add("volume no $it"); add("coloca o volume no $it") }
        }
    }

    /**
     * Lista de frases para o modo "só estas palavras": o reconhecedor passa a ouvir apenas
     * o nome e marca todo o resto (conversa, música) como desconhecido.
     */
    private fun grammar(): String? {
        val vocab = readVocabulary()
        fun known(phrase: String) = vocab == null || phrase.split(" ").all { it in vocab }
        val forms = CANDIDATES.filter(::known)
        wakeForms = forms
        if (forms.isEmpty()) return null
        val commands = COMMANDS.filter(::known)
        // O nome sozinho, ou o nome seguido de um comando simples
        val phrases = forms + forms.filter { !it.contains(' ') }.flatMap { f -> commands.map { "$f $it" } }
        return JSONArray(phrases + "[unk]").toString()
    }

    /** Palavras que o modelo conhece (arquivo words.txt do modelo), ou null se não achar. */
    private fun readVocabulary(): Set<String>? {
        val dir = File(context.getExternalFilesDir(null), "model")
        val file = listOf("graph/words.txt", "words.txt").map { File(dir, it) }.firstOrNull { it.exists() }
            ?: return null
        return try {
            file.useLines { lines -> lines.map { it.substringBefore(' ') }.toHashSet() }
        } catch (_: Exception) {
            null
        }
    }
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
            val g = grammar()
            val rec = if (g != null) Recognizer(m, 16000.0f, g) else Recognizer(m, 16000.0f)
            grammarMode = g != null
            val s = SpeechService(rec, 16000.0f)
            lastPartial = ""
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
        override fun onPartialResult(hypothesis: String?) = deliver(hypothesis, "partial", false)

        override fun onResult(hypothesis: String?) = deliver(hypothesis, "text", true)

        override fun onFinalResult(hypothesis: String?) = deliver(hypothesis, "text", true)

        override fun onError(exception: Exception?) {
            main.post {
                stop()
                failed = true
                onText("", true)
            }
        }

        override fun onTimeout() {}
    }

    private var lastPartial = ""
    private var grammarMode = false

    private fun deliver(json: String?, key: String, final: Boolean) {
        var text = try {
            JSONObject(json ?: return).optString(key)
        } catch (_: Exception) {
            return
        }
        if (text.isBlank()) return
        // Modo "só o nome (+ comando)": o que não é [unk] é o nome, às vezes seguido de um comando
        if (grammarMode) {
            val words = text.split(" ").filter { it.isNotBlank() && it != "[unk]" }
            if (words.isEmpty()) return
            text = words.joinToString(" ")
        }
        if (!final) {
            if (text == lastPartial) return // o Vosk repete o mesmo parcial várias vezes
            lastPartial = text
        } else {
            lastPartial = ""
        }
        main.post { if (service != null) onText(text, final) }
    }
}
