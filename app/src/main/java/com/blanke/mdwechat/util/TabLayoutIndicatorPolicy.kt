package com.blanke.mdwechat.util

import kotlin.math.abs

object TabLayoutIndicatorPolicy {
    private const val snapEpsilon = 0.0001f

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
}
