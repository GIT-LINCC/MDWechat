package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TabLayoutIndicatorPolicyTest {
    @Test
    fun snapsTinyScientificNotationOffsetsBackToZero() {
        assertEquals(
            0f,
            TabLayoutIndicatorPolicy.normalizePositionOffset(1.1920929E-7f),
            0f
        )
    }

    @Test
    fun keepsNormalSwipeOffsetsUntouched() {
        assertEquals(
            0.42f,
            TabLayoutIndicatorPolicy.normalizePositionOffset(0.42f),
            0f
        )
    }

    @Test
    fun clampsNonFiniteOffsetsToZero() {
        assertEquals(
            0f,
            TabLayoutIndicatorPolicy.normalizePositionOffset(Float.NaN),
            0f
        )
        assertEquals(
            0f,
            TabLayoutIndicatorPolicy.normalizePositionOffset(Float.POSITIVE_INFINITY),
            0f
        )
    }
}
