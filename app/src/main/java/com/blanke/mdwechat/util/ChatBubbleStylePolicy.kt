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
        val strokeWidthDp: Float
    ) {
        val signature: String
            get() = "$bubbleColor:$pressedBubbleColor:$textColor:$semanticTextColor:$quoteFillColor:" +
                    "$quoteTextColor:$quoteStrokeColor:$strokeColor:$strokeWidthDp"
    }

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

    fun shouldUpdateCachedPositionForNeighborGrowth(
        side: Side,
        cachedPosition: GroupPosition,
        computedPosition: GroupPosition
    ): Boolean {
        if (side != Side.RIGHT || cachedPosition == computedPosition) {
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
                isVoiceWechatMessage(type, content) ||
                isCallWechatMessage(type, content)
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

    private fun isCallWechatMessage(type: Int?, content: String?): Boolean {
        if (type == 50) {
            return true
        }
        val text = content ?: return false
        return text.indexOf("通话时长", ignoreCase = true) >= 0 ||
                text.indexOf("已在其它设备拒绝", ignoreCase = true) >= 0 ||
                text.indexOf("<voip", ignoreCase = true) >= 0
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
