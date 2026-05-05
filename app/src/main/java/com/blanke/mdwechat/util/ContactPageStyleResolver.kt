package com.blanke.mdwechat.util

import com.blanke.mdwechat.Version

object ContactPageStyleResolver {
    private const val dayPageBackgroundColor = 0xFFEDEDED.toInt()
    private const val nightPageBackgroundColor = 0xFF333333.toInt()
    private val wxVersionKeepsNativeHeaderIconTree = Version("8.0.49")
    private val indexedContactRowMarkers = setOf("cg5", "kbo", "kbq")

    fun resolvePageBackgroundColor(isNightMode: Boolean): Int {
        return if (isNightMode) {
            nightPageBackgroundColor
        } else {
            dayPageBackgroundColor
        }
    }

    fun shouldUseTransparentItemSurface(): Boolean {
        return true
    }

    fun shouldStyleIndexedContactRow(resourceNames: Set<String>): Boolean {
        return indexedContactRowMarkers.all { it in resourceNames }
    }

    fun shouldWrapHeaderEntryIcon(wxVersion: Version?): Boolean {
        return wxVersion != null && wxVersion < wxVersionKeepsNativeHeaderIconTree
    }
}
