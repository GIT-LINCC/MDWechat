package com.blanke.mdwechat.util

data class MaterialTabSelectionPlan(
    val selectedPosition: Int,
    val usesSlidingPill: Boolean,
    val iconMotion: MaterialTabIconTransitionPolicy.IconMotion?
)

object MaterialTabIconTransitionPolicy {
    enum class SelectionSource {
        CLICK,
        SWIPE,
        PROGRAMMATIC
    }

    enum class IconMotion {
        CHAT_JELLY,
        CONTACT_FLIP,
        COMPASS_SPIN,
        PERSON_BOUNCE
    }

    private const val containerAlpha = 0x30

    const val chatJellyDurationMs = 600L
    const val contactFlipDurationMs = 700L
    const val compassSpinDurationMs = 700L
    const val personBounceDurationMs = 600L
    const val iconFillDurationMs = 400L
    const val clickPillDurationMs = 400L
    const val swipePillSettleDurationMs = 450L
    const val activePillCollapsedScaleX = 0.5f
    const val activePillExpandedScale = 1f
    const val restingScale = 1f
    const val selectedRestScale = 1.15f
    const val restingAlpha = 1f
    const val rippleEnabled = false
    const val activeIconColor = 0xFF001D35.toInt()
    const val inactiveIconColor = 0xFF444746.toInt()

    fun selectionPlan(
        previousPosition: Int,
        selectedPosition: Int,
        tabCount: Int,
        source: SelectionSource
    ): MaterialTabSelectionPlan? {
        if (previousPosition == selectedPosition) {
            return null
        }
        if (previousPosition !in 0 until tabCount || selectedPosition !in 0 until tabCount) {
            return null
        }
        return when (source) {
            SelectionSource.CLICK -> MaterialTabSelectionPlan(
                selectedPosition = selectedPosition,
                usesSlidingPill = false,
                iconMotion = null
            )
            SelectionSource.SWIPE -> MaterialTabSelectionPlan(
                selectedPosition = selectedPosition,
                usesSlidingPill = true,
                iconMotion = motionForPosition(selectedPosition)
            )
            SelectionSource.PROGRAMMATIC -> null
        }
    }

    fun motionForPosition(position: Int): IconMotion = when (position) {
        0 -> IconMotion.CHAT_JELLY
        1 -> IconMotion.CONTACT_FLIP
        2 -> IconMotion.COMPASS_SPIN
        else -> IconMotion.PERSON_BOUNCE
    }

    fun durationForMotion(motion: IconMotion): Long = when (motion) {
        IconMotion.CHAT_JELLY -> chatJellyDurationMs
        IconMotion.CONTACT_FLIP -> contactFlipDurationMs
        IconMotion.COMPASS_SPIN -> compassSpinDurationMs
        IconMotion.PERSON_BOUNCE -> personBounceDurationMs
    }

    fun swipeFraction(positionOffset: Float): Float {
        if (!positionOffset.isFinite()) {
            return 0f
        }
        return positionOffset.coerceIn(0f, 1f)
    }

    fun isActiveSwipeOffset(positionOffset: Float, hasNextTab: Boolean): Boolean {
        return hasNextTab && swipeFraction(positionOffset) > 0f
    }

    fun containerColor(indicatorColor: Int): Int {
        if ((indicatorColor ushr 24) == 0) {
            return 0x00000000
        }
        return (containerAlpha shl 24) or (indicatorColor and 0x00FFFFFF)
    }

    fun iconTranslationYDp(position: Int): Float {
        return if (position == 0) 3f else 1f
    }
}
