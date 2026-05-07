package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun defaultBubblePaletteKeepsModernColorsAndRemovesWhiteOutline() {
        val right = ChatBubbleStylePolicy.bubblePalette(ChatBubbleStylePolicy.Side.RIGHT)
        val left = ChatBubbleStylePolicy.bubblePalette(ChatBubbleStylePolicy.Side.LEFT)

        assertEquals(ChatBubbleStylePolicy.DEFAULT_RIGHT_BUBBLE_COLOR, right.bubbleColor)
        assertEquals(ChatBubbleStylePolicy.DEFAULT_RIGHT_TEXT_COLOR, right.textColor)
        assertNotEquals(right.textColor, right.semanticTextColor)
        assertEquals(ChatBubbleStylePolicy.TRANSPARENT_COLOR, right.strokeColor)
        assertEquals(0f, right.strokeWidthDp, 0f)
        assertEquals(ChatBubbleStylePolicy.DEFAULT_LEFT_BUBBLE_COLOR, left.bubbleColor)
        assertNotEquals(left.textColor, left.semanticTextColor)
        assertEquals(ChatBubbleStylePolicy.TRANSPARENT_COLOR, left.strokeColor)
        assertEquals(0f, left.strokeWidthDp, 0f)
    }

    @Test
    fun customBubbleTintOverridesSideFillColors() {
        val config = ChatBubbleStylePolicy.BubbleColorConfig(
            useCustomBubbleTint = true,
            leftBubbleTint = 0xFF112233.toInt(),
            rightBubbleTint = 0xFF445566.toInt()
        )

        assertEquals(
            0xFF112233.toInt(),
            ChatBubbleStylePolicy.bubblePalette(ChatBubbleStylePolicy.Side.LEFT, config).bubbleColor
        )
        assertEquals(
            0xFF445566.toInt(),
            ChatBubbleStylePolicy.bubblePalette(ChatBubbleStylePolicy.Side.RIGHT, config).bubbleColor
        )
    }

    @Test
    fun customTextColorsOverrideSideTextColors() {
        val config = ChatBubbleStylePolicy.BubbleColorConfig(
            useCustomTextColor = true,
            leftTextColor = 0xFFABCDEF.toInt(),
            rightTextColor = 0xFF123456.toInt()
        )

        assertEquals(
            0xFFABCDEF.toInt(),
            ChatBubbleStylePolicy.bubblePalette(ChatBubbleStylePolicy.Side.LEFT, config).textColor
        )
        assertEquals(
            0xFF123456.toInt(),
            ChatBubbleStylePolicy.bubblePalette(ChatBubbleStylePolicy.Side.RIGHT, config).textColor
        )
    }

    @Test
    fun paletteSignatureChangesWhenCustomColorsChange() {
        val first = ChatBubbleStylePolicy.bubblePalette(
            ChatBubbleStylePolicy.Side.RIGHT,
            ChatBubbleStylePolicy.BubbleColorConfig(
                useCustomBubbleTint = true,
                rightBubbleTint = 0xFF445566.toInt()
            )
        )
        val second = ChatBubbleStylePolicy.bubblePalette(
            ChatBubbleStylePolicy.Side.RIGHT,
            ChatBubbleStylePolicy.BubbleColorConfig(
                useCustomBubbleTint = true,
                rightBubbleTint = 0xFF667788.toInt()
            )
        )

        assertNotEquals(first.signature, second.signature)
    }

    @Test
    fun semanticTextColorKeepsBubbleHueForBlueSenderBubble() {
        val color = ChatBubbleStylePolicy.dynamicSemanticTextColor(0xFF0084FF.toInt())

        assertTrue(blueOf(color) > redOf(color))
        assertTrue(blueOf(color) >= greenOf(color))
        assertTrue(luminance(color) > 180)
    }

    @Test
    fun semanticTextColorUsesMaterialBlueForNeutralBubbles() {
        val color = ChatBubbleStylePolicy.dynamicSemanticTextColor(0xFF222222.toInt())

        assertTrue(blueOf(color) > redOf(color))
        assertTrue(blueOf(color) > greenOf(color))
        assertTrue(luminance(color) > 180)
    }

    @Test
    fun semanticTextColorDarkensHueForLightWarmBubbles() {
        val color = ChatBubbleStylePolicy.dynamicSemanticTextColor(0xFFFFE8E0.toInt())

        assertTrue(redOf(color) > blueOf(color))
        assertTrue(luminance(color) < 130)
    }

    @Test
    fun cachedRightBubbleShapeCanGrowWhenNewNeighborArrives() {
        assertTrue(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.Side.RIGHT,
                ChatBubbleStylePolicy.GroupPosition.SINGLE,
                ChatBubbleStylePolicy.GroupPosition.TOP
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.Side.RIGHT,
                ChatBubbleStylePolicy.GroupPosition.BOTTOM,
                ChatBubbleStylePolicy.GroupPosition.MIDDLE
            )
        )
        assertFalse(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.Side.RIGHT,
                ChatBubbleStylePolicy.GroupPosition.MIDDLE,
                ChatBubbleStylePolicy.GroupPosition.BOTTOM
            )
        )
        assertFalse(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.Side.LEFT,
                ChatBubbleStylePolicy.GroupPosition.SINGLE,
                ChatBubbleStylePolicy.GroupPosition.TOP
            )
        )
    }

    @Test
    fun appendedRightMessageRegroupsExistingSingleMessage() {
        val before = ChatBubbleStylePolicy.resolveRenderStates(
            listOf(row("1", ChatBubbleStylePolicy.Side.RIGHT, "self", 1_000L, "test"))
        )
        val after = ChatBubbleStylePolicy.resolveRenderStates(
            listOf(
                row("1", ChatBubbleStylePolicy.Side.RIGHT, "self", 1_000L, "test"),
                row("2", ChatBubbleStylePolicy.Side.RIGHT, "self", 2_000L, "test")
            )
        )

        assertEquals(ChatBubbleStylePolicy.GroupPosition.SINGLE, before.getValue("1").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, after.getValue("1").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, after.getValue("2").position)
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
    fun nonTextMessagesStopRenderStateGrouping() {
        val rows = listOf(
            row("1", ChatBubbleStylePolicy.Side.RIGHT, "self", 1_000L, "a"),
            row("2", ChatBubbleStylePolicy.Side.RIGHT, "self", 2_000L, "b"),
            row("img", ChatBubbleStylePolicy.Side.RIGHT, "self", 3_000L, "[image]", isTextMessage = false)
        )

        val states = ChatBubbleStylePolicy.resolveRenderStates(rows)

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("1").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("2").position)
        assertFalse(states.containsKey("img"))
    }

    @Test
    fun wechatImageXmlIsNotTextLikeWhenTypeIsMissing() {
        assertFalse(
            ChatBubbleStylePolicy.isTextLikeWechatMessage(
                null,
                "wxid_abc:\n<msg><img aeskey=\"x\" /></msg>"
            )
        )
        assertFalse(ChatBubbleStylePolicy.isTextLikeWechatMessage(null, null))
        assertTrue(ChatBubbleStylePolicy.isTextLikeWechatMessage(null, "plain text"))
        assertTrue(
            ChatBubbleStylePolicy.isTextLikeWechatMessage(
                49,
                "<msg><appmsg><refermsg><content>reply</content></refermsg></appmsg></msg>"
            )
        )
    }

    @Test
    fun voiceAndCallRowsAreStylableBubbleMessages() {
        assertTrue(ChatBubbleStylePolicy.isStylableWechatBubbleMessage(34, "<msg><voicemsg /></msg>"))
        assertTrue(ChatBubbleStylePolicy.isStylableWechatBubbleMessage(50, "通话时长 01:48"))
        assertTrue(ChatBubbleStylePolicy.isStylableWechatBubbleMessage(1, "plain text"))
        assertFalse(ChatBubbleStylePolicy.isStylableWechatBubbleMessage(3, "<msg><img aeskey=\"x\" /></msg>"))
    }

    @Test
    fun voiceRowsParticipateInMessageGrouping() {
        val states = ChatBubbleStylePolicy.resolveRenderStates(
            listOf(
                row("voice1", ChatBubbleStylePolicy.Side.LEFT, "alice", 1_000L, "4\"", isTextMessage = true),
                row("voice2", ChatBubbleStylePolicy.Side.LEFT, "alice", 2_000L, "14\"", isTextMessage = true)
            )
        )

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("voice1").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("voice2").position)
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
        text: String,
        isTextMessage: Boolean = true
    ): ChatBubbleStylePolicy.MessageRow {
        return ChatBubbleStylePolicy.MessageRow(
            stableKey = stableKey,
            isTextMessage = isTextMessage,
            side = side,
            senderKey = senderKey,
            createTimeMs = createTimeMs,
            contentText = text
        )
    }

    private fun luminance(color: Int): Int {
        return (redOf(color) * 299 + greenOf(color) * 587 + blueOf(color) * 114) / 1000
    }

    private fun redOf(color: Int): Int = color ushr 16 and 0xFF

    private fun greenOf(color: Int): Int = color ushr 8 and 0xFF

    private fun blueOf(color: Int): Int = color and 0xFF
}
