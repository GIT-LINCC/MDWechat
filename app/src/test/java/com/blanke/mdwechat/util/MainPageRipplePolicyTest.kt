package com.blanke.mdwechat.util

import com.blanke.mdwechat.Version
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPageRipplePolicyTest {
    @Test
    fun doesNotUseOverlayRippleOnCurrentWechatVersions() {
        assertFalse(
            MainPageRipplePolicy.shouldUseOverlayRipple(
                Version("8.0.49"),
                23
            )
        )
        assertFalse(
            MainPageRipplePolicy.shouldUseOverlayRipple(
                Version("8.0.49"),
                35
            )
        )
        assertFalse(
            MainPageRipplePolicy.shouldUseOverlayRipple(
                Version("8.0.48"),
                35
            )
        )
        assertFalse(
            MainPageRipplePolicy.shouldUseOverlayRipple(
                Version("8.0.49"),
                22
            )
        )
    }

    @Test
    fun wrapsRootBackgroundOnApi21AndAboveForWechat8049AndAbove() {
        assertTrue(
            MainPageRipplePolicy.shouldWrapRootBackground(
                Version("8.0.49"),
                21
            )
        )
        assertTrue(
            MainPageRipplePolicy.shouldWrapRootBackground(
                Version("8.0.49"),
                22
            )
        )
        assertTrue(
            MainPageRipplePolicy.shouldWrapRootBackground(
                Version("8.0.49"),
                23
            )
        )
        assertTrue(
            MainPageRipplePolicy.shouldWrapRootBackground(
                Version("8.0.49"),
                35
            )
        )
        assertFalse(
            MainPageRipplePolicy.shouldWrapRootBackground(
                Version("8.0.48"),
                22
            )
        )
    }

    @Test
    fun preservesWechatItemBackgroundsOnlyOnWechat8049AndAbove() {
        assertTrue(MainPageRipplePolicy.shouldPreserveNativeItemBackground(Version("8.0.49")))
        assertTrue(MainPageRipplePolicy.shouldPreserveNativeItemBackground(Version("8.0.50")))
        assertFalse(MainPageRipplePolicy.shouldPreserveNativeItemBackground(Version("8.0.48")))
        assertFalse(MainPageRipplePolicy.shouldPreserveNativeItemBackground(null))
    }
}
