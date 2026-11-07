package com.alef.assistentea.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.alef.assistentea.service.UpdateReceiver
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização pelo próprio app: confere no GitHub se há versão nova e instala por cima,
 * sem desinstalar (o Android só pede a confirmação "Atualizar").
 */
class Updater(private val context: Context) {

    data class Release(val version: Int, val apkUrl: String, val size: Long)

    private val main = Handler(Looper.getMainLooper())

    companion object {
        private const val API = "https://api.github.com/repos/alef1995/assistente-a/releases/latest"
    }

    fun currentVersion(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }

    /** Pode instalar? (a primeira vez, o Android pede "permitir desta fonte") */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Procura versão nova. [onResult] recebe null se já está na última (ou sem internet). */
    fun check(onResult: (Release?) -> Unit) {
        Thread {
            val r = try {
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Alexa-app")
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val version = Regex("(\\d+)\\s*$").find(json.optString("name"))?.groupValues?.get(1)?.toIntOrNull()
                val assets = json.optJSONArray("assets")
                var apk: JSONObject? = null
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) apk = a
                }
                if (version != null && apk != null && version > currentVersion())
                    Release(version, apk.getString("browser_download_url"), apk.optLong("size"))
                else null
            } catch (_: Exception) {
                null
            }
            main.post { onResult(r) }
        }.start()
    }

    /** Baixa e manda instalar. [onProgress] recebe 0..100; [onError] uma mensagem. */
    fun install(release: Release, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(context.packageName)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Alexa-app")
                    }
                    val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else release.size
                    conn.inputStream.use { input ->
                        session.openWrite("alexa.apk", 0, if (total > 0) total else -1).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; main.post { onProgress(pct) } }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    conn.disconnect()
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(
                        context, 41, Intent(context, UpdateReceiver::class.java), flags
                    )
                    session.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                main.post { onError("Não consegui baixar a atualização: ${e.message ?: "erro"}") }
            }
        }.start()
    }
}
