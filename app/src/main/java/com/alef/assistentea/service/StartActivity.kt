package com.alef.assistentea.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Tela invisível que só liga o serviço da Zuri e fecha.
 *
 * Usada por: atalhos do ícone, botão "assistente" do celular (segurar o botão home/power,
 * se a Zuri for escolhida como app assistente), botão de voz de fones Bluetooth e conexão da caixa.
 * Ligar o serviço com uma tela visível é o que o Android exige para liberar o microfone.
 */
class StartActivity : Activity() {

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Activation.clearTapNotification(this)
    }

    override fun onResume() {
        super.onResume()
        if (launched) return
        launched = true
        val serviceAction = when (intent?.action) {
            AssistantService.ACTION_CONTINUOUS_ON, AssistantService.ACTION_BT_CONNECTED,
            AssistantService.ACTION_TOGGLE_CONTINUOUS, AssistantService.ACTION_LISTEN -> intent.action!!
            else -> AssistantService.ACTION_LISTEN // ASSIST, VOICE_COMMAND, atalho "Falar"
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Sem microfone liberado: abre a tela principal para pedir
            startActivity(Intent(this, com.alef.assistentea.MainActivity::class.java))
            finish()
            return
        }
        AssistantService.start(this, serviceAction)
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }, 400)
    }
}
