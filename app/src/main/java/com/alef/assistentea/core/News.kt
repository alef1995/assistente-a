package com.alef.assistentea.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Manchetes do Google Notícias (feed RSS público, em português do Brasil).
 * Serve para notícias do dia e resultados de jogos ("Cruzeiro", "jogo do Atlético").
 * A IA recebe só os títulos e resume em uma ou duas frases.
 * Chame fora da thread principal (usa a internet).
 */
object News {

    fun headlines(topic: String?, max: Int = 8): String {
        val url = if (topic.isNullOrBlank()) {
            "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419"
        } else {
            "https://news.google.com/rss/search?q=" + URLEncoder.encode("$topic when:3d", "UTF-8") +
                "&hl=pt-BR&gl=BR&ceid=BR:pt-419"
        }
        val xml = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Zuri")
            }
            val t = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            t
        } catch (_: Exception) {
            return "Não consegui buscar as notícias agora."
        }

        val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(max).map { m ->
            val body = m.groupValues[1]
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                ?.let(::unescape) ?: ""
            val date = Regex("<pubDate>(.*?)</pubDate>").find(body)?.groupValues?.get(1)?.let(::shortDate) ?: ""
            "- $title" + if (date.isNotBlank()) " ($date)" else ""
        }.toList()

        if (items.isEmpty()) return "Nenhuma notícia encontrada sobre isso."
        return (if (topic.isNullOrBlank()) "Principais manchetes:\n" else "Manchetes sobre \"$topic\":\n") +
            items.joinToString("\n")
    }

    private fun unescape(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .trim()

    private fun shortDate(rfc: String): String = try {
        val d = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH).parse(rfc)
        SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("pt-BR")).format(d!!)
    } catch (_: Exception) {
        ""
    }
}
