package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaterialTabIconTransitionPolicyTest {
    @Test
    fun clickSelectionKeepsTheLegacyLocalPillAndSkipsV2IconMotion() {
        val plan = MaterialTabIconTransitionPolicy.selectionPlan(
            previousPosition = 0,
            selectedPosition = 2,
            tabCount = 4,
            source = MaterialTabIconTransitionPolicy.SelectionSource.CLICK
        )

        assertEquals(2, plan?.selectedPosition)
        assertEquals(false, plan?.usesSlidingPill)
        assertNull(plan?.iconMotion)
    }

    @Test
    fun swipeSelectionUsesSlidingPillAndTheSelectedTabsV2Motion() {
        val plan = MaterialTabIconTransitionPolicy.selectionPlan(
            previousPosition = 1,
            selectedPosition = 2,
            tabCount = 4,
            source = MaterialTabIconTransitionPolicy.SelectionSource.SWIPE
        )

        assertEquals(2, plan?.selectedPosition)
        assertEquals(true, plan?.usesSlidingPill)
        assertEquals(MaterialTabIconTransitionPolicy.IconMotion.COMPASS_SPIN, plan?.iconMotion)
    }

    @Test
    fun mapsEveryTabToItsPreviewMotion() {
        assertEquals(
            MaterialTabIconTransitionPolicy.IconMotion.CHAT_JELLY,
            MaterialTabIconTransitionPolicy.motionForPosition(0)
        )
        assertEquals(
            MaterialTabIconTransitionPolicy.IconMotion.CONTACT_FLIP,
            MaterialTabIconTransitionPolicy.motionForPosition(1)
        )
        assertEquals(
            MaterialTabIconTransitionPolicy.IconMotion.COMPASS_SPIN,
            MaterialTabIconTransitionPolicy.motionForPosition(2)
        )
        assertEquals(
            MaterialTabIconTransitionPolicy.IconMotion.PERSON_BOUNCE,
            MaterialTabIconTransitionPolicy.motionForPosition(3)
        )
    }

    @Test
    fun ignoresInitialProgrammaticSelectionAndReselection() {
        assertNull(
            MaterialTabIconTransitionPolicy.selectionPlan(
                previousPosition = -1,
                selectedPosition = 0,
                tabCount = 4,
                source = MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
            )
        )
        assertNull(
            MaterialTabIconTransitionPolicy.selectionPlan(
                previousPosition = 1,
                selectedPosition = 1,
                tabCount = 4,
                source = MaterialTabIconTransitionPolicy.SelectionSource.SWIPE
            )
        )
    }

    @Test
    fun ignoresInvalidTabIndexes() {
        assertNull(
            MaterialTabIconTransitionPolicy.selectionPlan(
                previousPosition = 0,
                selectedPosition = 4,
                tabCount = 4,
                source = MaterialTabIconTransitionPolicy.SelectionSource.SWIPE
            )
        )
        assertNull(
            MaterialTabIconTransitionPolicy.selectionPlan(
                previousPosition = 4,
                selectedPosition = 0,
                tabCount = 4,
                source = MaterialTabIconTransitionPolicy.SelectionSource.SWIPE
            )
        )
    }

    @Test
    fun usesPreviewMotionTiming() {
        assertEquals(600L, MaterialTabIconTransitionPolicy.durationForMotion(MaterialTabIconTransitionPolicy.IconMotion.CHAT_JELLY))
        assertEquals(700L, MaterialTabIconTransitionPolicy.durationForMotion(MaterialTabIconTransitionPolicy.IconMotion.CONTACT_FLIP))
        assertEquals(700L, MaterialTabIconTransitionPolicy.durationForMotion(MaterialTabIconTransitionPolicy.IconMotion.COMPASS_SPIN))
        assertEquals(600L, MaterialTabIconTransitionPolicy.durationForMotion(MaterialTabIconTransitionPolicy.IconMotion.PERSON_BOUNCE))
    }

    @Test
    fun usesLegacySelectedIconScaleAtRest() {
        assertEquals(1.15f, MaterialTabIconTransitionPolicy.selectedRestScale, 0f)
        assertEquals(1f, MaterialTabIconTransitionPolicy.restingScale, 0f)
    }

    @Test
    fun clickSelectionUsesTheLegacyFillAndPillTiming() {
        assertEquals(400L, MaterialTabIconTransitionPolicy.iconFillDurationMs)
        assertEquals(400L, MaterialTabIconTransitionPolicy.clickPillDurationMs)
        assertEquals(0.5f, MaterialTabIconTransitionPolicy.activePillCollapsedScaleX, 0f)
        assertEquals(1f, MaterialTabIconTransitionPolicy.activePillExpandedScale, 0f)
        assertEquals(450L, MaterialTabIconTransitionPolicy.swipePillSettleDurationMs)
    }

    @Test
    fun materialTabDesignDisablesRippleBecauseThePillOwnsPressedFeedback() {
        assertEquals(false, MaterialTabIconTransitionPolicy.rippleEnabled)
    }

    @Test
    fun usesPreviewIconColorsForBuiltInMaterialIcons() {
        assertEquals(0xFF001D35.toInt(), MaterialTabIconTransitionPolicy.activeIconColor)
        assertEquals(0xFF444746.toInt(), MaterialTabIconTransitionPolicy.inactiveIconColor)
    }

    @Test
    fun clampsSwipeOffsetIntoAPageFraction() {
        assertEquals(0f, MaterialTabIconTransitionPolicy.swipeFraction(Float.NaN), 0f)
        assertEquals(0f, MaterialTabIconTransitionPolicy.swipeFraction(-0.3f), 0f)
        assertEquals(0.42f, MaterialTabIconTransitionPolicy.swipeFraction(0.42f), 0f)
        assertEquals(1f, MaterialTabIconTransitionPolicy.swipeFraction(1.4f), 0f)
    }

    @Test
    fun showsTheSlidingPillOnlyWhileThereIsARealSwipeOffset() {
        assertEquals(false, MaterialTabIconTransitionPolicy.isActiveSwipeOffset(0f, hasNextTab = true))
        assertEquals(false, MaterialTabIconTransitionPolicy.isActiveSwipeOffset(0.4f, hasNextTab = false))
        assertEquals(true, MaterialTabIconTransitionPolicy.isActiveSwipeOffset(0.4f, hasNextTab = true))
    }

    @Test
    fun buildsTranslucentContainerColorFromIndicatorColor() {
        assertEquals(
            0x3003A9F4,
            MaterialTabIconTransitionPolicy.containerColor(0xFF03A9F4.toInt())
        )
        assertEquals(
            0x00000000,
            MaterialTabIconTransitionPolicy.containerColor(0x00000000)
        )
    }

    @Test
    fun lowersTheWechatIconSlightlyToMatchOpticalCenter() {
        assertEquals(3f, MaterialTabIconTransitionPolicy.iconTranslationYDp(0), 0f)
        assertEquals(1f, MaterialTabIconTransitionPolicy.iconTranslationYDp(1), 0f)
    }
}
