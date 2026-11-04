package com.alef.assistentea.service

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.alef.assistentea.core.Prefs

/**
 * Percebe quando uma caixa de som / fone Bluetooth conecta ou desconecta.
 * Conectou → liga a escuta contínua. Desconectou → desliga, para poupar bateria.
 * Só age se a opção "Ligar sozinha com a caixa Bluetooth" estiver ativada.
 */
class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).autoBluetooth) return
        val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (!isAudioDevice(device)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Espera o áudio da caixa conectar de verdade antes de ligar
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        Activation.startFromBackground(context.applicationContext, AssistantService.ACTION_BT_CONNECTED)
                    } finally {
                        pending.finish()
                    }
                }, 2500)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Activation.clearTapNotification(context)
                if (AssistantService.isRunning) {
                    AssistantService.send(context, AssistantService.ACTION_BT_DISCONNECTED)
                }
            }
        }
    }

    private fun isAudioDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return true
        return try {
            val major = device.bluetoothClass?.majorDeviceClass ?: return true
            major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.UNCATEGORIZED
        } catch (_: SecurityException) {
            true
        }
    }
}
