package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
