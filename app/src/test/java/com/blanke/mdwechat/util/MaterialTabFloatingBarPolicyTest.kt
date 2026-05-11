package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialTabFloatingBarPolicyTest {
    @Test
    fun disabledFloatingBarKeepsLegacyBottomTabGeometry() {
        assertEquals(0f, MaterialTabFloatingBarPolicy.horizontalMarginDp(isFloating = false), 0f)
        assertEquals(0f, MaterialTabFloatingBarPolicy.bottomMarginDp(isFloating = false), 0f)
        assertEquals(56f, MaterialTabFloatingBarPolicy.heightDp(isFloating = false, nativeHeightDp = 56f), 0f)
        assertEquals(0f, MaterialTabFloatingBarPolicy.cornerRadiusDp(isFloating = false), 0f)
    }

    @Test
    fun enabledFloatingBarMatchesTheWebviewPreviewCapsule() {
        assertEquals(14f, MaterialTabFloatingBarPolicy.horizontalMarginDp(isFloating = true), 0f)
        assertEquals(18f, MaterialTabFloatingBarPolicy.bottomMarginDp(isFloating = true), 0f)
        assertEquals(72f, MaterialTabFloatingBarPolicy.heightDp(isFloating = true, nativeHeightDp = 56f), 0f)
        assertEquals(36f, MaterialTabFloatingBarPolicy.cornerRadiusDp(isFloating = true), 0f)
    }

    @Test
    fun floatingBarSurfaceIsSubtleInsteadOfGlassLike() {
        assertEquals(0xE6FAFCF8.toInt(), MaterialTabFloatingBarPolicy.surfaceColor)
    }

    @Test
    fun floatingBarDoesNotDrawWhiteHairlineStroke() {
        assertEquals(0f, MaterialTabFloatingBarPolicy.strokeWidthDp, 0f)
    }

    @Test
    fun floatingBarDoesNotReserveNativeBottomTabSpace() {
        assertEquals(
            0f,
            MaterialTabFloatingBarPolicy.reservedNativeBottomTabHeightDp(
                isFloating = true,
                nativeHeightDp = 56f
            ),
            0f
        )
    }

    @Test
    fun legacyBottomTabStillReservesNativeTabSpace() {
        assertEquals(
            56f,
            MaterialTabFloatingBarPolicy.reservedNativeBottomTabHeightDp(
                isFloating = false,
                nativeHeightDp = 56f
            ),
            0f
        )
    }

    @Test
    fun floatingFabStaysAboveTheFloatingBottomBarWithPreviewSpacing() {
        assertEquals(
            94f,
            MaterialTabFloatingBarPolicy.floatMenuExtraBottomMarginDp(isFloating = true),
            0f
        )
    }

    @Test
    fun legacyFabOffsetContinuesToUseNativeTabHeight() {
        assertEquals(
            56f,
            MaterialTabFloatingBarPolicy.floatMenuExtraBottomMarginDp(
                isFloating = false,
                nativeBottomTabHeightDp = 56f
            ),
            0f
        )
    }
}
