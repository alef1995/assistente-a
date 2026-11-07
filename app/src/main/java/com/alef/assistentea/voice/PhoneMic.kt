package com.alef.assistentea.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Garante que a Zuri ouça pelo microfone do próprio celular.
 *
 * O microfone de caixas/fones Bluetooth costuma ser fraco e, quando ativado (modo "chamada"),
 * ainda derruba a qualidade da música. Se algum app (ou uma versão antiga da Zuri) deixou
 * o áudio preso no Bluetooth em modo chamada, isto devolve para o normal.
 * Não mexe em nada durante uma ligação de verdade.
 */
class PhoneMic(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun ensure() {
        try {
            // Ligação normal ou de WhatsApp em andamento: não mexe
            if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoOn) {
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                }
            }
        } catch (_: Exception) {
        }
    }
}
