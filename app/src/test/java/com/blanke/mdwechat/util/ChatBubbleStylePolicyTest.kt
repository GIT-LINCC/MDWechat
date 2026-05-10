package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun visibleBoundaryRemovesNextMembershipOnly() {
        assertEquals(
            ChatBubbleStylePolicy.GroupPosition.SINGLE,
            ChatBubbleStylePolicy.positionWithoutNext(ChatBubbleStylePolicy.GroupPosition.TOP)
        )
        assertEquals(
            ChatBubbleStylePolicy.GroupPosition.BOTTOM,
            ChatBubbleStylePolicy.positionWithoutNext(ChatBubbleStylePolicy.GroupPosition.MIDDLE)
        )
        assertEquals(
            ChatBubbleStylePolicy.GroupPosition.BOTTOM,
            ChatBubbleStylePolicy.positionWithoutNext(ChatBubbleStylePolicy.GroupPosition.BOTTOM)
        )
        assertEquals(
            ChatBubbleStylePolicy.GroupPosition.SINGLE,
            ChatBubbleStylePolicy.positionWithoutNext(ChatBubbleStylePolicy.GroupPosition.SINGLE)
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

        assertTrue(
            ChatBubbleStylePolicy.showNickname(
                ChatBubbleStylePolicy.Side.LEFT,
                ChatBubbleStylePolicy.GroupPosition.SINGLE
            )
        )
        assertFalse(
            ChatBubbleStylePolicy.showNickname(
                ChatBubbleStylePolicy.Side.RIGHT,
                ChatBubbleStylePolicy.GroupPosition.SINGLE
            )
        )
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
    fun richCardPaletteUsesNeutralCardSurface() {
        val palette = ChatBubbleStylePolicy.cardPalette()

        assertEquals(0xFFFFFFFF.toInt(), palette.bubbleColor)
        assertEquals(ChatBubbleStylePolicy.DEFAULT_LEFT_TEXT_COLOR, palette.textColor)
        assertEquals(0x52FFFFFF, palette.strokeColor)
        assertEquals(0.75f, palette.strokeWidthDp, 0f)
    }

    @Test
    fun imagePaletteUsesNeutralSurfaceInsteadOfSideTint() {
        val palette = ChatBubbleStylePolicy.imagePalette()

        assertEquals(0xFFFFFFFF.toInt(), palette.bubbleColor)
        assertEquals(0x14000000, palette.strokeColor)
        assertEquals(0.75f, palette.strokeWidthDp, 0f)
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
    fun cachedBubbleShapeCanGrowWhenNewNeighborArrives() {
        assertTrue(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.GroupPosition.SINGLE,
                ChatBubbleStylePolicy.GroupPosition.TOP
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.GroupPosition.BOTTOM,
                ChatBubbleStylePolicy.GroupPosition.MIDDLE
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.GroupPosition.SINGLE,
                ChatBubbleStylePolicy.GroupPosition.BOTTOM
            )
        )
        assertFalse(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.GroupPosition.MIDDLE,
                ChatBubbleStylePolicy.GroupPosition.BOTTOM
            )
        )
        assertFalse(
            ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                ChatBubbleStylePolicy.GroupPosition.SINGLE,
                ChatBubbleStylePolicy.GroupPosition.SINGLE
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
    fun groupChatMessagesDoNotUseChatroomIdAsSenderFallback() {
        assertEquals(
            "wxid_alice",
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.LEFT,
                "demo@chatroom",
                "wxid_alice:\n<voicemsg />"
            )
        )
        assertEquals(
            "wxid_alice",
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.LEFT,
                "demo@chatroom",
                "<msg><voicemsg fromusername=\"wxid_alice\" /></msg>"
            )
        )
        assertEquals(
            "wxid_alice",
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.LEFT,
                "demo@chatroom",
                "<msg><voicemsg username=\"wxid_alice\" /></msg>"
            )
        )
        assertEquals(
            "evilshooter",
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.LEFT,
                "demo@chatroom",
                "evilshooter:7602:0  "
            )
        )
        assertNull(
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.LEFT,
                "demo@chatroom",
                "<msg username=\"wxid_contact\"><nickname>shared card</nickname></msg>"
            )
        )
        assertNull(
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.LEFT,
                "demo@chatroom",
                "<voicemsg />"
            )
        )
        assertEquals(
            "wxid_friend",
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.LEFT,
                "wxid_friend",
                "<voicemsg />"
            )
        )
        assertEquals(
            "wxid_friend",
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.LEFT,
                "wxid_friend",
                "title:\nplain direct chat text"
            )
        )
        assertEquals(
            "self",
            ChatBubbleStylePolicy.senderKeyForGrouping(
                ChatBubbleStylePolicy.Side.RIGHT,
                "demo@chatroom",
                "<voicemsg />"
            )
        )
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
    fun visibleTimeSeparatorStopsVoiceBeforeFollowingCallAndKeepsNextGroupTogether() {
        val rows = listOf(
            row(
                stableKey = "voice14",
                side = ChatBubbleStylePolicy.Side.LEFT,
                senderKey = "alice",
                createTimeMs = 1_000L,
                text = "<voicemsg />",
                groupKey = "voice"
            ),
            row(
                stableKey = "voice2",
                side = ChatBubbleStylePolicy.Side.LEFT,
                senderKey = "alice",
                createTimeMs = 2_000L,
                text = "<voicemsg />",
                groupKey = "voice"
            ),
            row(
                stableKey = "call",
                side = ChatBubbleStylePolicy.Side.LEFT,
                senderKey = "alice",
                createTimeMs = 3_000L,
                text = "已在其它设备拒绝",
                hasTimeSeparatorBefore = true,
                groupKey = "call"
            ),
            row(
                stableKey = "voice5",
                side = ChatBubbleStylePolicy.Side.LEFT,
                senderKey = "alice",
                createTimeMs = 4_000L,
                text = "<voicemsg />",
                groupKey = "voice"
            )
        )

        val states = ChatBubbleStylePolicy.resolveRenderStates(rows)

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("voice14").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("voice2").position)
        assertTrue(states.getValue("voice2").showAvatar)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("call").position)
        assertFalse(states.getValue("call").showAvatar)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("voice5").position)
        assertTrue(states.getValue("voice5").showAvatar)
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
    fun nonRenderableMessagesStopRenderStateGrouping() {
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
    fun renderableMediaAndCardRowsGroupBySenderAndTimeWithoutTextFlag() {
        val rows = listOf(
            row(
                stableKey = "voice",
                side = ChatBubbleStylePolicy.Side.LEFT,
                senderKey = "alice",
                createTimeMs = 1_000L,
                text = "<voicemsg />",
                isTextMessage = false,
                groupKey = "voice"
            ),
            row(
                stableKey = "image",
                side = ChatBubbleStylePolicy.Side.LEFT,
                senderKey = "alice",
                createTimeMs = 2_000L,
                text = "<img />",
                isTextMessage = false,
                groupKey = "image"
            ),
            row(
                stableKey = "video",
                side = ChatBubbleStylePolicy.Side.LEFT,
                senderKey = "alice",
                createTimeMs = 3_000L,
                text = "<videomsg />",
                isTextMessage = false,
                groupKey = "image"
            ),
            row(
                stableKey = "card",
                side = ChatBubbleStylePolicy.Side.LEFT,
                senderKey = "alice",
                createTimeMs = 4_000L,
                text = "<appmsg />",
                isTextMessage = false,
                groupKey = "card"
            )
        )

        val states = ChatBubbleStylePolicy.resolveRenderStates(rows)

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("voice").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.MIDDLE, states.getValue("image").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.MIDDLE, states.getValue("video").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("card").position)
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
        assertTrue(ChatBubbleStylePolicy.isStylableWechatBubbleMessage(3, "<msg><img aeskey=\"x\" /></msg>"))
        assertTrue(ChatBubbleStylePolicy.isStylableWechatBubbleMessage(43, "<msg><videomsg /></msg>"))
        assertTrue(ChatBubbleStylePolicy.isStylableWechatBubbleMessage(62, "<msg><videomsg /></msg>"))
        assertTrue(ChatBubbleStylePolicy.isStylableWechatBubbleMessage(48, "<msg><location /></msg>"))
    }

    @Test
    fun richCardRowsAreStylableBubbleMessages() {
        assertTrue(
            ChatBubbleStylePolicy.isStylableWechatBubbleMessage(
                49,
                "<msg><appmsg><type>5</type><title>link</title></appmsg></msg>"
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.isStylableWechatBubbleMessage(
                49,
                "<msg><appmsg><type>33</type><title>mini program</title></appmsg></msg>"
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.isStylableWechatBubbleMessage(
                42,
                "<msg username=\"wxid_demo\"><nickname>card</nickname></msg>"
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.isStylableWechatBubbleMessage(
                419430449,
                "<msg><appmsg><type>2000</type><wcpayinfo /></appmsg></msg>"
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.isStylableWechatBubbleMessage(
                436207665,
                "<msg><appmsg><type>2001</type><title>微信红包</title></appmsg></msg>"
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.isStylableWechatBubbleMessage(
                3,
                "<msg><img aeskey=\"x\" /></msg>"
            )
        )
        assertTrue(
            ChatBubbleStylePolicy.isStylableWechatBubbleMessage(
                43,
                "<msg><videomsg /></msg>"
            )
        )
    }

    @Test
    fun redpacketCardTextSignalRequiresCardSpecificWording() {
        assertTrue(ChatBubbleStylePolicy.hasRedpacketCardTextSignal("微信红包"))
        assertTrue(ChatBubbleStylePolicy.hasRedpacketCardTextSignal("恭喜发财，大吉大利"))
        assertTrue(ChatBubbleStylePolicy.hasRedpacketCardTextSignal("红包 已领取"))
        assertTrue(ChatBubbleStylePolicy.hasRedpacketCardTextSignal("红包 已过期"))

        assertFalse(ChatBubbleStylePolicy.hasRedpacketCardTextSignal("红包啊"))
        assertFalse(ChatBubbleStylePolicy.hasRedpacketCardTextSignal("取出来搞点红包，意思意思"))
        assertFalse(ChatBubbleStylePolicy.hasRedpacketCardTextSignal("一起拆红包，快来！"))
        assertFalse(ChatBubbleStylePolicy.hasRedpacketCardTextSignal(""))
    }

    @Test
    fun richCardRowsParticipateInMessageGrouping() {
        val states = ChatBubbleStylePolicy.resolveRenderStates(
            listOf(
                row("link", ChatBubbleStylePolicy.Side.LEFT, "alice", 1_000L, "link", isTextMessage = true),
                row("text", ChatBubbleStylePolicy.Side.LEFT, "alice", 2_000L, "ok", isTextMessage = true),
                row("transfer", ChatBubbleStylePolicy.Side.LEFT, "alice", 3_000L, "transfer", isTextMessage = true)
            )
        )

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("link").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.MIDDLE, states.getValue("text").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("transfer").position)
    }

    @Test
    fun redpacketRowsParticipateInMessageGrouping() {
        val states = ChatBubbleStylePolicy.resolveRenderStates(
            listOf(
                row("text", ChatBubbleStylePolicy.Side.LEFT, "alice", 1_000L, "ok", isTextMessage = true),
                row("redpacket", ChatBubbleStylePolicy.Side.LEFT, "alice", 2_000L, "微信红包", isTextMessage = true)
            )
        )

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("text").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("redpacket").position)
    }

    @Test
    fun mediaRowsParticipateInMessageGrouping() {
        val states = ChatBubbleStylePolicy.resolveRenderStates(
            listOf(
                row("image", ChatBubbleStylePolicy.Side.LEFT, "alice", 1_000L, "[image]", isTextMessage = true),
                row("video", ChatBubbleStylePolicy.Side.LEFT, "alice", 2_000L, "<videomsg />", isTextMessage = true),
                row("location", ChatBubbleStylePolicy.Side.LEFT, "alice", 3_000L, "[location]", isTextMessage = true),
                row("text", ChatBubbleStylePolicy.Side.LEFT, "alice", 4_000L, "ok", isTextMessage = true)
            )
        )

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("image").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.MIDDLE, states.getValue("video").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.MIDDLE, states.getValue("location").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("text").position)
    }

    @Test
    fun explicitTimeSeparatorBeforeNextRowEndsPreviousMixedMediaGroup() {
        val states = ChatBubbleStylePolicy.resolveRenderStates(
            listOf(
                row("image", ChatBubbleStylePolicy.Side.LEFT, "alice", 1_000L, "[image]", isTextMessage = true),
                row("text1", ChatBubbleStylePolicy.Side.LEFT, "alice", 2_000L, "finally fixed", isTextMessage = true),
                row("text2", ChatBubbleStylePolicy.Side.LEFT, "alice", 3_000L, "pro subscription", isTextMessage = true),
                row(
                    "emoji",
                    ChatBubbleStylePolicy.Side.LEFT,
                    "alice",
                    4_000L,
                    "[wow]",
                    isTextMessage = true,
                    hasTimeSeparatorBefore = true
                )
            )
        )

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("image").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.MIDDLE, states.getValue("text1").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("text2").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.SINGLE, states.getValue("emoji").position)
        assertTrue(states.getValue("text2").showAvatar)
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
    fun mixedTextImageAndVoiceRowsKeepAdjacentSenderGroups() {
        val states = ChatBubbleStylePolicy.resolveRenderStates(
            listOf(
                row("skirt1", ChatBubbleStylePolicy.Side.LEFT, "wxid_skirt", 1_000L, "不是加上嘉业的一共4套？"),
                row("skirt2", ChatBubbleStylePolicy.Side.LEFT, "wxid_skirt", 34_000L, "是指多一本说明书？"),
                row(
                    stableKey = "skirt-image",
                    side = ChatBubbleStylePolicy.Side.LEFT,
                    senderKey = "wxid_skirt",
                    createTimeMs = 34_001L,
                    text = "<img />",
                    isTextMessage = false,
                    groupKey = "image"
                ),
                row("a0-text", ChatBubbleStylePolicy.Side.LEFT, "evilshooter", 50_000L, "一套"),
                row(
                    stableKey = "a0-voice",
                    side = ChatBubbleStylePolicy.Side.LEFT,
                    senderKey = "evilshooter",
                    createTimeMs = 99_000L,
                    text = "evilshooter:4713:0",
                    isTextMessage = false,
                    groupKey = "voice"
                )
            )
        )

        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("skirt1").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.MIDDLE, states.getValue("skirt2").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("skirt-image").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.TOP, states.getValue("a0-text").position)
        assertEquals(ChatBubbleStylePolicy.GroupPosition.BOTTOM, states.getValue("a0-voice").position)
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

    @Test
    fun rightSideRenderStatesNeverShowNickname() {
        val rows = listOf(
            row("1", ChatBubbleStylePolicy.Side.RIGHT, "self", 1_000L, "a"),
            row("2", ChatBubbleStylePolicy.Side.RIGHT, "self", 2_000L, "b")
        )

        val states = ChatBubbleStylePolicy.resolveRenderStates(rows)

        assertFalse(states.getValue("1").showNickname)
        assertFalse(states.getValue("2").showNickname)
        assertFalse(states.getValue("1").showAvatar)
        assertTrue(states.getValue("2").showAvatar)
    }

    private fun row(
        stableKey: String,
        side: ChatBubbleStylePolicy.Side,
        senderKey: String?,
        createTimeMs: Long,
        text: String,
        isTextMessage: Boolean = true,
        hasTimeSeparatorBefore: Boolean = false,
        groupKey: String? = null
    ): ChatBubbleStylePolicy.MessageRow {
        return ChatBubbleStylePolicy.MessageRow(
            stableKey = stableKey,
            isTextMessage = isTextMessage,
            side = side,
            senderKey = senderKey,
            createTimeMs = createTimeMs,
            contentText = text,
            hasTimeSeparatorBefore = hasTimeSeparatorBefore,
            groupKey = groupKey
        )
    }

    private fun luminance(color: Int): Int {
        return (redOf(color) * 299 + greenOf(color) * 587 + blueOf(color) * 114) / 1000
    }

    private fun redOf(color: Int): Int = color ushr 16 and 0xFF

    private fun greenOf(color: Int): Int = color ushr 8 and 0xFF

    private fun blueOf(color: Int): Int = color and 0xFF
}
