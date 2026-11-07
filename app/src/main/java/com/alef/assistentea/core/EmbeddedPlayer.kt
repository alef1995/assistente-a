package com.alef.assistentea.core

/** Player de música dentro da própria tela da Zuri (YouTube embutido). */
interface EmbeddedPlayer {
    val isReady: Boolean
    /** Tem uma fila de músicas carregada (tocando ou pausada). */
    val isActive: Boolean
    val isPlaying: Boolean
    val title: String?
    fun playQueue(ids: List<String>, titles: List<String?>)
    fun pause()
    fun resume()
    fun next()
    fun previous()
    fun stop()
}
