package com.blanke.mdwechat.util

object MaterialTabFloatingBarPolicy {
    val surfaceColor = 0xE6FAFCF8.toInt()
    const val strokeWidthDp = 0f

    private const val floatingHorizontalMarginDp = 14f
    private const val floatingBottomMarginDp = 18f
    private const val floatingHeightDp = 72f
    private const val floatingCornerRadiusDp = floatingHeightDp / 2f
    private const val floatingFabBottomDp = 106f
    private const val floatMenuBaseBottomMarginDp = 12f

    fun horizontalMarginDp(isFloating: Boolean): Float {
        return if (isFloating) floatingHorizontalMarginDp else 0f
    }

    fun bottomMarginDp(isFloating: Boolean): Float {
        return if (isFloating) floatingBottomMarginDp else 0f
    }

    fun heightDp(isFloating: Boolean, nativeHeightDp: Float): Float {
        return if (isFloating) floatingHeightDp else nativeHeightDp
    }

    fun cornerRadiusDp(isFloating: Boolean): Float {
        return if (isFloating) floatingCornerRadiusDp else 0f
    }

    fun reservedNativeBottomTabHeightDp(
        isFloating: Boolean,
        nativeHeightDp: Float
    ): Float {
        return if (isFloating) 0f else nativeHeightDp
    }

    fun floatMenuExtraBottomMarginDp(
        isFloating: Boolean,
        nativeBottomTabHeightDp: Float = 0f
    ): Float {
        return if (isFloating) {
            floatingFabBottomDp - floatMenuBaseBottomMarginDp
        } else {
            nativeBottomTabHeightDp
        }
    }
}
