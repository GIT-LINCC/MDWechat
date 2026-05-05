package com.blanke.mdwechat.util

import kotlin.math.abs

object TabLayoutIndicatorPolicy {
    private const val snapEpsilon = 0.0001f
    private const val regularIndicatorHeightDp = 2f
    private const val smallIndicatorHeightDp = 1f

    fun normalizePositionOffset(rawOffset: Float): Float {
        if (rawOffset.isNaN() || rawOffset.isInfinite()) {
            return 0f
        }
        val clampedOffset = rawOffset.coerceIn(0f, 1f)
        return when {
            abs(clampedOffset) < snapEpsilon -> 0f
            abs(1f - clampedOffset) < snapEpsilon -> 1f
            else -> clampedOffset
        }
    }

    fun indicatorOnContent(isSmall: Boolean): Boolean {
        return isSmall
    }

    fun indicatorHeightDp(isSmall: Boolean): Float {
        return if (isSmall) smallIndicatorHeightDp else regularIndicatorHeightDp
    }

    fun topContentGapDp(): Float {
        return 4f
    }

    fun shouldApplyTopContentOffset(
        isTopTabLayout: Boolean,
        actionBarHeight: Int,
        quitFix: Boolean
    ): Boolean {
        return quitFix || actionBarHeight > 0 || isTopTabLayout
    }
}
