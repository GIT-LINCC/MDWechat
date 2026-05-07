package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleStylePolicyTest {
    @Test
    fun singleMessagesUseFullyRoundedCornersOnBothSides() {
        assertEquals(
            ChatBubbleStylePolicy.CornerRadiiDp(24f, 24f, 24f, 24f),
            ChatBubbleStylePolicy.cornerRadii(
                ChatBubbleStylePolicy.Side.LEFT,
                ChatBubbleStylePolicy.GroupPosition.SINGLE
            )
        )
        assertEquals(
            ChatBubbleStylePolicy.CornerRadiiDp(24f, 24f, 24f, 24f),
            ChatBubbleStylePolicy.cornerRadii(
                ChatBubbleStylePolicy.Side.RIGHT,
                ChatBubbleStylePolicy.GroupPosition.SINGLE
            )
        )
    }

    @Test
    fun leftMessageGroupUsesSmallCornersOnAvatarSideOnly() {
        assertEquals(
            ChatBubbleStylePolicy.CornerRadiiDp(24f, 24f, 24f, 4f),
            ChatBubbleStylePolicy.cornerRadii(
                ChatBubbleStylePolicy.Side.LEFT,
                ChatBubbleStylePolicy.GroupPosition.TOP
            )
        )
        assertEquals(
            ChatBubbleStylePolicy.CornerRadiiDp(4f, 24f, 24f, 4f),
            ChatBubbleStylePolicy.cornerRadii(
                ChatBubbleStylePolicy.Side.LEFT,
                ChatBubbleStylePolicy.GroupPosition.MIDDLE
            )
        )
        assertEquals(
            ChatBubbleStylePolicy.CornerRadiiDp(4f, 24f, 24f, 24f),
            ChatBubbleStylePolicy.cornerRadii(
                ChatBubbleStylePolicy.Side.LEFT,
                ChatBubbleStylePolicy.GroupPosition.BOTTOM
            )
        )
    }

    @Test
    fun rightMessageGroupUsesSmallCornersOnAvatarSideOnly() {
        assertEquals(
            ChatBubbleStylePolicy.CornerRadiiDp(24f, 24f, 4f, 24f),
            ChatBubbleStylePolicy.cornerRadii(
                ChatBubbleStylePolicy.Side.RIGHT,
                ChatBubbleStylePolicy.GroupPosition.TOP
            )
        )
        assertEquals(
            ChatBubbleStylePolicy.CornerRadiiDp(24f, 4f, 4f, 24f),
            ChatBubbleStylePolicy.cornerRadii(
                ChatBubbleStylePolicy.Side.RIGHT,
                ChatBubbleStylePolicy.GroupPosition.MIDDLE
            )
        )
        assertEquals(
            ChatBubbleStylePolicy.CornerRadiiDp(24f, 4f, 24f, 24f),
            ChatBubbleStylePolicy.cornerRadii(
                ChatBubbleStylePolicy.Side.RIGHT,
                ChatBubbleStylePolicy.GroupPosition.BOTTOM
            )
        )
    }

    @Test
    fun groupPositionFollowsPreviousAndNextMembership() {
        assertEquals(
            ChatBubbleStylePolicy.GroupPosition.SINGLE,
            ChatBubbleStylePolicy.groupPosition(hasPrevious = false, hasNext = false)
        )
        assertEquals(
            ChatBubbleStylePolicy.GroupPosition.TOP,
            ChatBubbleStylePolicy.groupPosition(hasPrevious = false, hasNext = true)
        )
        assertEquals(
            ChatBubbleStylePolicy.GroupPosition.MIDDLE,
            ChatBubbleStylePolicy.groupPosition(hasPrevious = true, hasNext = true)
        )
        assertEquals(
            ChatBubbleStylePolicy.GroupPosition.BOTTOM,
            ChatBubbleStylePolicy.groupPosition(hasPrevious = true, hasNext = false)
        )
    }

    @Test
    fun avatarAndNicknameVisibilityFollowClusterEdges() {
        assertTrue(ChatBubbleStylePolicy.showAvatar(ChatBubbleStylePolicy.GroupPosition.SINGLE))
        assertFalse(ChatBubbleStylePolicy.showAvatar(ChatBubbleStylePolicy.GroupPosition.TOP))
        assertFalse(ChatBubbleStylePolicy.showAvatar(ChatBubbleStylePolicy.GroupPosition.MIDDLE))
        assertTrue(ChatBubbleStylePolicy.showAvatar(ChatBubbleStylePolicy.GroupPosition.BOTTOM))

        assertTrue(ChatBubbleStylePolicy.showNickname(ChatBubbleStylePolicy.GroupPosition.SINGLE))
        assertTrue(ChatBubbleStylePolicy.showNickname(ChatBubbleStylePolicy.GroupPosition.TOP))
        assertFalse(ChatBubbleStylePolicy.showNickname(ChatBubbleStylePolicy.GroupPosition.MIDDLE))
        assertFalse(ChatBubbleStylePolicy.showNickname(ChatBubbleStylePolicy.GroupPosition.BOTTOM))
    }

    @Test
    fun topSpacingDependsOnClusterPosition() {
        assertEquals(16f, ChatBubbleStylePolicy.topMarginDp(ChatBubbleStylePolicy.GroupPosition.SINGLE), 0f)
        assertEquals(16f, ChatBubbleStylePolicy.topMarginDp(ChatBubbleStylePolicy.GroupPosition.TOP), 0f)
        assertEquals(2f, ChatBubbleStylePolicy.topMarginDp(ChatBubbleStylePolicy.GroupPosition.MIDDLE), 0f)
        assertEquals(2f, ChatBubbleStylePolicy.topMarginDp(ChatBubbleStylePolicy.GroupPosition.BOTTOM), 0f)
    }

    @Test
    fun nicknameRowsKeepBubbleCloseToName() {
        assertEquals(
            2f,
            ChatBubbleStylePolicy.bubbleTopMarginDp(
                ChatBubbleStylePolicy.GroupPosition.SINGLE,
                hasVisibleNickname = true
            ),
            0f
        )
        assertEquals(
            2f,
            ChatBubbleStylePolicy.bubbleTopMarginDp(
                ChatBubbleStylePolicy.GroupPosition.TOP,
                hasVisibleNickname = true
            ),
            0f
        )
        assertEquals(
            16f,
            ChatBubbleStylePolicy.bubbleTopMarginDp(
                ChatBubbleStylePolicy.GroupPosition.SINGLE,
                hasVisibleNickname = false
            ),
            0f
        )
    }

    @Test
    fun leftMessagesNeedKnownSenderBeforeGrouping() {
        val left = ChatBubbleStylePolicy.MessageCandidate(
            isTextMessage = true,
            side = ChatBubbleStylePolicy.Side.LEFT,
            senderKey = "alice"
        )
        val sameSender = left.copy(senderKey = "alice")
        val otherSender = left.copy(senderKey = "bob")
        val unknownSender = left.copy(senderKey = null)

        assertTrue(ChatBubbleStylePolicy.canGroupWith(left, sameSender))
        assertFalse(ChatBubbleStylePolicy.canGroupWith(left, otherSender))
        assertFalse(ChatBubbleStylePolicy.canGroupWith(left, unknownSender))
    }

    @Test
    fun rightMessagesGroupBySideButStopAtNonTextOrOppositeSide() {
        val right = ChatBubbleStylePolicy.MessageCandidate(
            isTextMessage = true,
            side = ChatBubbleStylePolicy.Side.RIGHT,
            senderKey = null
        )

        assertTrue(ChatBubbleStylePolicy.canGroupWith(right, right.copy()))
        assertFalse(
            ChatBubbleStylePolicy.canGroupWith(
                right,
                right.copy(side = ChatBubbleStylePolicy.Side.LEFT)
            )
        )
        assertFalse(ChatBubbleStylePolicy.canGroupWith(right, right.copy(isTextMessage = false)))
    }

    @Test
    fun messagesSeparatedByLargeVerticalGapStillGroupWhenTimeSegmentContinues() {
        val right = ChatBubbleStylePolicy.MessageCandidate(
            isTextMessage = true,
            side = ChatBubbleStylePolicy.Side.RIGHT,
            senderKey = null
        )

        assertTrue(
            ChatBubbleStylePolicy.canGroupWith(
                right,
                right.copy(),
                verticalGapDp = 2f
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.canGroupWith(
                right,
                right.copy(),
                verticalGapDp = 32f
            )
        )
    }

    @Test
    fun renderStatesAreResolvedFromMessageDataOnly() {
        val rows = listOf(
            row("1", ChatBubbleStylePolicy.Side.RIGHT, "self", 1_000L, "a"),
            row("2", ChatBubbleStylePolicy.Side.RIGHT, "self", 2_000L, "b"),
            row("3", ChatBubbleStylePolicy.Side.RIGHT, "self", 3_000L, "c"),
            row("4", ChatBubbleStylePolicy.Side.LEFT, "alice", 4_000L, "d")
        )

        val states = ChatBubbleStylePolicy.resolveRenderStates(rows)

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("1").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.MIDDLE, states.getValue("2").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("3").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.SINGLE, states.getValue("4").position)
    }

    @Test
    fun renderStatesStopAtTimeSeparator() {
        val rows = listOf(
            row("1", ChatBubbleStylePolicy.Side.RIGHT, "self", 1_000L, "a"),
            row("2", ChatBubbleStylePolicy.Side.RIGHT, "self", 2_000L, "b"),
            row("3", ChatBubbleStylePolicy.Side.RIGHT, "self", 10 * 60 * 1000L, "c")
        )

        val states = ChatBubbleStylePolicy.resolveRenderStates(rows)

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("1").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("2").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.SINGLE, states.getValue("3").position)
    }

    @Test
    fun renderStatesCarryVisualContractForNativePainter() {
        val rows = listOf(
            row("1", ChatBubbleStylePolicy.Side.LEFT, "alice", 1_000L, "a"),
            row("2", ChatBubbleStylePolicy.Side.LEFT, "alice", 2_000L, "b")
        )

        val states = ChatBubbleStylePolicy.resolveRenderStates(rows)

        assertEquals(ChatBubbleStylePolicy.cornerRadii(ChatBubbleStylePolicy.Side.LEFT, ChatBubbleStylePolicy.GroupPosition.TOP), states.getValue("1").cornerRadii)
        assertTrue(states.getValue("1").showNickname)
        assertFalse(states.getValue("1").showAvatar)
        assertEquals(ChatBubbleStylePolicy.cornerRadii(ChatBubbleStylePolicy.Side.LEFT, ChatBubbleStylePolicy.GroupPosition.BOTTOM), states.getValue("2").cornerRadii)
        assertFalse(states.getValue("2").showNickname)
        assertTrue(states.getValue("2").showAvatar)
    }

    private fun row(
        stableKey: String,
        side: ChatBubbleStylePolicy.Side,
        senderKey: String?,
        createTimeMs: Long,
        text: String
    ): ChatBubbleStylePolicy.MessageRow {
        return ChatBubbleStylePolicy.MessageRow(
            stableKey = stableKey,
            isTextMessage = true,
            side = side,
            senderKey = senderKey,
            createTimeMs = createTimeMs,
            contentText = text
        )
    }
}
