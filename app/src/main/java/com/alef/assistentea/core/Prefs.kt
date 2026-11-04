package com.alef.assistentea.core

import android.content.Context

/** Preferências do usuário, guardadas no celular. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Ligar a escuta contínua sozinha quando uma caixa/fone Bluetooth conectar. */
    var autoBluetooth: Boolean
        get() = sp.getBoolean("auto_bluetooth", false)
        set(v) = sp.edit().putBoolean("auto_bluetooth", v).apply()

    /** Ouvir pelo microfone da caixa Bluetooth (quando ela tiver um). */
    var speakerMic: Boolean
        get() = sp.getBoolean("speaker_mic", false)
        set(v) = sp.edit().putBoolean("speaker_mic", v).apply()

    /** Tocar um bipe quando a A for chamada (como a Alexa). */
    var wakeBeep: Boolean
        get() = sp.getBoolean("wake_beep", true)
        set(v) = sp.edit().putBoolean("wake_beep", v).apply()
}
