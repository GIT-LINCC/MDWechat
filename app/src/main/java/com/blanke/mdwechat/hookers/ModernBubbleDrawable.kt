package com.blanke.mdwechat.hookers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.blanke.mdwechat.util.ChatBubbleStylePolicy
import com.blanke.mdwechat.util.ChatBubbleStylePolicy.Side

class ModernBubbleDrawable(
    context: Context,
    private val state: ChatBubbleStylePolicy.RenderState
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val baseColor = if (state.side == Side.RIGHT) {
        Color.parseColor("#C5EFD1")
    } else {
        Color.parseColor("#FCFCF8")
    }
    private val gradientColors = intArrayOf(lighten(baseColor), baseColor, darken(baseColor))
    private val gradientStops = floatArrayOf(0f, 0.55f, 1f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(140, 255, 255, 255)
    }
    private val path = Path()
    private val rect = RectF()
    private var cachedLeft = Int.MIN_VALUE
    private var cachedTop = Int.MIN_VALUE
    private var cachedRight = Int.MIN_VALUE
    private var cachedBottom = Int.MIN_VALUE
    private var cachedGradient: LinearGradient? = null

    override fun draw(canvas: Canvas) {
        updateDrawingCache()
        fillPaint.shader = cachedGradient
        fillPaint.style = Paint.Style.FILL
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun updateDrawingCache() {
        val currentBounds = bounds
        if (cachedLeft == currentBounds.left &&
            cachedTop == currentBounds.top &&
            cachedRight == currentBounds.right &&
            cachedBottom == currentBounds.bottom
        ) {
            return
        }
        cachedLeft = currentBounds.left
        cachedTop = currentBounds.top
        cachedRight = currentBounds.right
        cachedBottom = currentBounds.bottom

        rect.set(currentBounds)
        rect.inset(dp(0.5f), dp(0.5f))
        path.reset()
        path.addRoundRect(rect, radiiPx(), Path.Direction.CW)
        cachedGradient = LinearGradient(
            0f,
            rect.top,
            0f,
            rect.bottom,
            gradientColors,
            gradientStops,
            Shader.TileMode.CLAMP
        )
    }

    private fun radiiPx(): FloatArray {
        val radii = state.cornerRadii
        val topLeft = dp(radii.topLeft)
        val topRight = dp(radii.topRight)
        val bottomRight = dp(radii.bottomRight)
        val bottomLeft = dp(radii.bottomLeft)
        return floatArrayOf(
            topLeft,
            topLeft,
            topRight,
            topRight,
            bottomRight,
            bottomRight,
            bottomLeft,
            bottomLeft
        )
    }

    private fun dp(value: Float): Float = value * density

    private fun lighten(color: Int): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * 1.04f).toInt().coerceIn(0, 255),
            (Color.green(color) * 1.04f).toInt().coerceIn(0, 255),
            (Color.blue(color) * 1.04f).toInt().coerceIn(0, 255)
        )
    }

    private fun darken(color: Int): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * 0.985f).toInt().coerceIn(0, 255),
            (Color.green(color) * 0.985f).toInt().coerceIn(0, 255),
            (Color.blue(color) * 0.985f).toInt().coerceIn(0, 255)
        )
    }
}
