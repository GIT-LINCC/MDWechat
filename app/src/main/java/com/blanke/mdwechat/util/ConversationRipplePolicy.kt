package com.blanke.mdwechat.util

import com.blanke.mdwechat.Version

object ConversationRipplePolicy {
    private val minimumRootBackgroundRippleVersion = Version("8.0.49")

    fun shouldSuppressNativeConversationFeedback(
        wxVersion: Version?,
        sdkInt: Int,
        isRippleHookEnabled: Boolean,
        isConversationRow: Boolean
    ): Boolean {
        return isRippleHookEnabled
            && isConversationRow
            && shouldUseOverlayRipple(wxVersion, sdkInt)
    }

    fun shouldUseOverlayRipple(wxVersion: Version?, sdkInt: Int): Boolean {
        if (wxVersion == null) {
            return false
        }
        return wxVersion.compareTo(minimumRootBackgroundRippleVersion) >= 0 && sdkInt >= 23
    }

    fun shouldWrapRootBackground(wxVersion: Version?, sdkInt: Int): Boolean {
        if (wxVersion == null) {
            return false
        }
        return wxVersion.compareTo(minimumRootBackgroundRippleVersion) >= 0 && sdkInt in 21..22
    }
}
