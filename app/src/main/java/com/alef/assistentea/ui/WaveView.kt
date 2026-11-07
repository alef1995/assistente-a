package com.alef.assistentea.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Ondas fluidas em tons de azul, no estilo das assistentes de IA.
 *
 *  - [Mode.IDLE]: respiração calma, esperando "Zuri";
 *  - [Mode.LISTENING]: as ondas seguem a sua voz ([setLevel]);
 *  - [Mode.THINKING]: pulso rápido enquanto entende o pedido;
 *  - [Mode.SPEAKING]: ritmo de fala enquanto a Zuri responde.
 *
 * Desenhada à mão (Canvas), sem bibliotecas, a 60 quadros por segundo.
 */
class WaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private var level = 0f
    private var amp = 0.1f
    private var speed = 0.6f
    private var phase = 0.0
    private var lastFrame = 0L

    /** Volume da voz ouvida, de 0 a 1. */
    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
    }

    /** Uma camada de onda. */
    private class Layer(
        val color: Int,
        val freq: Float,       // quantas ondulações
        val speed: Float,      // velocidade relativa
        val offset: Double,    // defasagem
        val height: Float,     // altura relativa
        val stroke: Float      // espessura da linha (dp)
    ) {
        var fill: Shader? = null
    }

    private val layers = listOf(
        Layer(Color.parseColor("#1E5BFF"), 1.6f, 0.7f, 0.0, 1.00f, 2.0f),
        Layer(Color.parseColor("#0A84FF"), 2.2f, 1.0f, 1.3, 0.85f, 2.5f),
        Layer(Color.parseColor("#00C6FF"), 2.9f, 1.4f, 2.6, 0.70f, 2.0f),
        Layer(Color.parseColor("#5AC8FA"), 3.6f, 1.9f, 4.1, 0.55f, 1.5f),
        Layer(Color.parseColor("#9BE7FF"), 4.4f, 2.4f, 5.2, 0.40f, 1.2f)
    )

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * density }
    private val path = Path()
    private val glowRadius = 100f * density
    private val points = 96
    private val ys = FloatArray(points + 1)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cy = h / 2f
        val maxH = h * 0.42f
        for (l in layers) {
            val c = l.color
            l.fill = LinearGradient(
                0f, cy - maxH, 0f, cy + maxH,
                intArrayOf(
                    Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)),
                    Color.argb(120, Color.red(c), Color.green(c), Color.blue(c)),
                    Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))
                ),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        glowPaint.shader = RadialGradient(
            w / 2f, cy, glowRadius,
            intArrayOf(Color.parseColor("#5500A8FF"), Color.parseColor("#2200C6FF"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        basePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#6600C6FF"), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Tempo
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val t = now / 1000.0

        // Alvo de altura e velocidade conforme o estado
        val (targetAmp, targetSpeed) = when (mode) {
            Mode.IDLE -> (0.10f + 0.035f * sin(t * 0.9).toFloat()) to 0.55f
            Mode.LISTENING -> (0.16f + level * 0.84f) to 1.5f
            Mode.THINKING -> (0.28f + 0.12f * sin(t * 6.0).toFloat()) to 2.6f
            Mode.SPEAKING -> (0.30f + 0.45f * abs(sin(t * 5.3) * sin(t * 2.1 + 1.0)).toFloat()) to 1.9f
        }
        // Suaviza as mudanças (sem "pulos")
        amp += (targetAmp - amp) * min(1f, dt * 9f)
        speed += (targetSpeed - speed) * min(1f, dt * 4f)
        phase += dt * speed * 2.4

        val cy = h / 2f
        val maxH = h * 0.42f

        // Brilho no centro, que cresce com a voz
        val glowScale = (min(w, h) * 0.55f / glowRadius) * (0.75f + amp * 0.7f)
        canvas.save()
        canvas.scale(glowScale, glowScale, w / 2f, cy)
        canvas.drawCircle(w / 2f, cy, glowRadius, glowPaint)
        canvas.restore()

        // Linha de base
        canvas.drawLine(0f, cy, w, cy, basePaint)

        // Ondas, da mais larga para a mais fina
        for (l in layers) {
            val a = amp * l.height * maxH
            val p = phase * l.speed + l.offset
            for (i in 0..points) {
                val nx = i.toFloat() / points * 2f - 1f          // -1 .. 1
                val env = (1f - nx * nx).let { it * it }          // afina nas pontas
                val s = sin(nx * l.freq * PI + p).toFloat()
                ys[i] = env * a * s
            }

            // Preenchimento: forma espelhada (em cima e embaixo da linha)
            path.reset()
            path.moveTo(0f, cy)
            for (i in 0..points) path.lineTo(i * w / points, cy - abs(ys[i]))
            for (i in points downTo 0) path.lineTo(i * w / points, cy + abs(ys[i]))
            path.close()
            fillPaint.shader = l.fill
            canvas.drawPath(path, fillPaint)

            // Linha brilhante da onda
            path.reset()
            path.moveTo(0f, cy + ys[0])
            for (i in 1..points) path.lineTo(i * w / points, cy + ys[i])
            linePaint.color = l.color
            linePaint.alpha = 210
            linePaint.strokeWidth = l.stroke * density
            canvas.drawPath(path, linePaint)
        }

        postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            lastFrame = 0L
            invalidate()
        }
    }
}
