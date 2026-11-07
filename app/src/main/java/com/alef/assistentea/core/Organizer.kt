package com.alef.assistentea.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.alef.assistentea.service.ReminderReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lista de compras e lembretes da Zuri, guardados no próprio tablet.
 * Os lembretes usam o despertador do Android: na hora, a tela acende e a Zuri fala.
 */
class Organizer(context: Context) {

    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("organizer", Context.MODE_PRIVATE)
    private val ptBR = Locale.forLanguageTag("pt-BR")

    // ================================================================== lista de compras

    fun list(): List<String> {
        val arr = JSONArray(sp.getString("shopping", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun saveList(items: List<String>) = sp.edit().putString("shopping", JSONArray(items).toString()).apply()

    fun addToList(items: List<String>): String {
        val current = list().toMutableList()
        val added = items.map { it.trim() }.filter { it.isNotBlank() }
            .filter { new -> current.none { it.equals(new, ignoreCase = true) } }
        current += added
        saveList(current)
        return if (added.isEmpty()) "Esses itens já estavam na lista. A lista tem ${current.size} itens."
        else "Adicionado: ${added.joinToString(", ")}. A lista agora tem ${current.size} itens."
    }

    fun removeFromList(items: List<String>): String {
        val current = list().toMutableList()
        val removed = current.filter { have -> items.any { have.contains(it.trim(), ignoreCase = true) } }
        current.removeAll(removed)
        saveList(current)
        return if (removed.isEmpty()) "Nenhum desses itens estava na lista." else "Removido: ${removed.joinToString(", ")}."
    }

    fun clearList(): String {
        saveList(emptyList())
        return "Lista de compras apagada."
    }

    fun describeList(): String {
        val l = list()
        return if (l.isEmpty()) "A lista de compras está vazia." else "Lista de compras (${l.size}): ${l.joinToString(", ")}."
    }

    // ================================================================== lembretes

    data class Reminder(val id: Int, val text: String, val at: Long)

    fun reminders(): List<Reminder> {
        val arr = JSONArray(sp.getString("reminders", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Reminder(o.getInt("id"), o.getString("text"), o.getLong("at"))
        }.sortedBy { it.at }
    }

    private fun saveReminders(list: List<Reminder>) = sp.edit().putString(
        "reminders",
        JSONArray(list.map { JSONObject().put("id", it.id).put("text", it.text).put("at", it.at) }).toString()
    ).apply()

    /** Cria um lembrete para [at] (milissegundos). */
    fun addReminder(text: String, at: Long): String {
        if (at <= System.currentTimeMillis()) return "Esse horário já passou. Peça outro horário."
        val id = (sp.getInt("next_id", 1)).also { sp.edit().putInt("next_id", it + 1).apply() }
        val r = Reminder(id, text.trim(), at)
        saveReminders(reminders() + r)
        schedule(r)
        return "Lembrete criado: \"${r.text}\" ${whenText(at)}."
    }

    fun cancelReminder(match: String): String {
        val all = reminders()
        val gone = all.filter { match.isBlank() || it.text.contains(match, ignoreCase = true) }
        gone.forEach { unschedule(it) }
        saveReminders(all - gone.toSet())
        return if (gone.isEmpty()) "Não achei esse lembrete." else "Cancelado: ${gone.joinToString(", ") { it.text }}."
    }

    fun describeReminders(): String {
        val now = System.currentTimeMillis()
        val l = reminders().filter { it.at > now }
        return if (l.isEmpty()) "Não há lembretes marcados."
        else "Lembretes: " + l.joinToString("; ") { "${it.text} ${whenText(it.at)}" } + "."
    }

    /** Lembretes de hoje que ainda vão acontecer (para a rotina de bom dia). */
    fun todayReminders(): List<Reminder> {
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        }.timeInMillis
        val now = System.currentTimeMillis()
        return reminders().filter { it.at in now..end }
    }

    /** Chamado quando o lembrete toca: tira da lista. */
    fun done(id: Int): Reminder? {
        val all = reminders()
        val r = all.firstOrNull { it.id == id }
        saveReminders(all.filter { it.id != id })
        return r
    }

    /** Depois de reiniciar o tablet, o Android esquece os alarmes: agenda de novo. */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val (future, past) = reminders().partition { it.at > now }
        future.forEach { schedule(it) }
        if (past.isNotEmpty()) saveReminders(future)
    }

    fun canBeExact(): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    private fun pending(r: Reminder): PendingIntent = PendingIntent.getBroadcast(
        ctx, 5000 + r.id,
        Intent(ctx, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_ID, r.id)
            .putExtra(ReminderReceiver.EXTRA_TEXT, r.text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun schedule(r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (canBeExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(r))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(r)) // pode atrasar alguns minutos
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(r))
        }
    }

    private fun unschedule(r: Reminder) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(r))
    }

    /** "hoje às 18:00", "amanhã às 7:30", "em 12/11 às 9:00". */
    fun whenText(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        val hour = SimpleDateFormat("HH:mm", ptBR).format(Date(at))
        val dayDiff = ((startOfDay(c) - startOfDay(today)) / 86_400_000L).toInt()
        return when (dayDiff) {
            0 -> "hoje às $hour"
            1 -> "amanhã às $hour"
            else -> "em ${SimpleDateFormat("dd/MM", ptBR).format(Date(at))} às $hour"
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** "2026-11-07T18:30" (hora local) → milissegundos. */
    fun parseLocal(iso: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT).parse(iso.take(16))?.time
    } catch (_: Exception) {
        null
    }
}
