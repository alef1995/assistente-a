package com.alef.assistentea.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.alef.assistentea.core.Prefs
import com.alef.assistentea.MainActivity
import com.alef.assistentea.R
import com.alef.assistentea.core.AssistantEngine
import com.alef.assistentea.widget.MicWidget

/**
 * Serviço em primeiro plano que mantém a Zuri ouvindo mesmo com a tela apagada.
 *
 * O Android exige uma notificação fixa enquanto o microfone é usado em segundo plano —
 * é ela que mostra "Zuri está ouvindo" e tem os botões Falar / Parar.
 * Quando a escuta contínua está desligada, o serviço termina sozinho depois de cada pedido,
 * para não gastar bateria.
 */
class AssistantService : Service(), AssistantEngine.Host {

    companion object {
        const val ACTION_TAP = "com.alef.assistentea.TAP"
        const val ACTION_LISTEN = "com.alef.assistentea.LISTEN"
        const val ACTION_CONTINUOUS_ON = "com.alef.assistentea.CONTINUOUS_ON"
        const val ACTION_CONTINUOUS_OFF = "com.alef.assistentea.CONTINUOUS_OFF"
        const val ACTION_TOGGLE_CONTINUOUS = "com.alef.assistentea.TOGGLE_CONTINUOUS"
        const val ACTION_BT_CONNECTED = "com.alef.assistentea.BT_CONNECTED"
        const val ACTION_BT_DISCONNECTED = "com.alef.assistentea.BT_DISCONNECTED"
        const val ACTION_STOP = "com.alef.assistentea.STOP"

        private const val CHANNEL_RUNNING = "a_running"
        const val CHANNEL_ALERTS = "a_alerts"
        private const val NOTIF_RUNNING = 1
        const val NOTIF_ACTIVATE = 2

        /** Escuta contínua ligada? (para o widget e a tela) */
        @Volatile var continuousOn = false
            private set

        @Volatile var isRunning = false
            private set

        /** Inicia o serviço com uma ação. Retorna false se o Android não permitiu. */
        fun start(context: Context, action: String): Boolean = try {
            context.startForegroundService(Intent(context, AssistantService::class.java).setAction(action))
            true
        } catch (_: Exception) {
            false
        }

        /** Envia uma ação ao serviço que já está rodando (não cria um novo). */
        fun send(context: Context, action: String): Boolean = try {
            context.startService(Intent(context, AssistantService::class.java).setAction(action))
            true
        } catch (_: Exception) {
            false
        }

        /** PendingIntent que inicia o serviço (botões de notificação e widget). */
        fun pending(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getForegroundService(
                context, requestCode,
                Intent(context, AssistantService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, "Zuri ativa", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aparece enquanto a Zuri está ouvindo"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Avisos da Zuri", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Ex.: caixa Bluetooth conectada, toque para ativar a Zuri"
                }
            )
        }
    }

    inner class LocalBinder : Binder() {
        val engine: AssistantEngine get() = this@AssistantService.engine
    }

    private lateinit var engine: AssistantEngine
    private val binder = LocalBinder()
    private var inForeground = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        engine = AssistantEngine(this, this)
        isRunning = true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: o Android reiniciou o serviço depois de fechá-lo.
        // No modo tablet, volta a ficar sempre ouvindo.
        val action = intent?.action
            ?: if (Prefs(this).tabletMode) ACTION_CONTINUOUS_ON else ACTION_TAP
        if (!inForeground && !goForeground()) {
            // Sem permissão para usar o microfone agora: avisa e encerra
            Activation.notifyTapToActivate(this, "Toque para a Zuri começar a ouvir.")
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_TAP -> engine.tap()
            ACTION_LISTEN -> if (engine.state == AssistantEngine.State.IDLE) engine.tap()
            ACTION_CONTINUOUS_ON -> engine.setContinuous(true)
            ACTION_BT_CONNECTED -> engine.setContinuous(true, byBluetooth = true)
            ACTION_CONTINUOUS_OFF -> engine.setContinuous(false)
            ACTION_TOGGLE_CONTINUOUS -> engine.setContinuous(!engine.continuous)
            ACTION_BT_DISCONNECTED -> if (engine.startedByBluetooth) engine.setContinuous(false) else onEngineIdleIfQuiet()
            ACTION_STOP -> {
                engine.setContinuous(false)
                stopEverything()
            }
        }
        return if (engine.continuous) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        isRunning = false
        continuousOn = false
        MicWidget.refreshAll(this)
        engine.destroy()
        super.onDestroy()
    }

    // ================================================================== AssistantEngine.Host

    override fun onEngineIdle() {
        if (!engine.continuous) stopEverything()
    }

    override fun onContinuousChanged(on: Boolean) {
        continuousOn = on
        if (on) acquireWakeLock() else releaseWakeLock()
        if (inForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_RUNNING, buildNotification())
        }
        MicWidget.refreshAll(this)
    }

    override fun onMicBlocked() {
        Activation.notifyTapToActivate(this, "O Android pausou o microfone. Toque para a Zuri voltar a ouvir.")
    }

    /** Acende a tela (mesmo bloqueada) e traz as ondas da Zuri para a frente. */
    override fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "Zuri:tela"
                ).acquire(8000)
            }
            if (Prefs(this).tabletMode) {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun onEngineIdleIfQuiet() {
        if (!engine.continuous && engine.state == AssistantEngine.State.IDLE) stopEverything()
    }

    // ================================================================== mantém o processador acordado

    /** Com a escuta sempre ligada, impede o Android de "adormecer" o processador com a tela apagada. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zuri:escuta").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ================================================================== primeiro plano

    private fun goForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_RUNNING, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_RUNNING, buildNotification())
            }
            inForeground = true
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopEverything() {
        inForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val continuous = this::engine.isInitialized && engine.continuous
        val title = if (continuous) "Zuri está ouvindo" else "Zuri ativa"
        val text = if (continuous) "Diga \"Zuri, …\" — funciona com a tela apagada" else "Fale seu pedido"
        val builder = Notification.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Falar", pending(this, ACTION_TAP, 11)).build())
        if (continuous) {
            builder.addAction(Notification.Action.Builder(null, "Parar de ouvir", pending(this, ACTION_STOP, 12)).build())
        } else {
            builder.addAction(
                Notification.Action.Builder(null, "Ouvir sempre", pending(this, ACTION_CONTINUOUS_ON, 13)).build()
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
