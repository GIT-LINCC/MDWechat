package com.blanke.mdwechat.util

object SettingsHeaderStyleResolver {
    private const val dayBaseColor = 0xFFEDEDED.toInt()
    private const val nightBaseColor = 0xFF333333.toInt()
    private val transparentSegments = setOf(
        "gxn",
        "gxv",
        "gxp",
        "o4w",
        "ovl",
        "hxi",
        "hn7",
        "hyd",
        "o4v",
        "headView",
        "statusSpacerView",
        "q1"
    )

    fun resolveBaseColor(isNightMode: Boolean): Int {
        return if (isNightMode) {
            nightBaseColor
        } else {
            dayBaseColor
        }
    }

    fun shouldKeepSegmentTransparent(resourceName: String): Boolean {
        return resourceName in transparentSegments
    }

    fun resolveTextColor(resourceName: String?, titleColor: Int, summaryColor: Int): Int {
        return when (resourceName) {
            "ouv", "opx" -> summaryColor
            else -> titleColor
        }
    }

    fun resolveIconTintColor(defaultColor: Int): Int {
        return defaultColor
    }

    fun transparentSegmentNames(): Set<String> {
        return transparentSegments
    }
}
