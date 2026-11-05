package com.alef.assistentea.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/** Informação da música atual. */
data class TrackInfo(val title: String, val artist: String?, val appName: String?, val playing: Boolean)

/**
 * Controla o player de música que estiver ativo no celular.
 *
 * Duas formas, da mais completa para a mais simples:
 *  1. Com "Acesso a notificações" concedido: usa a sessão de mídia do player
 *     (MediaController) → controla e lê título/artista.
 *  2. Sem esse acesso: envia "teclas de mídia" ao sistema (as mesmas de um fone
 *     Bluetooth). Funciona com praticamente qualquer player, mas sem saber o nome da música.
 */
class MediaControl(private val context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var observer: ((TrackInfo?) -> Unit)? = null
    private var observedController: MediaController? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = notifyObserver()
        override fun onMetadataChanged(metadata: MediaMetadata?) = notifyObserver()
        override fun onSessionDestroyed() {
            observedController = null
            notifyObserver()
        }
    }

    // ------------------------------------------------------------------ permissão

    fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        }
    }

    // ------------------------------------------------------------------ comandos

    fun isMusicPlaying(): Boolean {
        controller()?.playbackState?.let { return it.state == PlaybackState.STATE_PLAYING }
        return am.isMusicActive
    }

    fun play() = control({ it.transportControls.play() }, KeyEvent.KEYCODE_MEDIA_PLAY)
    fun pause() = control({ it.transportControls.pause() }, KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next() = control({ it.transportControls.skipToNext() }, KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = control({ it.transportControls.skipToPrevious() }, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /**
     * "Parar": muitos apps (Spotify, por exemplo) encerram a sessão com "stop" e depois
     * não aceitam "continuar". Por isso usamos "pausa", que tem o mesmo efeito para quem ouve.
     */
    fun stop() = pause()

    /**
     * Tenta pedir a música diretamente à sessão do player já aberto, sem trazer o app
     * para a frente (funciona mesmo com a Alexa em segundo plano). Só é usado se o player
     * declarar suporte a "tocar a partir de busca". Retorna o nome do app, ou null.
     */
    fun playFromSearch(query: String, extras: android.os.Bundle, onlyPackage: String?): String? {
        if (!hasNotificationAccess()) return null
        val sessions = try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            return null
        }
        val c = sessions.firstOrNull { s ->
            (onlyPackage == null || s.packageName == onlyPackage) &&
                ((s.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L
        } ?: return null
        return try {
            c.transportControls.playFromSearch(query, extras)
            appLabel(c.packageName) ?: c.packageName
        } catch (_: Exception) {
            null
        }
    }

    fun nowPlaying(): TrackInfo? {
        val c = controller() ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        return TrackInfo(title, artist, appLabel(c.packageName), playing)
    }

    // ------------------------------------------------------------------ observar mudanças

    /** Chama [onChange] sempre que a música ou o estado (tocando/pausado) mudar. */
    fun observe(onChange: (TrackInfo?) -> Unit) {
        observer = onChange
        if (!hasNotificationAccess()) {
            onChange(null)
            return
        }
        if (sessionsListener == null) {
            val l = MediaSessionManager.OnActiveSessionsChangedListener { attachTo(pick(it.orEmpty())) }
            try {
                sessionManager.addOnActiveSessionsChangedListener(l, listenerComponent, main)
                sessionsListener = l
            } catch (_: SecurityException) {
                // acesso revogado
            }
        }
        attachTo(controller())
    }

    fun stopObserving() {
        sessionsListener?.let {
            try { sessionManager.removeOnActiveSessionsChangedListener(it) } catch (_: Exception) {}
        }
        sessionsListener = null
        observedController?.unregisterCallback(controllerCallback)
        observedController = null
        observer = null
    }

    private fun attachTo(c: MediaController?) {
        if (c?.sessionToken != observedController?.sessionToken) {
            observedController?.unregisterCallback(controllerCallback)
            observedController = c
            c?.registerCallback(controllerCallback, main)
        }
        notifyObserver()
    }

    private fun notifyObserver() {
        val o = observer ?: return
        main.post { o(nowPlaying()) }
    }

    // ------------------------------------------------------------------ internos

    private fun controller(): MediaController? {
        if (!hasNotificationAccess()) return null
        return try {
            pick(sessionManager.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            null
        }
    }

    /** Prefere a sessão que está tocando; senão, a mais recente. */
    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()

    private fun control(action: (MediaController) -> Unit, keyCode: Int) {
        val c = controller()
        if (c != null) {
            try {
                action(c)
                return
            } catch (_: Exception) {
                // cai para a tecla de mídia
            }
        }
        sendMediaKey(keyCode)
    }

    private fun sendMediaKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun appLabel(pkg: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
