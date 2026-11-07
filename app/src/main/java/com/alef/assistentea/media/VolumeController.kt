package com.alef.assistentea.media

import android.content.Context
import android.media.AudioManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Controla o volume de MÍDIA do Android (o mesmo da música).
 * Quando há uma caixa Bluetooth conectada, o Android aplica esse volume a ela.
 */
class VolumeController(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val stream = AudioManager.STREAM_MUSIC
    private val maxIndex = am.getStreamMaxVolume(stream)

    /** Cada "aumentar"/"diminuir" muda cerca de 10%. */
    private val step = max(1, (maxIndex / 10.0).roundToInt())

    fun percent(): Int = if (maxIndex == 0) 0 else (am.getStreamVolume(stream) * 100.0 / maxIndex).roundToInt()

    /** Volume na escala de 0 a 10 (como a Zuri fala: "volume 5"). */
    fun level(): Int = (percent() / 10.0).roundToInt().coerceIn(0, 10)

    fun isMuted(): Boolean = am.isStreamMute(stream)

    fun up(): Int {
        if (isMuted()) unmute()
        setIndex(am.getStreamVolume(stream) + step)
        return percent()
    }

    fun down(): Int {
        setIndex(am.getStreamVolume(stream) - step)
        return percent()
    }

    fun setPercent(p: Int): Int {
        if (isMuted() && p > 0) unmute()
        setIndex((p.coerceIn(0, 100) * maxIndex / 100.0).roundToInt())
        return percent()
    }

    fun mute() = safe { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI) }

    fun unmute() = safe { am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI) }

    /** Abaixa temporariamente (enquanto o microfone escuta) e devolve o volume anterior. */
    fun duck(toPercent: Int): Int? {
        val before = am.getStreamVolume(stream)
        val target = (toPercent * maxIndex / 100.0).roundToInt()
        if (before <= target) return null
        safe { am.setStreamVolume(stream, target, 0) }
        return before
    }

    fun restoreIndex(index: Int) = safe { am.setStreamVolume(stream, index, 0) }

    private fun setIndex(i: Int) = safe {
        am.setStreamVolume(stream, i.coerceIn(0, maxIndex), AudioManager.FLAG_SHOW_UI)
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            // Alguns aparelhos bloqueiam mudanças no modo "Não perturbe"
        }
    }
}
