package com.blanke.mdwechat.util

object ChatBubbleStylePolicy {
    val DEFAULT_RIGHT_BUBBLE_COLOR: Int = 0xFFC5EFD1.toInt()
    val DEFAULT_LEFT_BUBBLE_COLOR: Int = 0xFFFCFCF8.toInt()
    val DEFAULT_RIGHT_TEXT_COLOR: Int = 0xFF042100.toInt()
    val DEFAULT_LEFT_TEXT_COLOR: Int = 0xFF1A1C19.toInt()
    val DEFAULT_RIGHT_QUOTE_FILL_COLOR: Int = 0xFFD8F1DE.toInt()
    val DEFAULT_LEFT_QUOTE_FILL_COLOR: Int = 0xFFECEEE8.toInt()
    val DEFAULT_RIGHT_QUOTE_TEXT_COLOR: Int = 0xFF31583D.toInt()
    val DEFAULT_LEFT_QUOTE_TEXT_COLOR: Int = 0xFF666B63.toInt()
    val DEFAULT_DYNAMIC_HUE: Float = 210f
    val TRANSPARENT_COLOR: Int = 0x00000000
    private val richCardMessageTypes = setOf(
        42,
        49,
        419430449,
        436207665,
        469762097
    )
    private val richAppMsgTypes = setOf(
        3,
        5,
        6,
        10,
        13,
        19,
        24,
        33,
        36,
        40,
        44,
        46,
        51,
        57,
        2000,
        2001,
        2002
    )
    private val unsupportedAppMsgTypes = setOf(2, 4, 8)

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
        val senderKey: String?,
        val groupKey: String? = null
    )

    data class MessageRow(
        val stableKey: String,
        val isTextMessage: Boolean,
        val side: Side?,
        val senderKey: String?,
        val createTimeMs: Long?,
        val contentText: String?,
        val hasTimeSeparatorBefore: Boolean = false,
        val groupKey: String? = null
    ) {
        fun toCandidate(): MessageCandidate {
            return MessageCandidate(
                isTextMessage = isTextMessage,
                side = side,
                senderKey = senderKey,
                groupKey = groupKey
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

    data class BubbleColorConfig(
        val useCustomBubbleTint: Boolean = false,
        val leftBubbleTint: Int = DEFAULT_LEFT_BUBBLE_COLOR,
        val rightBubbleTint: Int = DEFAULT_RIGHT_BUBBLE_COLOR,
        val useCustomTextColor: Boolean = false,
        val leftTextColor: Int = DEFAULT_LEFT_TEXT_COLOR,
        val rightTextColor: Int = DEFAULT_RIGHT_TEXT_COLOR
    )

    data class BubblePalette(
        val bubbleColor: Int,
        val pressedBubbleColor: Int,
        val textColor: Int,
        val semanticTextColor: Int,
        val quoteFillColor: Int,
        val quoteTextColor: Int,
        val quoteStrokeColor: Int,
        val strokeColor: Int,
        val strokeWidthDp: Float,
        val useGradient: Boolean = true
    ) {
        val signature: String
            get() = "$bubbleColor:$pressedBubbleColor:$textColor:$semanticTextColor:$quoteFillColor:" +
                    "$quoteTextColor:$quoteStrokeColor:$strokeColor:$strokeWidthDp:$useGradient"
    }

    fun groupPosition(hasPrevious: Boolean, hasNext: Boolean): GroupPosition {
        return when {
            hasPrevious && hasNext -> GroupPosition.MIDDLE
            hasPrevious -> GroupPosition.BOTTOM
            hasNext -> GroupPosition.TOP
            else -> GroupPosition.SINGLE
        }
    }

    fun positionWithoutNext(position: GroupPosition): GroupPosition {
        return when (position) {
            GroupPosition.TOP -> GroupPosition.SINGLE
            GroupPosition.MIDDLE -> GroupPosition.BOTTOM
            GroupPosition.BOTTOM,
            GroupPosition.SINGLE -> position
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

    fun bubblePalette(side: Side, config: BubbleColorConfig = BubbleColorConfig()): BubblePalette {
        val bubbleColor = when (side) {
            Side.LEFT -> if (config.useCustomBubbleTint) config.leftBubbleTint else DEFAULT_LEFT_BUBBLE_COLOR
            Side.RIGHT -> if (config.useCustomBubbleTint) config.rightBubbleTint else DEFAULT_RIGHT_BUBBLE_COLOR
        }
        val textColor = when (side) {
            Side.LEFT -> if (config.useCustomTextColor) config.leftTextColor else DEFAULT_LEFT_TEXT_COLOR
            Side.RIGHT -> if (config.useCustomTextColor) config.rightTextColor else DEFAULT_RIGHT_TEXT_COLOR
        }
        val quoteFillColor = when {
            !config.useCustomBubbleTint && side == Side.RIGHT -> DEFAULT_RIGHT_QUOTE_FILL_COLOR
            !config.useCustomBubbleTint -> DEFAULT_LEFT_QUOTE_FILL_COLOR
            else -> quoteFillFromBubble(bubbleColor)
        }
        val quoteTextColor = when {
            config.useCustomTextColor -> textColor
            side == Side.RIGHT -> DEFAULT_RIGHT_QUOTE_TEXT_COLOR
            else -> DEFAULT_LEFT_QUOTE_TEXT_COLOR
        }
        return BubblePalette(
            bubbleColor = bubbleColor,
            pressedBubbleColor = scaleRgb(bubbleColor, 0.97f),
            textColor = textColor,
            semanticTextColor = dynamicSemanticTextColor(bubbleColor),
            quoteFillColor = quoteFillColor,
            quoteTextColor = quoteTextColor,
            quoteStrokeColor = TRANSPARENT_COLOR,
            strokeColor = TRANSPARENT_COLOR,
            strokeWidthDp = 0f
        )
    }

    fun cardPalette(): BubblePalette {
        val bubbleColor = 0xFFFFFFFF.toInt()
        return BubblePalette(
            bubbleColor = bubbleColor,
            pressedBubbleColor = scaleRgb(bubbleColor, 0.97f),
            textColor = DEFAULT_LEFT_TEXT_COLOR,
            semanticTextColor = dynamicSemanticTextColor(bubbleColor),
            quoteFillColor = DEFAULT_LEFT_QUOTE_FILL_COLOR,
            quoteTextColor = DEFAULT_LEFT_QUOTE_TEXT_COLOR,
            quoteStrokeColor = TRANSPARENT_COLOR,
            strokeColor = 0x52FFFFFF,
            strokeWidthDp = 0.75f
        )
    }

    fun imagePalette(): BubblePalette {
        val bubbleColor = TRANSPARENT_COLOR
        return BubblePalette(
            bubbleColor = bubbleColor,
            pressedBubbleColor = TRANSPARENT_COLOR,
            textColor = DEFAULT_LEFT_TEXT_COLOR,
            semanticTextColor = DEFAULT_LEFT_TEXT_COLOR,
            quoteFillColor = TRANSPARENT_COLOR,
            quoteTextColor = DEFAULT_LEFT_QUOTE_TEXT_COLOR,
            quoteStrokeColor = TRANSPARENT_COLOR,
            strokeColor = TRANSPARENT_COLOR,
            strokeWidthDp = 0f,
            useGradient = false
        )
    }

    fun shouldUpdateCachedPositionForNeighborGrowth(
        cachedPosition: GroupPosition,
        computedPosition: GroupPosition
    ): Boolean {
        if (cachedPosition == computedPosition) {
            return false
        }
        return when (cachedPosition) {
            GroupPosition.SINGLE -> computedPosition == GroupPosition.TOP ||
                    computedPosition == GroupPosition.BOTTOM
            GroupPosition.TOP,
            GroupPosition.BOTTOM -> computedPosition == GroupPosition.MIDDLE
            GroupPosition.MIDDLE -> false
        }
    }

    fun showAvatar(position: GroupPosition): Boolean {
        return position == GroupPosition.SINGLE || position == GroupPosition.BOTTOM
    }

    fun showNickname(position: GroupPosition): Boolean {
        return position == GroupPosition.SINGLE || position == GroupPosition.TOP
    }

    fun showNickname(side: Side, position: GroupPosition): Boolean {
        return side == Side.LEFT && showNickname(position)
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
            if (!row.isRenderableForGrouping()) {
                return@forEachIndexed
            }
            val previous = rows.getOrNull(index - 1)
            val next = rows.getOrNull(index + 1)
            val hasTimeBeforeCurrent = row.hasTimeSeparatorBefore ||
                    isTimeSplit(previous, row, timeSeparatorGapMs)
            val hasTimeBeforeNext = next?.hasTimeSeparatorBefore == true ||
                    isTimeSplit(row, next, timeSeparatorGapMs)
            val hasPrevious = !hasTimeBeforeCurrent &&
                    canGroupWith(row.toCandidate(), previous?.toCandidate())
            val hasNext = !hasTimeBeforeNext &&
                    canGroupWith(row.toCandidate(), next?.toCandidate())
            val position = groupPosition(hasPrevious = hasPrevious, hasNext = hasNext)
            states[row.stableKey] = RenderState(
                stableKey = row.stableKey,
                side = side,
                position = position,
                showAvatar = showAvatar(position),
                showNickname = showNickname(side, position),
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
        if (!current.isRenderableForGrouping() || !neighbor.isRenderableForGrouping()) {
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

    fun senderKeyForGrouping(side: Side?, talker: String?, content: String?): String? {
        val embeddedSender = extractGroupSenderPrefix(content)
            ?: extractVoiceSenderPrefix(content)
            ?: extractGroupSenderAttribute(content)
        val groupConversation = isGroupConversationId(talker)
        return when (side) {
            Side.RIGHT -> "self"
            Side.LEFT -> if (groupConversation) embeddedSender else talker ?: embeddedSender
            null -> if (groupConversation) embeddedSender else talker ?: embeddedSender
        }
    }

    fun isGroupConversationId(talker: String?): Boolean {
        return talker?.endsWith("@chatroom", ignoreCase = true) == true
    }

    private fun extractGroupSenderPrefix(content: String?): String? {
        val text = content ?: return null
        val unixIndex = text.indexOf(":\n")
        val windowsIndex = text.indexOf(":\r\n")
        val index = when {
            unixIndex > 0 -> unixIndex
            windowsIndex > 0 -> windowsIndex
            else -> -1
        }
        if (index !in 2..80) {
            return null
        }
        return text.substring(0, index).takeIf { it.isNotBlank() }
    }

    private fun extractVoiceSenderPrefix(content: String?): String? {
        val text = content?.trimStart() ?: return null
        val match = Regex("""^([A-Za-z0-9_@.\-]{2,80}):\d{1,8}:\d+""").find(text) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun MessageCandidate.isRenderableForGrouping(): Boolean {
        return isTextMessage || !groupKey.isNullOrBlank()
    }

    private fun MessageRow.isRenderableForGrouping(): Boolean {
        return isTextMessage || !groupKey.isNullOrBlank()
    }

    private fun extractGroupSenderAttribute(content: String?): String? {
        val text = content ?: return null
        val fromUsername = Regex("""\bfromusername\s*=\s*(['"])([^'"]+)\1""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (fromUsername != null) {
            return fromUsername
        }
        val voiceStart = text.indexOf("<voicemsg", ignoreCase = true)
        if (voiceStart < 0) {
            return null
        }
        val voiceEnd = text.indexOf(">", voiceStart).let { if (it >= 0) it else text.length }
        val voiceTag = text.substring(voiceStart, voiceEnd)
        return Regex("""\busername\s*=\s*(['"])([^'"]+)\1""", RegexOption.IGNORE_CASE)
            .find(voiceTag)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun isTextLikeWechatMessage(type: Int?, content: String?): Boolean {
        if (isReferenceMessageContent(content)) {
            return true
        }
        if (type == 1) {
            return true
        }
        if (type != null) {
            return false
        }
        val text = content?.trim().orEmpty()
        if (text.isBlank() || isClearlyNonTextMessageContent(text)) {
            return false
        }
        return true
    }

    fun isStylableWechatBubbleMessage(type: Int?, content: String?): Boolean {
        return isTextLikeWechatMessage(type, content) ||
                isImageWechatMessage(type, content) ||
                isVideoWechatMessage(type, content) ||
                isLocationWechatMessage(type, content) ||
                isVoiceWechatMessage(type, content) ||
                isCallWechatMessage(type, content) ||
                isRichCardWechatMessage(type, content)
    }

    fun groupKeyForWechatMessage(type: Int?, content: String?): String? {
        return when {
            isTextLikeWechatMessage(type, content) -> "text"
            isVoiceWechatMessage(type, content) -> "voice"
            isCallWechatMessage(type, content) -> "call"
            isImageWechatMessage(type, content) -> "image"
            isVideoWechatMessage(type, content) -> "image"
            isLocationWechatMessage(type, content) -> "location"
            isRichCardWechatMessage(type, content) -> "card"
            else -> null
        }
    }

    fun hasRedpacketCardTextSignal(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) {
            return false
        }
        if (value.contains("微信红包") || value.contains("恭喜发财")) {
            return true
        }
        if (!value.contains("红包")) {
            return false
        }
        return listOf(
            "领取",
            "已领",
            "领完",
            "已过期",
            "红包封面"
        ).any { marker -> value.contains(marker) }
    }

    private fun isTimeSplit(
        older: MessageRow?,
        newer: MessageRow?,
        thresholdMs: Long
    ): Boolean {
        val olderTime = older?.createTimeMs?.let { normalizeWechatCreateTimeMs(it) } ?: return false
        val newerTime = newer?.createTimeMs?.let { normalizeWechatCreateTimeMs(it) } ?: return false
        return kotlin.math.abs(newerTime - olderTime) >= thresholdMs
    }

    fun normalizeWechatCreateTimeMs(rawTime: Long): Long {
        val absTime = kotlin.math.abs(rawTime)
        return if (absTime in 1_000_000_000L until 100_000_000_000L) {
            rawTime * 1000L
        } else {
            rawTime
        }
    }

    private fun isReferenceMessageContent(content: String?): Boolean {
        val text = content ?: return false
        return text.indexOf("<refermsg", ignoreCase = true) >= 0 ||
                text.indexOf("<refermessage", ignoreCase = true) >= 0
    }

    private fun isVoiceWechatMessage(type: Int?, content: String?): Boolean {
        if (type == 34) {
            return true
        }
        return content?.indexOf("<voicemsg", ignoreCase = true) ?: -1 >= 0
    }

    private fun isImageWechatMessage(type: Int?, content: String?): Boolean {
        if (type == 3) {
            return true
        }
        return content?.indexOf("<img", ignoreCase = true) ?: -1 >= 0
    }

    private fun isVideoWechatMessage(type: Int?, content: String?): Boolean {
        if (type == 43 || type == 62) {
            return true
        }
        return content?.indexOf("<videomsg", ignoreCase = true) ?: -1 >= 0
    }

    private fun isLocationWechatMessage(type: Int?, content: String?): Boolean {
        if (type == 48) {
            return true
        }
        return content?.indexOf("<location", ignoreCase = true) ?: -1 >= 0
    }

    private fun isCallWechatMessage(type: Int?, content: String?): Boolean {
        if (type == 50) {
            return true
        }
        val text = content ?: return false
        return text.indexOf("通话时长", ignoreCase = true) >= 0 ||
                text.indexOf("已在其它设备拒绝", ignoreCase = true) >= 0 ||
                text.indexOf("<voip", ignoreCase = true) >= 0
    }

    private fun isRichCardWechatMessage(type: Int?, content: String?): Boolean {
        if (type in richCardMessageTypes && content.isNullOrBlank()) {
            return true
        }
        val text = content ?: return false
        if (hasAnyMarker(
                text,
                "<wcpayinfo",
                "<paymsg",
                "微信转账",
                "微信红包",
                "<nativeurl",
                "<templateid>"
            )
        ) {
            return true
        }
        if (type == 42 || hasAnyMarker(text, "<msg username=", "<msg bigheadimgurl=", "<nickname>")) {
            return true
        }
        if (text.indexOf("<appmsg", ignoreCase = true) < 0) {
            return type in richCardMessageTypes
        }
        val appMsgType = extractAppMsgType(text)
        if (appMsgType != null) {
            if (appMsgType in unsupportedAppMsgTypes) {
                return false
            }
            return appMsgType in richAppMsgTypes || type in richCardMessageTypes
        }
        return type in richCardMessageTypes
    }

    private fun extractAppMsgType(content: String): Int? {
        val startTag = "<type>"
        val endTag = "</type>"
        val start = content.indexOf(startTag, ignoreCase = true)
        if (start < 0) {
            return null
        }
        val valueStart = start + startTag.length
        val end = content.indexOf(endTag, valueStart, ignoreCase = true)
        if (end <= valueStart) {
            return null
        }
        return content.substring(valueStart, end).trim().toIntOrNull()
    }

    private fun hasAnyMarker(content: String, vararg markers: String): Boolean {
        return markers.any { marker ->
            content.indexOf(marker, ignoreCase = true) >= 0
        }
    }

    private fun isClearlyNonTextMessageContent(content: String): Boolean {
        val markers = listOf(
            "<img",
            "<emoji",
            "<videomsg",
            "<voicemsg",
            "<location",
            "<appmsg",
            "<recordinfo",
            "<msgsource"
        )
        return markers.any { marker ->
            content.indexOf(marker, ignoreCase = true) >= 0
        }
    }

    private fun quoteFillFromBubble(color: Int): Int {
        val overlay = if (isVisuallyDark(color)) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF000000.toInt()
        }
        val overlayAmount = if (isVisuallyDark(color)) 0.18f else 0.06f
        return blendRgb(color, overlay, overlayAmount)
    }

    fun dynamicSemanticTextColor(backgroundColor: Int): Int {
        val hsl = rgbToHsl(backgroundColor)
        val isLightBackground = hsl.lightness > 0.6f
        val isNeutral = hsl.saturation < 0.05f
        val hue = if (isNeutral) DEFAULT_DYNAMIC_HUE else hsl.hue
        val saturation = if (isNeutral) 0.80f else 0.85f
        val lightness = if (isLightBackground) {
            (hsl.lightness - 0.30f).coerceIn(0.22f, 0.40f)
        } else {
            (hsl.lightness + 0.30f).coerceIn(0.85f, 0.94f)
        }
        return hslToRgb(alphaOf(backgroundColor), hue, saturation, lightness)
    }

    private fun isVisuallyDark(color: Int): Boolean {
        val red = color ushr 16 and 0xFF
        val green = color ushr 8 and 0xFF
        val blue = color and 0xFF
        return (red * 299 + green * 587 + blue * 114) < 128_000
    }

    private fun scaleRgb(color: Int, factor: Float): Int {
        val alpha = color ushr 24 and 0xFF
        val red = ((color ushr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val green = ((color ushr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val blue = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private data class HslColor(
        val hue: Float,
        val saturation: Float,
        val lightness: Float
    )

    private fun rgbToHsl(color: Int): HslColor {
        val red = redOf(color) / 255f
        val green = greenOf(color) / 255f
        val blue = blueOf(color) / 255f
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val lightness = (max + min) / 2f
        if (max == min) {
            return HslColor(hue = 0f, saturation = 0f, lightness = lightness)
        }
        val delta = max - min
        val saturation = if (lightness > 0.5f) {
            delta / (2f - max - min)
        } else {
            delta / (max + min)
        }
        val hue = when (max) {
            red -> ((green - blue) / delta + if (green < blue) 6f else 0f) / 6f
            green -> ((blue - red) / delta + 2f) / 6f
            else -> ((red - green) / delta + 4f) / 6f
        } * 360f
        return HslColor(hue = hue, saturation = saturation, lightness = lightness)
    }

    private fun hslToRgb(alpha: Int, hue: Float, saturation: Float, lightness: Float): Int {
        val normalizedHue = ((hue % 360f) + 360f) % 360f
        val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - kotlin.math.abs((normalizedHue / 60f) % 2f - 1f))
        val m = lightness - c / 2f
        val (r1, g1, b1) = when {
            normalizedHue < 60f -> Triple(c, x, 0f)
            normalizedHue < 120f -> Triple(x, c, 0f)
            normalizedHue < 180f -> Triple(0f, c, x)
            normalizedHue < 240f -> Triple(0f, x, c)
            normalizedHue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val red = java.lang.Math.round((r1 + m) * 255f).coerceIn(0, 255)
        val green = java.lang.Math.round((g1 + m) * 255f).coerceIn(0, 255)
        val blue = java.lang.Math.round((b1 + m) * 255f).coerceIn(0, 255)
        return (alpha.coerceIn(0, 255) shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun alphaOf(color: Int): Int = color ushr 24 and 0xFF

    private fun redOf(color: Int): Int = color ushr 16 and 0xFF

    private fun greenOf(color: Int): Int = color ushr 8 and 0xFF

    private fun blueOf(color: Int): Int = color and 0xFF

    private fun blendRgb(base: Int, overlay: Int, overlayAmount: Float): Int {
        val amount = overlayAmount.coerceIn(0f, 1f)
        val inverse = 1f - amount
        val alpha = base ushr 24 and 0xFF
        val red = ((base ushr 16 and 0xFF) * inverse + (overlay ushr 16 and 0xFF) * amount)
            .toInt()
            .coerceIn(0, 255)
        val green = ((base ushr 8 and 0xFF) * inverse + (overlay ushr 8 and 0xFF) * amount)
            .toInt()
            .coerceIn(0, 255)
        val blue = ((base and 0xFF) * inverse + (overlay and 0xFF) * amount)
            .toInt()
            .coerceIn(0, 255)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
