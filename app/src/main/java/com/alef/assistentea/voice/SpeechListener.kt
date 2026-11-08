package com.alef.assistentea.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Reconhecimento de voz usando o serviço do próprio Android (normalmente o do Google),
 * configurado para português do Brasil.
 *
 * Deve ser usado sempre na thread principal.
 */
class SpeechListener(private val context: Context, private val callback: Callback) : RecognitionListener {

    interface Callback {
        fun onListeningStarted()
        fun onPartialText(text: String)
        fun onFinalResults(alternatives: List<String>)
        /** [silent] = erro "normal" (ninguém falou), que não precisa ser anunciado.
         *  [code] = código de erro do SpeechRecognizer (ou -1). */
        fun onListenError(message: String, silent: Boolean, code: Int)
        fun onVoiceLevel(rmsDb: Float)
    }

    companion object {
        private val BIASING = arrayListOf(
            "Zuri", "stop", "volume 1", "volume 5", "volume 10", "tocar", "pausar", "pausar música", "continuar", "próxima", "anterior", "parar", "volume",
            "aumentar volume", "diminuir volume", "silenciar", "lanterna", "timer", "despertador",
            "que horas são", "bateria", "abrir", "músicas do", "que música é essa"
        )
    }

    private var recognizer: SpeechRecognizer? = null
    private var cancelledByUs = false

    /** Uma forma de pedir o reconhecimento: qual serviço e como informar o idioma. */
    private data class Attempt(val service: ComponentName?, val onDevice: Boolean, val lang: String?, val biasing: Boolean)

    private val attempts: List<Attempt> by lazy { buildAttempts() }
    private var attemptIndex = 0
    private var currentKey: String? = null
    private var fallbacksThisTurn = 0

    private fun key(a: Attempt) = "${a.service?.flattenToString()}|${a.onDevice}|${a.lang}|${a.biasing}"

    private fun buildAttempts(): List<Attempt> {
        val services = try {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        } catch (_: Exception) { emptyList() }
        // Prefere o app Google e depois o "Serviços de voz do Google"
        val ordered = services.sortedBy {
            when (it.packageName) {
                "com.google.android.googlequicksearchbox" -> 0
                "com.google.android.tts" -> 1
                else -> 2
            }
        }
        val list = mutableListOf<Attempt>()
        list += Attempt(null, false, "pt-BR", true)
        list += Attempt(null, false, "pt-BR", false)
        for (c in ordered) {
            list += Attempt(c, false, "pt-BR", false)
            list += Attempt(c, false, "pt_BR", false)
        }
        if (Build.VERSION.SDK_INT >= 31) list += Attempt(null, true, "pt-BR", false)
        list += Attempt(null, false, "pt", false)
        list += Attempt(null, false, null, false)   // idioma padrão do aparelho
        val saved = context.getSharedPreferences("speech", Context.MODE_PRIVATE).getString("attempt", null)
        val idx = list.indexOfFirst { key(it) == saved }
        if (idx > 0) list.add(0, list.removeAt(idx))
        return list.distinctBy { key(it) }
    }

    private fun createRecognizer(a: Attempt): SpeechRecognizer = when {
        a.onDevice && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        a.service != null -> SpeechRecognizer.createSpeechRecognizer(context, a.service)
        else -> SpeechRecognizer.createSpeechRecognizer(context)
    }

    var isListening = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (isListening) return
        fallbacksThisTurn = 0
        startAttempt()
    }

    private fun startAttempt() {
        val a = attempts[attemptIndex.coerceIn(0, attempts.size - 1)]
        val k = key(a)
        if (recognizer != null && currentKey != k) { recognizer?.destroy(); recognizer = null }
        val r = recognizer ?: try {
            createRecognizer(a).also {
                it.setRecognitionListener(this)
                recognizer = it
                currentKey = k
            }
        } catch (e: Exception) {
            if (tryNextAttempt()) return
            callback.onListenError("Não consegui abrir o reconhecimento de voz: ${e.message}", false, -1)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (a.lang != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, a.lang)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Dá um pouco mais de tempo para terminar a frase
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            if (!a.onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // Android 13+: dá "dicas" ao reconhecedor sobre as palavras que a Zuri espera ouvir,
            // o que reduz trocas como "posar" no lugar de "pausar".
            if (a.biasing) putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", BIASING)
        }
        cancelledByUs = false
        isListening = true
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            callback.onListenError("Não consegui abrir o microfone: ${e.message}", false, -1)
        }
    }

    /** Para de ouvir e processa o que já foi dito. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Cancela sem processar. */
    fun cancel() {
        if (!isListening) return
        cancelledByUs = true
        isListening = false
        recognizer?.cancel()
    }

    fun destroy() {
        isListening = false
        recognizer?.destroy()
        recognizer = null
    }

    // ---------------------------------------------------------------- RecognitionListener

    /** Passa para a próxima forma de reconhecimento. Retorna false se acabaram as opções. */
    private fun tryNextAttempt(): Boolean {
        if (fallbacksThisTurn >= attempts.size - 1) return false
        fallbacksThisTurn++
        attemptIndex = (attemptIndex + 1) % attempts.size
        recognizer?.destroy(); recognizer = null
        isListening = true   // continua "ouvindo" enquanto troca de método (cancel() funciona)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!cancelledByUs) startAttempt() }, 150)
        return true
    }

    private fun rememberWorkingAttempt() {
        val k = currentKey ?: return
        context.getSharedPreferences("speech", Context.MODE_PRIVATE).edit().putString("attempt", k).apply()
    }

    override fun onReadyForSpeech(params: Bundle?) = callback.onListeningStarted()
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) = callback.onVoiceLevel(rmsdB)
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) callback.onPartialText(text)
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (list.isNotEmpty()) rememberWorkingAttempt()
        if (list.isEmpty()) {
            callback.onListenError("Não entendi.", true, SpeechRecognizer.ERROR_NO_MATCH)
        } else {
            callback.onFinalResults(list)
        }
    }

    override fun onError(error: Int) {
        isListening = false
        if (cancelledByUs && error == SpeechRecognizer.ERROR_CLIENT) return
        // 12 = idioma não suportado, 13 = idioma indisponível: tenta outra forma sozinha
        if ((error == 12 || error == 13) && tryNextAttempt()) return
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            // Recria o reconhecedor na próxima vez
            recognizer?.destroy()
            recognizer = null
        }
        val (msg, silent) = describe(error)
        callback.onListenError(msg, silent, error)
    }

    private fun describe(error: Int): Pair<String, Boolean> = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi. Pode repetir?" to true
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada." to true
        SpeechRecognizer.ERROR_AUDIO -> "Problema ao gravar o áudio do microfone." to false
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão de microfone." to false
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Sem conexão com a internet para reconhecer a voz." to false
        SpeechRecognizer.ERROR_SERVER -> "O serviço de reconhecimento de voz falhou. Tente de novo." to false
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecimento de voz está ocupado. Tente de novo." to false
        SpeechRecognizer.ERROR_CLIENT -> "Reconhecimento interrompido." to true
        10 -> "Muitas solicitações seguidas. Aguarde um instante." to false        // ERROR_TOO_MANY_REQUESTS
        11 -> "O serviço de voz desconectou. Tente de novo." to false             // ERROR_SERVER_DISCONNECTED
        12, 13 -> "O reconhecimento de voz recusou o português. Abra o app Google › Configurações › Voz › Idiomas e escolha Português (Brasil); em Reconhecimento offline, baixe Português." to false
        else -> "Erro no reconhecimento de voz (código $error)." to false
    }
}
