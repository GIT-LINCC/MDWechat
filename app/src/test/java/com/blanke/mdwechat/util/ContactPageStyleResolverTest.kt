package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.blanke.mdwechat.Version

class ContactPageStyleResolverTest {
    @Test
    fun usesMdGrayPageBackgroundInDayMode() {
        assertEquals(
            0xFFEDEDED.toInt(),
            ContactPageStyleResolver.resolvePageBackgroundColor(isNightMode = false)
        )
    }

    @Test
    fun usesTransparentItemSurfacesToRevealPageBackground() {
        assertTrue(ContactPageStyleResolver.shouldUseTransparentItemSurface())
    }

    @Test
    fun recognizesIndexedContactRowsByStableResourceMarkers() {
        assertTrue(
            ContactPageStyleResolver.shouldStyleIndexedContactRow(
                setOf("cg5", "kbo", "kbq")
            )
        )
    }

    @Test
    fun keepsNativeHeaderIconTreeOnWeChat8049AndNewer() {
        assertFalse(ContactPageStyleResolver.shouldWrapHeaderEntryIcon(Version("8.0.49")))
    }

    @Test
    fun keepsLegacyHeaderIconWrappingBeforeWeChat8049() {
        assertTrue(ContactPageStyleResolver.shouldWrapHeaderEntryIcon(Version("8.0.48")))
    }
}
