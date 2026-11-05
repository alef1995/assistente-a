package com.alef.assistentea.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
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
            "Alexa", "tocar", "pausar", "continuar", "próxima", "anterior", "parar", "volume",
            "aumentar volume", "diminuir volume", "silenciar", "lanterna", "timer", "despertador",
            "que horas são", "bateria", "abrir", "músicas do", "que música é essa"
        )
    }

    private var recognizer: SpeechRecognizer? = null
    private var cancelledByUs = false

    var isListening = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (isListening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Dá um pouco mais de tempo para terminar a frase
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // Android 13+: dá "dicas" ao reconhecedor sobre as palavras que a Alexa espera ouvir,
            // o que reduz trocas como "posar" no lugar de "pausar".
            putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", BIASING)
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
        if (list.isEmpty()) {
            callback.onListenError("Não entendi.", true, SpeechRecognizer.ERROR_NO_MATCH)
        } else {
            callback.onFinalResults(list)
        }
    }

    override fun onError(error: Int) {
        isListening = false
        if (cancelledByUs && error == SpeechRecognizer.ERROR_CLIENT) return
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
        12, 13 -> "Português (Brasil) não está disponível no reconhecimento de voz deste celular." to false
        else -> "Erro no reconhecimento de voz (código $error)." to false
    }
}
