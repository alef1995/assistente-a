package com.alef.assistentea.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.alef.assistentea.R
import com.alef.assistentea.core.Organizer

/** Hora do lembrete: a Zuri acende a tela e fala. Se ela não estiver rodando, mostra uma notificação. */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val text = Organizer(context).done(id)?.text ?: intent.getStringExtra(EXTRA_TEXT) ?: return

        val spoken = AssistantService.isRunning && try {
            context.startService(
                Intent(context, AssistantService::class.java)
                    .setAction(AssistantService.ACTION_REMINDER)
                    .putExtra(EXTRA_TEXT, text)
            )
            true
        } catch (_: Exception) {
            false
        }

        // Também deixa na notificação (fica registrado mesmo se ninguém ouviu)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        AssistantService.createChannels(context)
        val n = Notification.Builder(context, AssistantService.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Lembrete da Zuri")
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(7000 + id.coerceAtLeast(0), n)
        if (!spoken) android.util.Log.i("Zuri", "Lembrete só por notificação: $text")
    }
}
