package com.blanke.mdwechat.util

import com.blanke.mdwechat.Version

object MainPageRipplePolicy {
    private val minimumOverlayRippleVersion = Version("8.0.49")

    fun shouldPreserveNativeItemBackground(wxVersion: Version?): Boolean {
        if (wxVersion == null) {
            return false
        }
        return wxVersion.compareTo(minimumOverlayRippleVersion) >= 0
    }

    fun shouldUseOverlayRipple(wxVersion: Version?, sdkInt: Int): Boolean {
        return false
    }

    fun shouldWrapRootBackground(wxVersion: Version?, sdkInt: Int): Boolean {
        return shouldPreserveNativeItemBackground(wxVersion) && sdkInt >= 21
    }
}
