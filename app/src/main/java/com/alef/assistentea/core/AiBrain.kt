package com.alef.assistentea.core

import android.os.Handler
import android.os.Looper
import com.alef.assistentea.command.Command
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O "cérebro" de IA da Zuri.
 *
 * Usa um modelo de linguagem ABERTO (por padrão o gpt-oss, da OpenAI, de código aberto) rodando
 * no Groq, que tem plano gratuito e responde muito rápido. A IA conversa, responde perguntas e,
 * quando o pedido é uma ação ("bota umas músicas animadas"), devolve a ação certa (uma "ferramenta"),
 * que a Zuri executa como se fosse um comando falado.
 *
 * Os comandos simples ("pausar", "volume 5") NÃO passam por aqui: continuam instantâneos e offline.
 */
class AiBrain(private val prefs: Prefs) {

    /** Resposta da IA: um texto para falar e/ou uma ação para executar. */
    data class Answer(val text: String?, val command: Command?)

    private val main = Handler(Looper.getMainLooper())
    private val history = mutableListOf<JSONObject>()
    private var lastUse = 0L

    /** Serviço de IA, descoberto pelo começo da chave que o usuário colou. */
    data class Provider(val name: String, val url: String, val model: String)

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"

        /** gsk_ = Groq · sk-or- = OpenRouter · AIza = Google (Gemini). Todos com plano grátis. */
        fun providerFor(key: String): Provider = when {
            key.startsWith("sk-or-") -> Provider(
                "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free"
            )
            key.startsWith("AIza") -> Provider(
                "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash"
            )
            else -> Provider("Groq", "https://api.groq.com/openai/v1/chat/completions", DEFAULT_MODEL)
        }
        private const val MEMORY_MS = 5 * 60 * 1000L   // lembra da conversa por 5 minutos
        private const val MAX_HISTORY = 12
    }

    val isConfigured: Boolean get() = prefs.aiKey.isNotBlank()

    fun forget() = history.clear()

    /**
     * Pergunta à IA. [context] = situação atual (o que está tocando, volume...).
     * [onDone] roda na thread principal; recebe null se der erro (sem internet, chave inválida...).
     */
    fun ask(userText: String, context: String, onDone: (Answer?, String?) -> Unit) {
        if (System.currentTimeMillis() - lastUse > MEMORY_MS) history.clear()
        lastUse = System.currentTimeMillis()
        history.add(JSONObject().put("role", "user").put("content", userText))
        while (history.size > MAX_HISTORY) history.removeAt(0)
        val snapshot = history.toList()

        Thread {
            var error: String? = null
            val answer = try {
                call(snapshot, context)
            } catch (e: Exception) {
                error = e.message
                null
            }
            main.post {
                if (answer != null) {
                    // Guarda a resposta na memória da conversa
                    val said = answer.text ?: answer.command?.let { "[ação: ${it.javaClass.simpleName}]" } ?: ""
                    history.add(JSONObject().put("role", "assistant").put("content", said))
                } else {
                    history.removeLastOrNull()
                }
                onDone(answer, error)
            }
        }.start()
    }

    // ------------------------------------------------------------------ chamada à IA

    private fun call(messages: List<JSONObject>, context: String): Answer {
        val provider = providerFor(prefs.aiKey.trim())
        val model = prefs.aiModel.ifBlank { provider.model }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt(context)))
                messages.forEach { put(it) }
            })
            put("tools", tools())
            put("tool_choice", "auto")
            put("temperature", 0.6)
            put("max_completion_tokens", 400)
            if (model.startsWith("openai/gpt-oss")) put("reasoning_effort", "low") // mais rápido
        }

        val conn = (URL(provider.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + prefs.aiKey.trim())
            if (provider.name == "OpenRouter") setRequestProperty("X-Title", "Zuri")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code == 401) throw IllegalStateException("chave inválida (${provider.name})")
        if (code == 429) throw IllegalStateException("limite grátis do ${provider.name} atingido, tente daqui a pouco")
        if (code !in 200..299) {
            val detail = try {
                JSONObject(text).optJSONObject("error")?.optString("message")
            } catch (_: Exception) { null }
            if (code == 403 && detail?.contains("network", true) == true)
                throw IllegalStateException("o ${provider.name} bloqueou a internet desta casa (VPN ou rede). Tente outra IA")
            throw IllegalStateException("erro $code do ${provider.name}" + (detail?.let { ": ${it.take(120)}" } ?: ""))
        }

        val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val content = msg.optString("content", "").takeIf { it.isNotBlank() && it != "null" }?.let(::clean)
        val calls = msg.optJSONArray("tool_calls")
        val command = if (calls != null && calls.length() > 0) {
            val f = calls.getJSONObject(0).getJSONObject("function")
            toCommand(f.getString("name"), JSONObject(f.optString("arguments").ifBlank { "{}" }))
        } else null
        return Answer(content, command)
    }

    private fun systemPrompt(context: String): String {
        val now = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("pt-BR")).format(Date())
        return """
Você é a Zuri, assistente de voz que fica num tablet na casa de uma família no Brasil.
Fale sempre em português do Brasil, de forma simpática, natural e BREVE: no máximo 2 frases curtas,
porque sua resposta será falada em voz alta. Nunca use markdown, listas, asteriscos, emojis ou links.
Quando o pedido for uma ação (tocar música, pausar, volume, timer, despertador, lanterna, abrir app),
use a ferramenta certa em vez de responder com texto. Se a pessoa disser só um nome de artista ou
música, toque. Para músicas por estilo ou clima ("algo animado", "pra relaxar"), escolha um termo de
busca bom (ex.: "pagode anos 90", "músicas relaxantes MPB"). Volume vai de 1 (mais baixo) a 10 (máximo).
Se não souber algo atual (notícias, resultados), diga com franqueza que não tem acesso a isso agora.
Agora: $now.
Situação: $context
""".trimIndent()
    }

    /** Tira formatação que não faz sentido em voz alta. */
    private fun clean(s: String): String =
        s.replace(Regex("[*_#`>]"), "").replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------------ ferramentas (ações)

    private fun tool(name: String, description: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required)))
        })

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    private fun tools(): JSONArray = JSONArray().apply {
        put(tool("play_music", "Tocar música, artista, álbum, playlist ou estilo.",
            JSONObject()
                .put("query", prop("string", "O que buscar, ex.: 'Evidências', 'pagode anos 90'"))
                .put("artist", prop("string", "Nome do artista, se o pedido for por artista")),
            listOf("query")))
        put(tool("pause_music", "Pausar a música."))
        put(tool("resume_music", "Continuar a música pausada."))
        put(tool("stop_music", "Parar a música de vez."))
        put(tool("next_track", "Pular para a próxima música."))
        put(tool("previous_track", "Voltar para a música anterior."))
        put(tool("set_volume", "Mudar o volume para um nível de 1 (mais baixo) a 10 (máximo).",
            JSONObject().put("level", prop("integer", "Nível de 1 a 10")), listOf("level")))
        put(tool("volume_up", "Aumentar um pouco o volume."))
        put(tool("volume_down", "Diminuir um pouco o volume."))
        put(tool("mute", "Silenciar o som."))
        put(tool("set_timer", "Criar um timer.",
            JSONObject().put("seconds", prop("integer", "Duração em segundos")), listOf("seconds")))
        put(tool("set_alarm", "Criar um despertador.",
            JSONObject().put("hour", prop("integer", "Hora (0-23)")).put("minute", prop("integer", "Minuto (0-59)")),
            listOf("hour", "minute")))
        put(tool("flashlight", "Ligar ou desligar a lanterna.",
            JSONObject().put("on", prop("boolean", "true = ligar, false = desligar")), listOf("on")))
        put(tool("open_app", "Abrir um aplicativo do tablet.",
            JSONObject().put("name", prop("string", "Nome do app, ex.: WhatsApp")), listOf("name")))
        put(tool("stop_listening", "Parar de ouvir (a pessoa pediu para a Zuri desligar a escuta)."))
    }

    private fun toCommand(name: String, a: JSONObject): Command? = when (name) {
        "play_music" -> {
            val q = a.optString("query").ifBlank { a.optString("artist") }
            val artist = a.optString("artist").takeIf { it.isNotBlank() && it != "null" }
            if (q.isBlank()) null else Command.Play(q, artist)
        }
        "pause_music" -> Command.Pause
        "resume_music" -> Command.Resume
        "stop_music" -> Command.Stop
        "next_track" -> Command.Next
        "previous_track" -> Command.Previous
        "set_volume" -> Command.SetVolume((a.optInt("level", 5).coerceIn(0, 10)) * 10)
        "volume_up" -> Command.VolumeUp
        "volume_down" -> Command.VolumeDown
        "mute" -> Command.Mute
        "set_timer" -> Command.Timer(a.optInt("seconds", 0))
        "set_alarm" -> Command.Alarm(a.optInt("hour", -1), a.optInt("minute", 0))
        "flashlight" -> Command.Flashlight(a.optBoolean("on", true))
        "open_app" -> a.optString("name").takeIf { it.isNotBlank() }?.let { Command.OpenApp(it) }
        "stop_listening" -> Command.StopListening
        else -> null
    }
}
