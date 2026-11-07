package com.alef.assistentea.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.alef.assistentea.MainActivity
import com.alef.assistentea.core.Prefs

/**
 * Modo tablet: quando o tablet liga (ou o app é atualizado), abre a Zuri sozinha,
 * já ouvindo. Para abrir sem toque, o Android exige a permissão "exibir sobre outros apps";
 * sem ela, aparece uma notificação para tocar.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {}
            else -> return
        }
        // O Android esquece os alarmes ao reiniciar: agenda os lembretes de novo
        com.alef.assistentea.core.Organizer(context).rescheduleAll()
        if (!Prefs(context).tabletMode) return
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {
            }
        }
        Activation.notifyTapToActivate(context, "Toque para a Zuri voltar a ouvir.")
    }
}
