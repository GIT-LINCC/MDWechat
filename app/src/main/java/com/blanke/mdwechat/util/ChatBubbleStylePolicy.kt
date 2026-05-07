package com.blanke.mdwechat.util

object ChatBubbleStylePolicy {
    enum class Side {
        LEFT,
        RIGHT
    }

    enum class GroupPosition {
        SINGLE,
        TOP,
        MIDDLE,
        BOTTOM
    }

    data class CornerRadiiDp(
        val topLeft: Float,
        val topRight: Float,
        val bottomRight: Float,
        val bottomLeft: Float
    )

    data class MessageCandidate(
        val isTextMessage: Boolean,
        val side: Side?,
        val senderKey: String?
    )

    data class MessageRow(
        val stableKey: String,
        val isTextMessage: Boolean,
        val side: Side?,
        val senderKey: String?,
        val createTimeMs: Long?,
        val contentText: String?
    ) {
        fun toCandidate(): MessageCandidate {
            return MessageCandidate(
                isTextMessage = isTextMessage,
                side = side,
                senderKey = senderKey
            )
        }
    }

    data class RenderState(
        val stableKey: String,
        val side: Side,
        val position: GroupPosition,
        val showAvatar: Boolean,
        val showNickname: Boolean,
        val topMarginDp: Float,
        val cornerRadii: CornerRadiiDp
    )

    fun groupPosition(hasPrevious: Boolean, hasNext: Boolean): GroupPosition {
        return when {
            hasPrevious && hasNext -> GroupPosition.MIDDLE
            hasPrevious -> GroupPosition.BOTTOM
            hasNext -> GroupPosition.TOP
            else -> GroupPosition.SINGLE
        }
    }

    fun cornerRadii(
        side: Side,
        position: GroupPosition,
        largeRadiusDp: Float = 24f,
        smallRadiusDp: Float = 4f
    ): CornerRadiiDp {
        if (position == GroupPosition.SINGLE) {
            return CornerRadiiDp(largeRadiusDp, largeRadiusDp, largeRadiusDp, largeRadiusDp)
        }
        return when (side) {
            Side.LEFT -> when (position) {
                GroupPosition.TOP -> CornerRadiiDp(
                    largeRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp,
                    smallRadiusDp
                )
                GroupPosition.MIDDLE -> CornerRadiiDp(
                    smallRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp,
                    smallRadiusDp
                )
                GroupPosition.BOTTOM -> CornerRadiiDp(
                    smallRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp
                )
                GroupPosition.SINGLE -> CornerRadiiDp(
                    largeRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp
                )
            }
            Side.RIGHT -> when (position) {
                GroupPosition.TOP -> CornerRadiiDp(
                    largeRadiusDp,
                    largeRadiusDp,
                    smallRadiusDp,
                    largeRadiusDp
                )
                GroupPosition.MIDDLE -> CornerRadiiDp(
                    largeRadiusDp,
                    smallRadiusDp,
                    smallRadiusDp,
                    largeRadiusDp
                )
                GroupPosition.BOTTOM -> CornerRadiiDp(
                    largeRadiusDp,
                    smallRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp
                )
                GroupPosition.SINGLE -> CornerRadiiDp(
                    largeRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp,
                    largeRadiusDp
                )
            }
        }
    }

    fun showAvatar(position: GroupPosition): Boolean {
        return position == GroupPosition.SINGLE || position == GroupPosition.BOTTOM
    }

    fun showNickname(position: GroupPosition): Boolean {
        return position == GroupPosition.SINGLE || position == GroupPosition.TOP
    }

    fun topMarginDp(position: GroupPosition): Float {
        return when (position) {
            GroupPosition.SINGLE,
            GroupPosition.TOP -> 16f
            GroupPosition.MIDDLE,
            GroupPosition.BOTTOM -> 2f
        }
    }

    fun bubbleTopMarginDp(position: GroupPosition, hasVisibleNickname: Boolean): Float {
        return if (hasVisibleNickname) {
            2f
        } else {
            topMarginDp(position)
        }
    }

    fun resolveRenderStates(
        rows: List<MessageRow>,
        timeSeparatorGapMs: Long = 5 * 60 * 1000L
    ): Map<String, RenderState> {
        if (rows.isEmpty()) {
            return emptyMap()
        }
        val states = LinkedHashMap<String, RenderState>()
        rows.forEachIndexed { index, row ->
            val side = row.side ?: return@forEachIndexed
            if (!row.isTextMessage) {
                return@forEachIndexed
            }
            val previous = rows.getOrNull(index - 1)
            val next = rows.getOrNull(index + 1)
            val hasPrevious = !isTimeSplit(previous, row, timeSeparatorGapMs) &&
                    canGroupWith(row.toCandidate(), previous?.toCandidate())
            val hasNext = !isTimeSplit(row, next, timeSeparatorGapMs) &&
                    canGroupWith(row.toCandidate(), next?.toCandidate())
            val position = groupPosition(hasPrevious = hasPrevious, hasNext = hasNext)
            states[row.stableKey] = RenderState(
                stableKey = row.stableKey,
                side = side,
                position = position,
                showAvatar = showAvatar(position),
                showNickname = showNickname(position),
                topMarginDp = topMarginDp(position),
                cornerRadii = cornerRadii(side, position)
            )
        }
        return states
    }

    fun canGroupWith(
        current: MessageCandidate,
        neighbor: MessageCandidate?,
        verticalGapDp: Float = 0f,
        maxClusterGapDp: Float = Float.POSITIVE_INFINITY
    ): Boolean {
        if (neighbor == null) {
            return false
        }
        if (!current.isTextMessage || !neighbor.isTextMessage) {
            return false
        }
        if (current.side == null || current.side != neighbor.side) {
            return false
        }
        if (current.side == Side.RIGHT) {
            return true
        }

        val currentSender = current.senderKey?.takeIf { it.isNotBlank() } ?: return false
        val neighborSender = neighbor.senderKey?.takeIf { it.isNotBlank() } ?: return false
        return currentSender == neighborSender
    }

    private fun isTimeSplit(
        older: MessageRow?,
        newer: MessageRow?,
        thresholdMs: Long
    ): Boolean {
        val olderTime = older?.createTimeMs ?: return false
        val newerTime = newer?.createTimeMs ?: return false
        return kotlin.math.abs(newerTime - olderTime) >= thresholdMs
    }
}
