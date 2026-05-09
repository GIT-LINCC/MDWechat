package com.blanke.mdwechat.hookers

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import com.blanke.mdwechat.util.ChatBubbleStylePolicy

class ModernBubbleDrawable(
    context: Context,
    initialState: ChatBubbleStylePolicy.RenderState,
    initialPalette: ChatBubbleStylePolicy.BubblePalette
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private var state = initialState
    private var palette = initialPalette
    private var currentRadii = initialState.cornerRadii
    private var baseColor = initialPalette.bubbleColor
    private var gradientColors = gradientColorsFor(initialPalette)
    private val gradientStops = floatArrayOf(0f, 0.55f, 1f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(initialPalette.strokeWidthDp)
        color = initialPalette.strokeColor
    }
    private val path = Path()
    private val rect = RectF()
    private var animator: ValueAnimator? = null
    private var cachedLeft = Int.MIN_VALUE
    private var cachedTop = Int.MIN_VALUE
    private var cachedRight = Int.MIN_VALUE
    private var cachedBottom = Int.MIN_VALUE
    private var cachedGradient: LinearGradient? = null

    val stableKey: String
        get() = state.stableKey

    val side: ChatBubbleStylePolicy.Side
        get() = state.side

    val position: ChatBubbleStylePolicy.GroupPosition
        get() = state.position

    override fun draw(canvas: Canvas) {
        updateDrawingCache()
        fillPaint.shader = cachedGradient
        fillPaint.style = Paint.Style.FILL
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null
        if (strokePaint.strokeWidth > 0f && Color.alpha(strokePaint.color) > 0) {
            canvas.drawPath(path, strokePaint)
        }
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

    fun update(
        nextState: ChatBubbleStylePolicy.RenderState,
        nextPalette: ChatBubbleStylePolicy.BubblePalette,
        animateCorners: Boolean
    ) {
        val startRadii = currentRadii
        val endRadii = nextState.cornerRadii
        state = nextState
        updatePalette(nextPalette)
        animator?.cancel()
        if (!animateCorners || startRadii == endRadii) {
            currentRadii = endRadii
            invalidateDrawingCache()
            invalidateSelf()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                currentRadii = lerp(startRadii, endRadii, fraction)
                invalidateDrawingCache()
                invalidateSelf()
            }
            start()
        }
    }

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
        val inset = strokePaint.strokeWidth / 2f
        if (inset > 0f && Color.alpha(strokePaint.color) > 0) {
            rect.inset(inset, inset)
        }
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
        val radii = currentRadii
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

    private fun updatePalette(nextPalette: ChatBubbleStylePolicy.BubblePalette) {
        if (palette == nextPalette) {
            return
        }
        palette = nextPalette
        baseColor = nextPalette.bubbleColor
        gradientColors = gradientColorsFor(nextPalette)
        strokePaint.strokeWidth = dp(nextPalette.strokeWidthDp)
        strokePaint.color = nextPalette.strokeColor
        invalidateDrawingCache()
    }

    private fun invalidateDrawingCache() {
        cachedLeft = Int.MIN_VALUE
        cachedTop = Int.MIN_VALUE
        cachedRight = Int.MIN_VALUE
        cachedBottom = Int.MIN_VALUE
        cachedGradient = null
    }

    private fun lerp(
        start: ChatBubbleStylePolicy.CornerRadiiDp,
        end: ChatBubbleStylePolicy.CornerRadiiDp,
        fraction: Float
    ): ChatBubbleStylePolicy.CornerRadiiDp {
        return ChatBubbleStylePolicy.CornerRadiiDp(
            topLeft = lerp(start.topLeft, end.topLeft, fraction),
            topRight = lerp(start.topRight, end.topRight, fraction),
            bottomRight = lerp(start.bottomRight, end.bottomRight, fraction),
            bottomLeft = lerp(start.bottomLeft, end.bottomLeft, fraction)
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private fun gradientColorsFor(palette: ChatBubbleStylePolicy.BubblePalette): IntArray {
        return if (palette.useGradient) {
            intArrayOf(lighten(palette.bubbleColor), palette.bubbleColor, darken(palette.bubbleColor))
        } else {
            intArrayOf(palette.bubbleColor, palette.bubbleColor, palette.bubbleColor)
        }
    }

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
