package com.alef.assistentea.core

import android.content.Context

/** Preferências do usuário, guardadas no celular. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Ligar a escuta contínua sozinha quando uma caixa/fone Bluetooth conectar. */
    var autoBluetooth: Boolean
        get() = sp.getBoolean("auto_bluetooth", false)
        set(v) = sp.edit().putBoolean("auto_bluetooth", v).apply()

    /** Modo tablet: tela sempre ligada, Zuri sempre ouvindo e ligando sozinha ao reiniciar. */
    var tabletMode: Boolean
        get() = sp.getBoolean("tablet_mode", true)
        set(v) = sp.edit().putBoolean("tablet_mode", v).apply()

    /** YouTube: tocar no app Morphe (sem propaganda, mas o app aparece) em vez de na tela da Zuri. */
    var youtubeInApp: Boolean
        get() = sp.getBoolean("youtube_in_app", false)
        set(v) = sp.edit().putBoolean("youtube_in_app", v).apply()

    /** Economia de energia: a tela apaga quando ninguém está usando e acende ao chamar a Zuri. */
    var screenSaver: Boolean
        get() = sp.getBoolean("screen_saver", true)
        set(v) = sp.edit().putBoolean("screen_saver", v).apply()

    /** Chave da IA (Groq, plano gratuito). Vazia = sem IA, só os comandos fixos. */
    var aiKey: String
        get() = sp.getString("ai_key", "") ?: ""
        set(v) = sp.edit().putString("ai_key", v.trim()).apply()

    /** Modelo de IA aberto usado no Groq. Vazio = padrão (gpt-oss-120b). */
    var aiModel: String
        get() = sp.getString("ai_model", "") ?: ""
        set(v) = sp.edit().putString("ai_model", v.trim()).apply()

    /** Cidade da casa (para a previsão do tempo). */
    var city: String
        get() = sp.getString("city", "") ?: ""
        set(v) = sp.edit().putString("city", v.trim()).apply()

    /** Música da rotina de bom dia (vazio = sem música). */
    var morningMusic: String
        get() = sp.getString("morning_music", "") ?: ""
        set(v) = sp.edit().putString("morning_music", v.trim()).apply()

    /** Tocar um bipe quando a Zuri for chamada (como a Zuri). */
    var wakeBeep: Boolean
        get() = sp.getBoolean("wake_beep", true)
        set(v) = sp.edit().putBoolean("wake_beep", v).apply()

    /** Sensibilidade do nome: 0 = baixa (quase nunca dispara sozinha), 1 = normal, 2 = alta (entende de longe). */
    var wakeSensitivity: Int
        get() = sp.getInt("wake_sensitivity", 1)
        set(v) = sp.edit().putInt("wake_sensitivity", v.coerceIn(0, 2)).apply()
}
