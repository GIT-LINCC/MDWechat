package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialTabIconTintPolicyTest {
    @Test
    fun selectedIconUsesSecondaryColorWhenSelectedIconTintIsEnabled() {
        val colors = MaterialTabIconTintPolicy.resolve(
            selectedTextColor = 0xFF00AAFF.toInt(),
            unselectedTextColor = 0xFF111111.toInt(),
            tertiaryColor = 0xFF666666.toInt(),
            tintSelectedIcon = true,
            tintUnselectedIcon = false
        )

        assertEquals(0xFF00AAFF.toInt(), colors.selectedIconColor)
        assertEquals(0xFF111111.toInt(), colors.unselectedIconColor)
    }

    @Test
    fun selectedIconFallsBackToTitleColorWhenSelectedIconTintIsDisabled() {
        val colors = MaterialTabIconTintPolicy.resolve(
            selectedTextColor = 0xFF00AAFF.toInt(),
            unselectedTextColor = 0xFF111111.toInt(),
            tertiaryColor = 0xFF666666.toInt(),
            tintSelectedIcon = false,
            tintUnselectedIcon = false
        )

        assertEquals(0xFF111111.toInt(), colors.selectedIconColor)
    }

    @Test
    fun unselectedIconUsesTertiaryColorWhenUnselectedIconTintIsEnabled() {
        val colors = MaterialTabIconTintPolicy.resolve(
            selectedTextColor = 0xFF00AAFF.toInt(),
            unselectedTextColor = 0xFF111111.toInt(),
            tertiaryColor = 0xFF666666.toInt(),
            tintSelectedIcon = false,
            tintUnselectedIcon = true
        )

        assertEquals(0xFF666666.toInt(), colors.unselectedIconColor)
    }
}
