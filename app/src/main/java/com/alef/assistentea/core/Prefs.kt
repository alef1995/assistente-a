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

    /** Tocar um bipe quando a Zuri for chamada (como a Zuri). */
    var wakeBeep: Boolean
        get() = sp.getBoolean("wake_beep", true)
        set(v) = sp.edit().putBoolean("wake_beep", v).apply()
}
