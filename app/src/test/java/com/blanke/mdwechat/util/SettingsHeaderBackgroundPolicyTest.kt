package com.blanke.mdwechat.util

import com.blanke.mdwechat.Version
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHeaderBackgroundPolicyTest {
    @Test
    fun usesUnifiedHeaderBackgroundForModernWechatWhenSettingsPageIsOpaque() {
        assertTrue(
            SettingsHeaderBackgroundPolicy.shouldUseUnifiedHeaderBackground(
                Version("8.0.0"),
                isSettingsPageTransparent = false
            )
        )
        assertTrue(
            SettingsHeaderBackgroundPolicy.shouldUseUnifiedHeaderBackground(
                Version("8.0.49"),
                isSettingsPageTransparent = false
            )
        )
    }

    @Test
    fun skipsUnifiedHeaderBackgroundForLegacyWechatOrTransparentMode() {
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldUseUnifiedHeaderBackground(
                Version("7.0.22"),
                isSettingsPageTransparent = false
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldUseUnifiedHeaderBackground(
                Version("8.0.49"),
                isSettingsPageTransparent = true
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldUseUnifiedHeaderBackground(
                null,
                isSettingsPageTransparent = false
            )
        )
    }

    @Test
    fun replacesWideImageCarrierOnlyOnWechat8049AndAbove() {
        assertTrue(
            SettingsHeaderBackgroundPolicy.shouldReplaceWideImageCarrier(
                Version("8.0.49"),
                width = 1216,
                height = 538
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldReplaceWideImageCarrier(
                Version("8.0.48"),
                width = 1216,
                height = 538
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldReplaceWideImageCarrier(
                Version("8.0.49"),
                width = 195,
                height = 195
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldReplaceWideImageCarrier(
                null,
                width = 1216,
                height = 538
            )
        )
    }

    @Test
    fun stylesStatusOverlayForModernWechatEvenInTransparentMode() {
        assertTrue(
            SettingsHeaderBackgroundPolicy.shouldStyleStatusOverlay(
                Version("8.0.49"),
                isSettingsPageTransparent = false
            )
        )
        assertTrue(
            SettingsHeaderBackgroundPolicy.shouldStyleStatusOverlay(
                Version("8.0.49"),
                isSettingsPageTransparent = true
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldStyleStatusOverlay(
                Version("8.0.48"),
                isSettingsPageTransparent = false
            )
        )
    }

    @Test
    fun replacesTallStatusOverlayCarrierOnlyOnWechat8049AndAbove() {
        assertTrue(
            SettingsHeaderBackgroundPolicy.shouldReplaceStatusOverlayCarrier(
                Version("8.0.49"),
                width = 1216,
                height = 1975
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldReplaceStatusOverlayCarrier(
                Version("8.0.48"),
                width = 1216,
                height = 1975
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.shouldReplaceStatusOverlayCarrier(
                Version("8.0.49"),
                width = 1216,
                height = 538
            )
        )
    }

    @Test
    fun recognizesSettingsStatusOverlayContainerByShapeAndChildren() {
        assertTrue(
            SettingsHeaderBackgroundPolicy.isStatusOverlayContainerCandidate(
                firstChildClassName = "android.widget.ImageView",
                secondChildClassName = "android.widget.FrameLayout",
                childCount = 4,
                width = 1216,
                height = 1975,
                referenceWidth = 1216,
                referenceHeight = 2688
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.isStatusOverlayContainerCandidate(
                firstChildClassName = "android.widget.LinearLayout",
                secondChildClassName = "android.widget.FrameLayout",
                childCount = 4,
                width = 1216,
                height = 1975,
                referenceWidth = 1216,
                referenceHeight = 2688
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.isStatusOverlayContainerCandidate(
                firstChildClassName = "android.widget.ImageView",
                secondChildClassName = "android.widget.FrameLayout",
                childCount = 1,
                width = 1216,
                height = 1975,
                referenceWidth = 1216,
                referenceHeight = 2688
            )
        )
        assertFalse(
            SettingsHeaderBackgroundPolicy.isStatusOverlayContainerCandidate(
                firstChildClassName = "android.widget.ImageView",
                secondChildClassName = "android.widget.FrameLayout",
                childCount = 4,
                width = 800,
                height = 1000,
                referenceWidth = 1216,
                referenceHeight = 2688
            )
        )
    }
}
