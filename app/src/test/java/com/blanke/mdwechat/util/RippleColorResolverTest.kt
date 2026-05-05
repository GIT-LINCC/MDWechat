package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RippleColorResolverTest {
    @Test
    fun keepsTranslucentColorsUnchanged() {
        val input = 0x22112233

        assertEquals(input.toInt(), RippleColorResolver.resolvePressedColor(input.toInt()))
    }

    @Test
    fun softensOpaqueColorsForPressedState() {
        val input = 0xFF112233
        val expected = 0x33112233

        assertEquals(expected.toInt(), RippleColorResolver.resolvePressedColor(input.toInt()))
    }
}
