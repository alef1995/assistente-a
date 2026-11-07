package com.alef.assistentea.music

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.alef.assistentea.media.MediaControl

/**
 * Inicia músicas em apps de streaming instalados, usando o mecanismo OFICIAL do Android
 * "tocar a partir de uma busca" (MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) —
 * o mesmo que o Google Assistente usa. O app de música faz a busca e começa a tocar;
 * não há player embutido, nem raspagem de site, nem o erro 153 do YouTube.
 */
class MusicLauncher(private val context: Context) {

    data class Player(val packageName: String, val label: String)

    sealed class Result {
        /** O app de música recebeu o pedido e deve começar a tocar.
         *  [viaSession] = pedido enviado em segundo plano, sem abrir o app (vale conferir se tocou). */
        data class Started(
            val playerLabel: String,
            val viaSession: Boolean = false,
            /** App de vídeo (YouTube): abre por cima da tela da Alexa. */
            val videoApp: Boolean = false
        ) : Result()
        /** Nenhum app aceitou tocar direto; abrimos a busca para o usuário tocar. */
        data class OpenedSearch(val playerLabel: String) : Result()
        /** Nenhum app de música encontrado. */
        data object NoPlayer : Result()
    }

    companion object {
        /** YouTube modificado pelo Morphe (toca em segundo plano e sem anúncios): player principal da Alexa. */
        val MORPHE_YOUTUBE = Player("app.morphe.android.youtube", "YouTube (Morphe)")

        val KNOWN_PLAYERS = listOf(
            MORPHE_YOUTUBE,
            Player("com.spotify.music", "Spotify"),
            Player("com.google.android.apps.youtube.music", "YouTube Music"),
            Player("deezer.android.app", "Deezer"),
            Player("com.amazon.mp3", "Amazon Music")
        )
        private const val PREFS = "music"
        private const val KEY_PLAYER = "preferred_player"
    }

    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedPlayers(): List<Player> = KNOWN_PLAYERS.filter { isInstalled(it.packageName) }

    /** Player escolhido pelo usuário. Sem escolha, é sempre o YouTube Morphe (se instalado). */
    var preferredPlayer: Player?
        get() {
            val pkg = prefs.getString(KEY_PLAYER, null)
                ?: return MORPHE_YOUTUBE.takeIf { isInstalled(it.packageName) }
            return installedPlayers().firstOrNull { it.packageName == pkg }
        }
        set(value) {
            prefs.edit().putString(KEY_PLAYER, value?.packageName).apply()
        }

    /** Alterna entre "automático" e os players instalados. Retorna o novo escolhido. */
    fun cyclePreferred(): Player? {
        val options: List<Player?> = listOf<Player?>(null) + installedPlayers()
        val idx = options.indexOf(preferredPlayer)
        val next = options[(idx + 1) % options.size]
        preferredPlayer = next
        return next
    }

    fun play(query: String, artist: String?, media: MediaControl? = null): Result {
        // YouTube Morphe escolhido (padrão quando instalado): toda música vai para ele
        if (preferredPlayer?.packageName == MORPHE_YOUTUBE.packageName) {
            return playOnYouTube(MORPHE_YOUTUBE, query, artist)
        }

        // 0) Player de música já aberto (ex.: Spotify) que aceita o pedido pela sessão de mídia:
        //    toca sem abrir a tela dele.
        if (media != null) {
            val wanted = preferredPlayer?.let { listOf(it) } ?: installedPlayers()
            for (p in wanted) {
                if (media.playFromSearch(query, searchExtras(query, artist), p.packageName) != null) {
                    return Result.Started(p.label, viaSession = true)
                }
            }
        }

        val candidates = buildList {
            preferredPlayer?.let { add(it) }
            installedPlayers().forEach { if (it !in this) add(it) }
        }

        // 1) Tocar direto no player escolhido ou em um dos conhecidos
        for (p in candidates) {
            val intent = playFromSearchIntent(query, artist).setPackage(p.packageName)
            if (canHandle(intent) && start(intent)) return Result.Started(p.label)
        }

        // 2) Qualquer outro app de música que aceite o pedido
        val any = playFromSearchIntent(query, artist)
        if (canHandle(any) && start(any)) return Result.Started("app de música")

        // 3) Último recurso: abrir a tela de busca (o usuário toca com um toque)
        val searchTerm = artist ?: query
        if (isInstalled("com.spotify.music")) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(searchTerm)))
                .setPackage("com.spotify.music")
            if (start(i)) return Result.OpenedSearch("Spotify")
        }
        val yt = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(searchTerm)))
        if (start(yt)) return Result.OpenedSearch("YouTube Music")

        return Result.NoPlayer
    }

    /**
     * Pede a música ao YouTube (Morphe). Ele aceita o mesmo pedido oficial "tocar a partir de uma busca"
     * (tela de busca de mídia dele). Se não aceitar, abre a busca do YouTube com o termo.
     */
    private fun playOnYouTube(p: Player, query: String, artist: String?): Result {
        // Artista: pede "músicas" para vir uma sequência, não um vídeo qualquer
        val term = artist?.let { "$it músicas" } ?: query
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(p.packageName)
            putExtra(SearchManager.QUERY, term)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        if (canHandle(play) && start(play)) return Result.Started(p.label, videoApp = true)

        val search = Intent(Intent.ACTION_SEARCH).setPackage(p.packageName).putExtra(SearchManager.QUERY, term)
        if (canHandle(search) && start(search)) return Result.OpenedSearch(p.label)

        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(term)))
            .setPackage(p.packageName)
        if (start(web)) return Result.OpenedSearch(p.label)
        return Result.NoPlayer
    }

    private fun searchExtras(query: String, artist: String?): Bundle = Bundle().apply {
        putString(SearchManager.QUERY, query)
        if (artist != null) {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        } else {
            // Busca "livre": o app decide se é música, artista ou playlist
            putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
    }

    private fun playFromSearchIntent(query: String, artist: String?): Intent =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtras(searchExtras(query, artist))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun canHandle(intent: Intent): Boolean =
        pm.queryIntentActivities(intent, 0).isNotEmpty()

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun isInstalled(pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
