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

    @Test
    fun regularIndicatorUsesFullTabWidth() {
        assertEquals(false, TabLayoutIndicatorPolicy.indicatorOnContent(isSmall = false))
    }

    @Test
    fun smallIndicatorUsesContentWidth() {
        assertEquals(true, TabLayoutIndicatorPolicy.indicatorOnContent(isSmall = true))
    }

    @Test
    fun regularIndicatorIsVisiblyTallerThanSmallIndicator() {
        assertEquals(2f, TabLayoutIndicatorPolicy.indicatorHeightDp(isSmall = false), 0f)
        assertEquals(1f, TabLayoutIndicatorPolicy.indicatorHeightDp(isSmall = true), 0f)
    }

    @Test
    fun topTabContentKeepsSmallGapBelowIndicator() {
        assertEquals(4f, TabLayoutIndicatorPolicy.topContentGapDp(), 0f)
    }

    @Test
    fun topTabAppliesContentOffsetEvenWhenActionBarWasAlreadyHidden() {
        assertEquals(
            true,
            TabLayoutIndicatorPolicy.shouldApplyTopContentOffset(
                isTopTabLayout = true,
                actionBarHeight = 0,
                quitFix = false
            )
        )
    }

    @Test
    fun nonTopTabStillWaitsForActionBarHeightBeforeOffset() {
        assertEquals(
            false,
            TabLayoutIndicatorPolicy.shouldApplyTopContentOffset(
                isTopTabLayout = false,
                actionBarHeight = 0,
                quitFix = false
            )
        )
    }
}
