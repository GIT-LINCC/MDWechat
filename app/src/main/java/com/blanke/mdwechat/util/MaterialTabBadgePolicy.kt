package com.blanke.mdwechat.util

object MaterialTabBadgePolicy {
    enum class Mode {
        NUMBER,
        DOT,
        HIDDEN
    }

    fun resolveMode(count: Int): Mode = when {
        count > 0 -> Mode.NUMBER
        count < 0 -> Mode.DOT
        else -> Mode.HIDDEN
    }
}
