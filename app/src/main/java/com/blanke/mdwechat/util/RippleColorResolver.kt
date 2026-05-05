package com.blanke.mdwechat.util

object RippleColorResolver {
    private const val DEFAULT_PRESSED_ALPHA = 0x33

    fun resolvePressedColor(color: Int): Int {
        val alpha = color ushr 24 and 0xFF
        return if (alpha == 0xFF) {
            (DEFAULT_PRESSED_ALPHA shl 24) or (color and 0x00FFFFFF)
        } else {
            color
        }
    }
}
