package com.blanke.mdwechat.util

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHeaderStyleResolverTest {
    @Test
    fun usesPageLightGrayAsHeaderBaseColorInDayMode() {
        assertEquals(
            0xFFEDEDED.toInt(),
            SettingsHeaderStyleResolver.resolveBaseColor(isNightMode = false)
        )
    }

    @Test
    fun keepsKnownHeaderCarrierSegmentsTransparent() {
        SettingsHeaderStyleResolver.transparentSegmentNames().forEach { resourceName ->
            assertTrue(
                "$resourceName should stay transparent so the row background remains uniform",
                SettingsHeaderStyleResolver.shouldKeepSegmentTransparent(resourceName)
            )
        }
    }

    @Test
    fun usesSummaryColorForWechatIdAndStatusText() {
        assertEquals(
            Color.DKGRAY,
            SettingsHeaderStyleResolver.resolveTextColor(
                resourceName = "ouv",
                titleColor = Color.BLACK,
                summaryColor = Color.DKGRAY
            )
        )
        assertEquals(
            Color.DKGRAY,
            SettingsHeaderStyleResolver.resolveTextColor(
                resourceName = "opx",
                titleColor = Color.BLACK,
                summaryColor = Color.DKGRAY
            )
        )
        assertEquals(
            Color.BLACK,
            SettingsHeaderStyleResolver.resolveTextColor(
                resourceName = "kbb",
                titleColor = Color.BLACK,
                summaryColor = Color.DKGRAY
            )
        )
    }
}
