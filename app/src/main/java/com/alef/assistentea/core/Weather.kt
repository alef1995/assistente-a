package com.alef.assistentea.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Previsão do tempo pelo Open-Meteo (gratuito, sem chave, sem cadastro).
 * Usado pela IA como "ferramenta": ela pede o clima de uma cidade e recebe os dados reais.
 * Chame fora da thread principal (usa a internet).
 */
object Weather {

    /** Resumo do clima atual e dos próximos dias, em texto para a IA ler. */
    fun forecast(city: String, days: Int = 3): String {
        val place = geocode(city) ?: return "Não encontrei a cidade \"$city\"."
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=${days.coerceIn(1, 7)}"
        val j = get(url) ?: return "Não consegui consultar a previsão agora."
        val sb = StringBuilder("Cidade: ${place.name}.")
        j.optJSONObject("current")?.let { c ->
            sb.append(" Agora: ${c.optDouble("temperature_2m").toInt()}°C")
            sb.append(" (sensação ${c.optDouble("apparent_temperature").toInt()}°C),")
            sb.append(" ${describe(c.optInt("weather_code"))},")
            sb.append(" umidade ${c.optInt("relative_humidity_2m")}%, vento ${c.optDouble("wind_speed_10m").toInt()} km/h.")
        }
        j.optJSONObject("daily")?.let { d ->
            val dates = d.getJSONArray("time")
            val labels = listOf("Hoje", "Amanhã", "Depois de amanhã")
            for (i in 0 until dates.length()) {
                val label = labels.getOrNull(i) ?: dates.getString(i)
                sb.append(" $label: ${describe(d.getJSONArray("weather_code").optInt(i))},")
                sb.append(" mínima ${d.getJSONArray("temperature_2m_min").optDouble(i).toInt()}°C,")
                sb.append(" máxima ${d.getJSONArray("temperature_2m_max").optDouble(i).toInt()}°C,")
                sb.append(" chance de chuva ${d.getJSONArray("precipitation_probability_max").optInt(i)}%.")
            }
        }
        return sb.toString()
    }

    /** Agora, em poucas palavras para a tela: "32° · garoa". Null se não conseguir. */
    fun nowShort(city: String): String? {
        val place = geocode(city) ?: return null
        val j = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,weather_code&timezone=auto"
        ) ?: return null
        val c = j.optJSONObject("current") ?: return null
        val t = Math.round(c.optDouble("temperature_2m")).toInt()
        return "$t° · ${describe(c.optInt("weather_code"))}"
    }

    private data class Place(val name: String, val lat: Double, val lon: Double)

    private fun geocode(city: String): Place? {
        val j = get(
            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&format=json&name=" +
                URLEncoder.encode(city, "UTF-8")
        ) ?: return null
        val r = j.optJSONArray("results")?.optJSONObject(0) ?: return null
        val name = listOfNotNull(r.optString("name"), r.optString("admin1").takeIf { it.isNotBlank() })
            .joinToString(", ")
        return Place(name, r.getDouble("latitude"), r.getDouble("longitude"))
    }

    private fun get(url: String): JSONObject? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        JSONObject(text)
    } catch (_: Exception) {
        null
    }

    /** Códigos de tempo da OMM → português. */
    private fun describe(code: Int): String = when (code) {
        0 -> "céu limpo"
        1 -> "predominantemente limpo"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "neblina"
        51, 53, 55 -> "garoa"
        56, 57 -> "garoa congelante"
        61 -> "chuva fraca"
        63 -> "chuva moderada"
        65 -> "chuva forte"
        66, 67 -> "chuva congelante"
        71, 73, 75, 77 -> "neve"
        80 -> "pancadas de chuva fracas"
        81 -> "pancadas de chuva"
        82 -> "pancadas de chuva fortes"
        85, 86 -> "pancadas de neve"
        95 -> "tempestade"
        96, 99 -> "tempestade com granizo"
        else -> "tempo variável"
    }
}
