package com.alef.assistentea.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.alef.assistentea.core.EmbeddedPlayer
import org.json.JSONArray

/**
 * Player oficial do YouTube ("IFrame Player API") dentro da tela da Alexa.
 *
 * A música toca aqui mesmo, num cartão de vídeo no canto da tela — sem abrir o app do YouTube.
 * O YouTube exige que o player fique visível (não pode ser escondido), por isso o cartão.
 * Recebe uma fila de vídeos; se algum não puder tocar fora do YouTube, pula para o próximo.
 */
class EmbeddedYouTube(
    private val web: WebView,
    /** O cartão que contém o vídeo (aparece quando começa a tocar). */
    private val card: View,
    private val onChange: () -> Unit
) : EmbeddedPlayer {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: String? = null

    override var isActive = false
        private set
    override var isPlaying = false
        private set
    override var title: String? = null
        private set
    private var titles: List<String?> = emptyList()

    override val isReady: Boolean get() = true // a fila espera o player carregar, se preciso

    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false // tocar sem precisar tocar na tela
        web.setBackgroundColor(0)
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Alexa")
        // Uma origem https de verdade: sem ela, o YouTube recusa o player (erro 153)
        web.loadDataWithBaseURL(ORIGIN + "/assistente-a/", HTML, "text/html", "utf-8", null)
    }

    fun destroy() {
        try {
            web.loadUrl("about:blank")
            web.destroy()
        } catch (_: Exception) {
        }
    }

    // ================================================================== EmbeddedPlayer

    override fun playQueue(ids: List<String>, titles: List<String?>) {
        if (ids.isEmpty()) return
        this.titles = titles
        title = titles.firstOrNull()
        isActive = true
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(300).start()
        run("playIds(${JSONArray(ids)})")
        onChange()
    }

    override fun pause() = run("player && player.pauseVideo()")
    override fun resume() = run("player && player.playVideo()")
    override fun next() = run("player && player.nextVideo()")
    override fun previous() = run("player && player.previousVideo()")

    override fun stop() {
        run("player && player.stopVideo()")
        isActive = false
        isPlaying = false
        card.animate().alpha(0f).setDuration(300).withEndAction { card.visibility = View.INVISIBLE }.start()
        onChange()
    }

    private fun run(js: String) {
        if (!ready) {
            if (js.startsWith("playIds")) pending = js
            return
        }
        web.evaluateJavascript(js, null)
    }

    // ================================================================== avisos vindos do player

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() = main.post {
            ready = true
            pending?.let { web.evaluateJavascript(it, null) }
            pending = null
        }

        /** Estados do YouTube: -1 não iniciado, 0 terminou, 1 tocando, 2 pausado, 3 carregando, 5 na fila. */
        @JavascriptInterface
        fun onState(state: Int, index: Int, videoTitle: String?) = main.post {
            isPlaying = state == 1
            if (!videoTitle.isNullOrBlank()) title = videoTitle
            else titles.getOrNull(index)?.let { title = it }
            if (state == 0 && index >= titles.size - 1) {
                // Acabou a fila
                isActive = false
            }
            onChange()
        }

        @JavascriptInterface
        fun onError(code: Int) = main.post {
            // 100/101/150: vídeo removido ou bloqueado fora do YouTube → já pulamos no JavaScript
            onChange()
        }
    }

    companion object {
        private const val ORIGIN = "https://alef1995.github.io"

        private val HTML = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden;height:100%}#p{width:100%;height:100%}</style>
</head><body>
<div id="p"></div>
<script>
  var player = null;
  var tag = document.createElement('script');
  tag.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(tag);

  function onYouTubeIframeAPIReady() {
    player = new YT.Player('p', {
      width: '100%', height: '100%',
      playerVars: { autoplay: 1, playsinline: 1, controls: 0, rel: 0, modestbranding: 1,
                    iv_load_policy: 3, origin: '$ORIGIN' },
      events: {
        onReady: function() { Alexa.onReady(); },
        onStateChange: function(e) { report(e.data); },
        onError: function(e) {
          Alexa.onError(e.data);
          // Vídeo que não pode tocar fora do YouTube: pula para o próximo
          setTimeout(function(){ try { player.nextVideo(); } catch(x){} }, 300);
        }
      }
    });
  }

  function report(state) {
    var t = '';
    try { t = (player.getVideoData() || {}).title || ''; } catch(x) {}
    var i = 0;
    try { i = player.getPlaylistIndex(); } catch(x) {}
    Alexa.onState(state, i, t);
  }

  function playIds(ids) {
    player.loadPlaylist(ids, 0, 0);
    try { player.setLoop(false); player.unMute(); player.setVolume(100); } catch(x) {}
  }
</script>
</body></html>
"""
    }
}
