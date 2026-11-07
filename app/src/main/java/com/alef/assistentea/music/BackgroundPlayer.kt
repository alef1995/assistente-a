package com.alef.assistentea.music

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * Manda um app de música (ex.: Spotify) tocar SEM abrir a tela dele.
 *
 * Usa o mesmo canal que o Android Auto usa nos carros ("MediaBrowserService"): a Alexa se conecta
 * ao serviço de música do app, recebe o controle dele e pede "toque tal coisa". O app toca em
 * segundo plano. Se o app não aceitar a conexão, [play] responde false e a Alexa usa o jeito antigo.
 */
class BackgroundPlayer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** Serviço de música do app (descoberto no próprio app instalado), ou null. */
    private fun browserService(pkg: String): ComponentName? = try {
        context.packageManager
            .queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    } catch (_: Exception) {
        null
    }

    fun play(pkg: String, query: String, artist: String?, onResult: (Boolean) -> Unit) {
        val component = browserService(pkg) ?: return onResult(false)
        var finished = false
        var browser: MediaBrowser? = null
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            if (!ok) try { browser?.disconnect() } catch (_: Exception) {}
            onResult(ok)
        }
        main.postDelayed({ finish(false) }, 9000)

        val extras = Bundle().apply {
            putString(SearchManager.QUERY, query)
            if (artist != null) {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
            } else {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
        }

        try {
            browser = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val b = browser ?: return finish(false)
                    val controller = try {
                        MediaController(context, b.sessionToken)
                    } catch (_: Exception) {
                        return finish(false)
                    }
                    try {
                        controller.transportControls.playFromSearch(query, extras)
                    } catch (_: Exception) {
                        return finish(false)
                    }
                    var checks = 0
                    val check = object : Runnable {
                        override fun run() {
                            if (finished) return
                            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) return finish(true)
                            checks++
                            // Às vezes o app precisa de um segundo pedido logo depois de "acordar"
                            if (checks == 6) try { controller.transportControls.playFromSearch(query, extras) } catch (_: Exception) {}
                            if (checks < 14) main.postDelayed(this, 500) else finish(false)
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
}
