package com.alef.assistentea.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Usa o microfone da caixa/fone Bluetooth (perfil de chamada, "SCO") em vez do microfone do celular.
 *
 * Limitação física do Bluetooth: enquanto o microfone da caixa está ligado, o áudio vai pelo
 * canal de chamada, com qualidade menor. Por isso é uma opção que o usuário liga se quiser.
 * Nem toda caixa tem microfone compatível — nesse caso [acquire] devolve false.
 */
class BluetoothMic(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    var isActive = false
        private set
    private var previousMode = AudioManager.MODE_NORMAL

    /** Existe um microfone Bluetooth disponível agora? */
    fun isAvailable(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoAvailableOffCall &&
                am.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Liga o microfone da caixa. [onReady] é chamado quando a conexão estiver pronta
     * (leva cerca de 1 segundo). Retorna false se não houver microfone Bluetooth.
     */
    fun acquire(onReady: () -> Unit): Boolean {
        if (isActive) {
            onReady()
            return true
        }
        if (!isAvailable()) return false
        try {
            previousMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (dev == null || !am.setCommunicationDevice(dev)) {
                    am.mode = previousMode
                    return false
                }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
            }
        } catch (_: Exception) {
            safeRestore()
            return false
        }
        isActive = true
        main.postDelayed(onReady, 1200)
        return true
    }

    /** Volta para o microfone do celular e para o áudio normal (alta qualidade). */
    fun release() {
        if (!isActive) return
        isActive = false
        safeRestore()
    }

    private fun safeRestore() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
            am.mode = if (previousMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else previousMode
        } catch (_: Exception) {
        }
    }
}
