package com.alef.assistentea.music

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Toca uma música no YouTube (Morphe) sem precisar da tela de busca dele.
 *
 *  1. A própria Alexa pesquisa no YouTube e descobre o vídeo (primeiro resultado);
 *  2. Tenta mandar tocar pelo canal de "carro" do YouTube (Android Auto), sem abrir o app;
 *  3. Se o YouTube não aceitar, abre direto no vídeo — já tocando, em modo "mix" para continuar
 *     com músicas parecidas — e a Alexa volta para a frente assim que o som começar.
 */
class YouTubePlayer(private val context: Context, private val pkg: String) {

    sealed class Outcome {
        /** Tocando em segundo plano, sem abrir o YouTube. */
        data object PlayingInBackground : Outcome()
        /** Abriu o YouTube direto no vídeo (ele começa a tocar sozinho). */
        data class OpenedVideo(val title: String?) : Outcome()
        /** Sem internet ou nada encontrado: abriu a busca do YouTube. */
        data object OpenedSearch : Outcome()
        data object Failed : Outcome()
    }

    private data class Video(val id: String, val title: String?)

    private val main = Handler(Looper.getMainLooper())

    companion object {
        private const val BROWSER_SERVICE =
            "com.google.android.apps.youtube.app.extensions.mediabrowser.impl.MainAppMediaBrowserService"
        private val RENDERER = Regex("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\"")
        private val ANY_VIDEO = Regex("\"videoId\":\"([\\w-]{11})\"")
    }

    fun play(term: String, onDone: (Outcome) -> Unit) {
        Thread {
            val video = try {
                search(term)
            } catch (_: Exception) {
                null
            }
            main.post {
                if (video == null) {
                    onDone(if (openSearch(term)) Outcome.OpenedSearch else Outcome.Failed)
                } else {
                    playInBackground(term, video) { ok ->
                        if (ok) onDone(Outcome.PlayingInBackground)
                        else onDone(if (openVideo(video)) Outcome.OpenedVideo(video.title) else Outcome.Failed)
                    }
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ 1) pesquisa

    private fun search(term: String): Video? {
        val url = URL(
            "https://www.youtube.com/results?search_query=" + URLEncoder.encode(term, "UTF-8") + "&hl=pt-BR&gl=BR"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            )
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        }
        try {
            if (conn.responseCode != 200) return null
            val html = conn.inputStream.bufferedReader().use { r ->
                val buf = CharArray(16 * 1024)
                val sb = StringBuilder()
                while (sb.length < 3_000_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (RENDERER.containsMatchIn(sb)) break // já achou o primeiro vídeo
                }
                sb.toString()
            }
            val m = RENDERER.find(html) ?: ANY_VIDEO.find(html) ?: return null
            val id = m.groupValues[1]
            val title = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]{1,120})\"")
                .find(html, m.range.last)?.groupValues?.get(1)
            return Video(id, title)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 2) sem abrir o app

    private fun playInBackground(term: String, video: Video, onResult: (Boolean) -> Unit) {
        var finished = false
        var browser: MediaBrowser? = null
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            if (!ok) try { browser?.disconnect() } catch (_: Exception) {}
            onResult(ok)
        }
        main.postDelayed({ finish(false) }, 7000) // não respondeu a tempo

        try {
            browser = MediaBrowser(context, ComponentName(pkg, BROWSER_SERVICE), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val b = browser ?: return finish(false)
                    val controller = try {
                        MediaController(context, b.sessionToken)
                    } catch (_: Exception) {
                        return finish(false)
                    }
                    val extras = Bundle().apply { putString("android.intent.extra.focus", "vnd.android.cursor.item/*") }
                    try {
                        controller.transportControls.playFromSearch(term, extras)
                    } catch (_: Exception) {
                        return finish(false)
                    }
                    // Confere se começou a tocar; na metade do tempo, tenta pelo endereço do vídeo
                    var checks = 0
                    val check = object : Runnable {
                        override fun run() {
                            if (finished) return
                            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) return finish(true)
                            checks++
                            if (checks == 5) try {
                                controller.transportControls.playFromUri(
                                    Uri.parse("https://www.youtube.com/watch?v=${video.id}"), extras
                                )
                            } catch (_: Exception) {}
                            if (checks < 10) main.postDelayed(this, 500) else finish(false)
                        }
                    }
                    main.postDelayed(check, 500)
                }

                override fun onConnectionFailed() = finish(false)
                override fun onConnectionSuspended() = finish(false)
            }, null)
            browser.connect()
        } catch (_: Exception) {
            finish(false)
        }
    }

    // ------------------------------------------------------------------ 3) abrindo direto no vídeo

    private fun openVideo(video: Video): Boolean {
        // "list=RD<id>" = mix do YouTube: depois desta, continua com músicas parecidas
        val uri = Uri.parse("https://www.youtube.com/watch?v=${video.id}&list=RD${video.id}")
        return start(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${video.id}")).setPackage(pkg))
    }

    private fun openSearch(term: String): Boolean = start(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(term)))
            .setPackage(pkg)
    )

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
