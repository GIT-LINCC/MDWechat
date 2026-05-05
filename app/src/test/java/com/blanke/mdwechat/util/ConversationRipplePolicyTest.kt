package com.blanke.mdwechat.util

import com.blanke.mdwechat.Version
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRipplePolicyTest {
    @Test
    fun suppressesNativeConversationRowFeedbackOnlyForOverlayRippleRows() {
        assertTrue(
            ConversationRipplePolicy.shouldSuppressNativeConversationFeedback(
                Version("8.0.49"),
                35,
                isRippleHookEnabled = true,
                isConversationRow = true
            )
        )
        assertFalse(
            ConversationRipplePolicy.shouldSuppressNativeConversationFeedback(
                Version("8.0.49"),
                35,
                isRippleHookEnabled = false,
                isConversationRow = true
            )
        )
        assertFalse(
            ConversationRipplePolicy.shouldSuppressNativeConversationFeedback(
                Version("8.0.49"),
                35,
                isRippleHookEnabled = true,
                isConversationRow = false
            )
        )
        assertFalse(
            ConversationRipplePolicy.shouldSuppressNativeConversationFeedback(
                Version("8.0.49"),
                22,
                isRippleHookEnabled = true,
                isConversationRow = true
            )
        )
    }

    @Test
    fun usesOverlayRippleForWechat8049AndAboveOnApi23Plus() {
        assertTrue(
            ConversationRipplePolicy.shouldUseOverlayRipple(
                Version("8.0.49"),
                23
            )
        )
        assertFalse(
            ConversationRipplePolicy.shouldUseOverlayRipple(
                Version("8.0.49"),
                22
            )
        )
    }

    @Test
    fun usesRootBackgroundFallbackOnlyOnApi21And22() {
        assertTrue(
            ConversationRipplePolicy.shouldWrapRootBackground(
                Version("8.0.49"),
                21
            )
        )
        assertTrue(
            ConversationRipplePolicy.shouldWrapRootBackground(
                Version("8.0.49"),
                22
            )
        )
    }

    @Test
    fun skipsRootBackgroundRippleBeforeWechat8049() {
        assertFalse(
            ConversationRipplePolicy.shouldWrapRootBackground(
                Version("8.0.48"),
                33
            )
        )
    }

    @Test
    fun skipsRootBackgroundRippleBelowApi21OrOnApi23Plus() {
        assertFalse(
            ConversationRipplePolicy.shouldWrapRootBackground(
                Version("8.0.49"),
                20
            )
        )
        assertFalse(
            ConversationRipplePolicy.shouldWrapRootBackground(
                Version("8.0.49"),
                23
            )
        )
    }

}
