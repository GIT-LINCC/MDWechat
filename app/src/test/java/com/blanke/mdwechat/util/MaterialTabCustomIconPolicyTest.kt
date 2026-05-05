package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaterialTabCustomIconPolicyTest {
    @Test
    fun cropsTheIconRegionFromLegacyTabCanvas() {
        val crop = MaterialTabCustomIconPolicy.cropWindow(width = 150, height = 150)

        assertEquals(MaterialTabCustomIconCrop(left = 30, top = 0, size = 90), crop)
    }

    @Test
    fun returnsNullForInvalidBitmapSize() {
        assertNull(MaterialTabCustomIconPolicy.cropWindow(width = 0, height = 150))
        assertNull(MaterialTabCustomIconPolicy.cropWindow(width = 150, height = 0))
    }

    @Test
    fun scalesMaterialIconSizeFromMdpiBaseline() {
        assertEquals(72, MaterialTabCustomIconPolicy.outputSizePx(bitmapScale = 1f))
        assertEquals(84, MaterialTabCustomIconPolicy.outputSizePx(bitmapScale = 7f / 6f))
    }

    @Test
    fun treatsCopiedBundledIconAsNotCustomized() {
        assertEquals(
            false,
            MaterialTabCustomIconPolicy.shouldUseExternalIcon(
                hasExternalIcon = true,
                matchesBundledIcon = true
            )
        )
        assertEquals(
            true,
            MaterialTabCustomIconPolicy.shouldUseExternalIcon(
                hasExternalIcon = true,
                matchesBundledIcon = false
            )
        )
        assertEquals(
            false,
            MaterialTabCustomIconPolicy.shouldUseExternalIcon(
                hasExternalIcon = false,
                matchesBundledIcon = false
            )
        )
    }

    @Test
    fun recognizesMaterialTabIconFilesThatShouldBeClearedOnReset() {
        assertEquals(true, MaterialTabCustomIconPolicy.isMaterialTabIconFileName("tab_icon0.png"))
        assertEquals(true, MaterialTabCustomIconPolicy.isMaterialTabIconFileName("tab_icon3.png"))
        assertEquals(false, MaterialTabCustomIconPolicy.isMaterialTabIconFileName("tab_icon4.png"))
        assertEquals(false, MaterialTabCustomIconPolicy.isMaterialTabIconFileName("tab_icon0.bak.png"))
        assertEquals(false, MaterialTabCustomIconPolicy.isMaterialTabIconFileName("ic_chat.png"))
    }

    @Test
    fun keepsCustomBitmapUntintedUnlessColorFiltersAreEnabled() {
        assertEquals(
            false,
            MaterialTabCustomIconPolicy.shouldTintIcons(
                hasCustomIcons = true,
                tintSelectedIcon = false,
                tintUnselectedIcon = false
            )
        )
        assertEquals(
            true,
            MaterialTabCustomIconPolicy.shouldTintIcons(
                hasCustomIcons = true,
                tintSelectedIcon = false,
                tintUnselectedIcon = true
            )
        )
        assertEquals(
            true,
            MaterialTabCustomIconPolicy.shouldTintIcons(
                hasCustomIcons = false,
                tintSelectedIcon = false,
                tintUnselectedIcon = false
            )
        )
    }
}
