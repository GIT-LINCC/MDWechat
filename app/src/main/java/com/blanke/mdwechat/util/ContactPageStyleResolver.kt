package com.blanke.mdwechat.util

object ContactPageStyleResolver {
    private const val dayPageBackgroundColor = 0xFFEDEDED.toInt()
    private const val nightPageBackgroundColor = 0xFF333333.toInt()
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
}
