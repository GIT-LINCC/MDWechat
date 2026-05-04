package com.blanke.mdwechat.util

object MainPageRippleGesturePolicy {
    fun shouldResetFromLocalPoint(localX: Float, localY: Float, width: Int, height: Int): Boolean {
        return shouldResetFromRawPoint(localX, localY, 0, 0, width, height)
    }

    fun shouldResetFromRawPoint(
        rawX: Float,
        rawY: Float,
        leftOnScreen: Int,
        topOnScreen: Int,
        width: Int,
        height: Int
    ): Boolean {
        if (width <= 0 || height <= 0) {
            return true
        }
        val rightOnScreen = leftOnScreen + width
        val bottomOnScreen = topOnScreen + height
        return rawX < leftOnScreen ||
            rawY < topOnScreen ||
            rawX >= rightOnScreen ||
            rawY >= bottomOnScreen
    }
}
