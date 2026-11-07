package com.alef.assistentea.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.AlarmClock
import com.alef.assistentea.command.CommandParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Ações do celular que não são de música: hora, data, bateria, timer, despertador, lanterna, abrir apps. */
class DeviceActions(private val context: Context) {

    private val ptBR = Locale.forLanguageTag("pt-BR")

    // ------------------------------------------------------------------ hora / data / bateria

    fun timeSentence(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val hours = when (h) {
            0 -> "É meia-noite"
            1 -> "É uma hora"
            12 -> "É meio-dia"
            else -> "São $h horas"
        }
        return when (m) {
            0 -> "$hours."
            1 -> "$hours e um minuto."
            else -> "$hours e $m minutos."
        }
    }

    fun dateSentence(): String {
        val s = SimpleDateFormat("EEEE, d 'de' MMMM", ptBR).format(Date())
        return "Hoje é $s."
    }

    fun batterySentence(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct <= 0) return "Não consegui ler a bateria."
        val charging = bm.isCharging
        return "A bateria está em $pct por cento" + if (charging) ", carregando." else "."
    }

    // ------------------------------------------------------------------ timer / despertador

    /** Cria um timer no app Relógio, sem abrir a tela dele. */
    fun setTimer(seconds: Int): Boolean = start(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Zuri")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )

    fun setAlarm(hour: Int, minute: Int): Boolean = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Zuri")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )

    fun durationSentence(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = buildList {
            if (h > 0) add(if (h == 1) "uma hora" else "$h horas")
            if (m > 0) add(if (m == 1) "um minuto" else "$m minutos")
            if (s > 0) add(if (s == 1) "um segundo" else "$s segundos")
        }
        return when (parts.size) {
            0 -> "zero segundos"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " e " + parts.last()
        }
    }

    fun clockSentence(hour: Int, minute: Int): String {
        val hh = when (hour) { 0 -> "meia-noite"; 12 -> "meio-dia"; 1 -> "uma hora"; else -> "$hour horas" }
        return if (minute == 0) hh else if (minute == 30) "$hh e meia" else "$hh e $minute"
    }

    // ------------------------------------------------------------------ lanterna

    fun setFlashlight(on: Boolean): Boolean {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ abrir apps

    /** Abre o app cujo nome mais se parece com [name]. Retorna o nome do app aberto, ou null. */
    fun openApp(name: String): String? {
        val wanted = CommandParser.normalize(name.replace(" ", ""))
        if (wanted.isEmpty()) return null
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map {
            it.activityInfo.packageName to it.loadLabel(pm).toString()
        }.filter { it.first != context.packageName }

        fun score(label: String): Int {
            val l = CommandParser.normalize(label.replace(" ", ""))
            return when {
                l == wanted -> 0
                l.startsWith(wanted) || wanted.startsWith(l) -> 1
                l.contains(wanted) -> 2
                CommandParser.distance(l, wanted, 2) <= 2 -> 3
                else -> 99
            }
        }

        val best = apps.map { it to score(it.second) }.filter { it.second < 99 }.minByOrNull { it.second }
            ?: return null
        val (pkg, label) = best.first
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return null
        return if (start(intent)) label else null
    }

    // ------------------------------------------------------------------ conversa

    private val jokes = listOf(
        "Por que o livro de matemática ficou triste? Porque tinha muitos problemas.",
        "O que o zero disse para o oito? Que cinto bonito!",
        "Por que a vaca foi para o espaço? Para ver a via láctea.",
        "Qual é o café mais perigoso do mundo? O ex-presso.",
        "O que o pato disse para a pata? Vem quá!",
        "Por que o celular foi ao médico? Porque estava com vírus.",
        "Qual é o peixe que caiu do décimo andar? Aaaah, tum!",
        "Por que a plantinha não foi atendida no hospital? Porque só tinha médico de plantão."
    )

    fun joke(): String = jokes.random()

    // ------------------------------------------------------------------ utilidades

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null &&
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()
        ) {
            false
        } else {
            context.startActivity(intent)
            true
        }
    } catch (_: Exception) {
        false
    }
}
