package com.blanke.mdwechat.util

import com.blanke.mdwechat.Version

object SettingsHeaderBackgroundPolicy {
    private val minimumUnifiedHeaderVersion = Version("8.0.0")
    private val minimumImageCarrierVersion = Version("8.0.49")
    private const val imageViewClassName = "android.widget.ImageView"
    private const val frameLayoutClassName = "android.widget.FrameLayout"

    fun shouldUseUnifiedHeaderBackground(
        wxVersion: Version?,
        isSettingsPageTransparent: Boolean
    ): Boolean {
        if (wxVersion == null || isSettingsPageTransparent) {
            return false
        }
        return wxVersion.compareTo(minimumUnifiedHeaderVersion) >= 0
    }

    fun shouldReplaceWideImageCarrier(
        wxVersion: Version?,
        width: Int,
        height: Int
    ): Boolean {
        if (wxVersion == null || width <= 0 || height <= 0) {
            return false
        }
        return wxVersion.compareTo(minimumImageCarrierVersion) >= 0 && width >= height * 2
    }

    fun shouldStyleStatusOverlay(
        wxVersion: Version?,
        isSettingsPageTransparent: Boolean
    ): Boolean {
        if (wxVersion == null) {
            return false
        }
        return wxVersion.compareTo(minimumImageCarrierVersion) >= 0
    }

    fun shouldReplaceStatusOverlayCarrier(
        wxVersion: Version?,
        width: Int,
        height: Int
    ): Boolean {
        if (wxVersion == null || width <= 0 || height <= 0) {
            return false
        }
        return wxVersion.compareTo(minimumImageCarrierVersion) >= 0 && height >= width
    }

    fun isStatusOverlayContainerCandidate(
        firstChildClassName: String?,
        secondChildClassName: String?,
        childCount: Int,
        width: Int,
        height: Int,
        referenceWidth: Int,
        referenceHeight: Int
    ): Boolean {
        if (childCount < 2 || width <= 0 || height <= 0 || referenceWidth <= 0 || referenceHeight <= 0) {
            return false
        }
        if (firstChildClassName != imageViewClassName || secondChildClassName != frameLayoutClassName) {
            return false
        }
        return width >= referenceWidth && height * 2 >= referenceHeight
    }
}
