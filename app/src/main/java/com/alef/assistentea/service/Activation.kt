package com.alef.assistentea.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.alef.assistentea.R

/**
 * Liga a A "sozinha" (ex.: quando a caixa Bluetooth conecta).
 *
 * Desde o Android 11, um app em segundo plano não pode começar a usar o microfone por conta própria.
 * As saídas permitidas pelo Android são:
 *  1. Com a permissão "Exibir sobre outros apps", abrir por um instante uma tela invisível
 *     ([StartActivity]) que liga o serviço — totalmente automático;
 *  2. Sem ela, mostrar uma notificação: um toque e a A começa a ouvir.
 */
object Activation {

    fun startFromBackground(context: Context, action: String) {
        if (Build.VERSION.SDK_INT < 30) {
            if (AssistantService.start(context, action)) return
        }
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(
                    Intent(context, StartActivity::class.java)
                        .setAction(action)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
                return
            } catch (_: Exception) {
                // cai para a notificação
            }
        }
        notifyTapToActivate(context, "Caixa conectada. Toque aqui para a A começar a ouvir.", action)
    }

    fun notifyTapToActivate(
        context: Context,
        text: String,
        action: String = AssistantService.ACTION_CONTINUOUS_ON
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        AssistantService.createChannels(context)
        val tap = AssistantService.pending(context, action, 21)
        val n = Notification.Builder(context, AssistantService.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("A")
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .addAction(Notification.Action.Builder(null, "Ativar a A", tap).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(AssistantService.NOTIF_ACTIVATE, n)
    }

    fun clearTapNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(AssistantService.NOTIF_ACTIVATE)
    }
}
