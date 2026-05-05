package com.blanke.mdwechat.util

import kotlin.math.roundToInt

data class MaterialTabCustomIconCrop(
    val left: Int,
    val top: Int,
    val size: Int
)

object MaterialTabCustomIconPolicy {
    private const val legacyIconRegionRatio = 0.6f
    private const val baselineIconSizePx = 72
    private val materialTabIconFileNamePattern = Regex("""tab_icon[0-3]\.png""")

    fun cropWindow(width: Int, height: Int): MaterialTabCustomIconCrop? {
        if (width <= 0 || height <= 0) {
            return null
        }
        val size = minOf(width, (height * legacyIconRegionRatio).roundToInt()).coerceAtLeast(1)
        return MaterialTabCustomIconCrop(
            left = (width - size) / 2,
            top = 0,
            size = size
        )
    }

    fun outputSizePx(bitmapScale: Float): Int {
        val scale = if (bitmapScale.isFinite() && bitmapScale > 0f) bitmapScale else 1f
        return (baselineIconSizePx * scale).roundToInt().coerceAtLeast(1)
    }

    fun shouldUseExternalIcon(
        hasExternalIcon: Boolean,
        matchesBundledIcon: Boolean
    ): Boolean {
        return hasExternalIcon && !matchesBundledIcon
    }

    fun isMaterialTabIconFileName(fileName: String): Boolean {
        return materialTabIconFileNamePattern.matches(fileName)
    }

    fun shouldTintIcons(
        hasCustomIcons: Boolean,
        tintSelectedIcon: Boolean,
        tintUnselectedIcon: Boolean
    ): Boolean {
        return !hasCustomIcons || tintSelectedIcon || tintUnselectedIcon
    }
}
