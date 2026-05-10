package com.blanke.mdwechat.hookers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.blanke.mdwechat.ViewTreeRepoThisVersion as VTTV
import com.blanke.mdwechat.bean.ViewTree
import com.blanke.mdwechat.util.ChatBubbleStylePolicy
import com.blanke.mdwechat.util.RuntimeProbe
import com.blanke.mdwechat.util.ViewTreeUtils
import com.blanke.mdwechat.util.ViewUtils
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

object ModernChatBubbleRenderer {
    private const val resourceItemRoot = "bn1"
    private const val resourceMessage = "bkl"
    private const val resourceAvatar = "bk1"
    private const val resourceNickname = "br1"
    private const val resourceNicknameModern = "brc"
    private const val resourceVoiceContainer = "brq"
    private const val resourceVoiceBubble = "brp"
    private const val resourceCallBubble = "bkg"
    private const val resourceCallText = "bs3"
    private const val resourceQuoteText = "bjp"
    private const val resourceQuoteContainer = "lgy"
    private const val resourceQuoteBackplate = "lgx"
    private const val keyOriginalAvatarHeight = "mdwechat_native_bubble_avatar_height"
    private const val keyAppliedSignature = "mdwechat_native_bubble_applied_signature"
    private const val keyPendingMiniProgramMeasuredTune = "mdwechat_native_bubble_pending_mini_program_measured_tune"
    private const val keyPendingContactMeasuredTune = "mdwechat_native_bubble_pending_contact_measured_tune"
    private const val keyPendingPaymentMeasuredTune = "mdwechat_native_bubble_pending_payment_measured_tune"
    private const val keyPendingPositionMeasuredTune = "mdwechat_native_bubble_pending_position_measured_tune"
    private const val keyPendingWebShareMeasuredTune = "mdwechat_native_bubble_pending_web_share_measured_tune"
    private const val keyPendingRowChromeSync = "mdwechat_native_bubble_pending_row_chrome_sync"
    private const val miniProgramReferenceCardWidth = 260f
    private const val miniProgramReferenceCardHeight = 234.75f
    private const val contactReferenceCardWidth = 240f
    private const val contactReferenceCardHeight = 103.5f
    private const val positionReferenceCardWidth = 240f
    private const val positionReferenceCardHeight = 167f
    private const val positionReferenceHeaderHeight = 65f
    private const val positionReferenceMapHeight = 100f
    private const val transferReferenceCardWidth = 240f
    private const val transferReferenceCardHeight = 115f
    private const val transferReferenceHeaderHeight = 69f
    private const val transferReferenceFooterHeight = 46f
    private const val probeFile = "native_bubble_renderer.txt"
    private const val enableRendererProbe = false
    private const val enableGenericRichCardHeuristic = false
    private val rowChromeSyncDelaysMs = longArrayOf(80L, 240L)
    private val probeKeys = mutableSetOf<String>()
    private val timeTextPattern = Regex("^\\d{1,2}:\\d{2}$")
    private val resourceIdCache = Collections.synchronizedMap(WeakHashMap<Context, MutableMap<String, Int>>())

    private data class BubbleTarget(
        val bubbleView: View,
        val textView: View?,
        val kind: String,
        val applyTextPadding: Boolean,
        val layoutView: View = bubbleView,
        val clearViews: List<View> = emptyList(),
        val namedViews: Map<String, View> = emptyMap(),
        val signatureText: String? = null,
        val clipToOutline: Boolean = false
    )

    private data class RichCardMetrics(
        val textCount: Int,
        val imageCount: Int,
        val textSample: String
    )

    private data class RichCardCandidate(
        val view: View,
        val metrics: RichCardMetrics,
        val score: Int
    )

    fun applyFromAdapterBind(adapter: Any, position: Int, itemView: View): Boolean {
        val root = findItemRoot(itemView) ?: itemView
        val state = ModernChatBubbleStyler.resolveRenderStateFromVisibleAdapterItem(adapter, position, root)
        if (state == null) {
            probe(itemView, "adapter.noState:${adapter.javaClass.name}:$position")
            debug(itemView, "adapterBind noState adapter=${adapter.javaClass.name} pos=$position")
            return false
        }
        debug(
            itemView,
            "adapterBind state adapter=${adapter.javaClass.name} pos=$position " +
                    "key=${state.stableKey.takeLast(10)} side=${state.side} group=${state.position}"
        )
        ModernChatBubbleStyler.rememberBoundRenderState(adapter, position, root, state)
        val applied = applyState(root, state, "adapter:${adapter.javaClass.simpleName}:$position")
        refreshVisibleNeighborItemsIfNeeded(adapter, position, root, state)
        return applied
    }

    fun applyVisibleChildrenFromAdapter(recycler: ViewGroup, adapter: Any): Int {
        val states = ModernChatBubbleStyler.resolveVisibleRenderStatesFromAdapter(recycler, adapter)
        debug(
            recycler,
            "visibleStates adapter=${adapter.javaClass.name} count=${states.size} children=${recycler.childCount}"
        )
        var applied = 0
        for (entry in states) {
            if (applyState(entry.itemView, entry.state, "visible:${adapter.javaClass.simpleName}")) {
                applied++
            }
        }
        return applied
    }

    fun applyFromChattingItemBind(boundView: View, holder: Any?, msgInfo: Any): Boolean {
        val context = ModernChatBubbleStyler.resolveRenderContextFromChattingItemBind(
            boundView = boundView,
            holder = holder,
            msgInfo = msgInfo
        )
        if (context == null) {
            probe(boundView, "chatItem.noState:${msgInfo.javaClass.name}")
            return false
        }
        val applied = applyState(boundView, context.state, "chatItem:${msgInfo.javaClass.simpleName}")
        val itemView = findItemRoot(boundView) ?: boundView
        if (context.adapter != null && context.position >= 0) {
            refreshVisibleNeighborItemsIfNeeded(context.adapter, context.position, itemView, context.state)
        }
        return applied
    }

    fun applyFromChattingItemBind(boundView: View, msgInfo: Any): Boolean {
        return applyFromChattingItemBind(boundView, null, msgInfo)
    }

    private fun applyState(boundView: View, state: ChatBubbleStylePolicy.RenderState, source: String): Boolean {
        val itemView = findItemRoot(boundView) ?: boundView
        val target = findBubbleTarget(itemView)
        if (target == null) {
            syncRowChromeState(itemView, target = null, state = state)
            probeAvatarApplyState(itemView, target = null, state = state, source = "$source.noBubble", text = null)
            probe(boundView, "$source.noBubble:${boundView.javaClass.name}:${resourceName(boundView)}")
            debug(boundView, "$source noBubbleTarget item=${resourceName(itemView)}")
            return false
        }
        val bubbleView = target.bubbleView
        val text = renderTextForSignature(target)
        if (text.isNullOrBlank()) {
            syncRowChromeState(itemView, target, state)
            probeAvatarApplyState(itemView, target, state, "$source.noText", text)
            probe(bubbleView, "$source.noText:${bubbleView.javaClass.name}:${resourceName(bubbleView)}:${target.kind}")
            debug(itemView, "$source noText target=${resourceName(bubbleView)} kind=${target.kind}")
            return false
        }
        if (shouldDeferCompactTopEdgeTextItem(itemView, target)) {
            syncRowChromeState(itemView, target, state)
            probeAvatarApplyState(itemView, target, state, "$source.deferTop", text)
            debug(itemView, "$source deferTopEdgeText item=${boundsText(itemView)} text=${shortText(text)}")
            return false
        }
        val renderState = state
        val palette = paletteForTarget(target, renderState)
        val signature = renderSignature(renderState, text, palette)
        if (isCurrentRender(itemView, bubbleView, renderState, signature)) {
            syncReusableState(itemView, target, renderState)
            tuneRichCardContent(target)
            tuneMediaContent(target)
            debugApply(itemView, bubbleView, renderState, source, text, "current")
            probeAvatarApplyState(itemView, target, renderState, "$source.current", text)
            scheduleRowChromeSyncIfNeeded(itemView, target, renderState, source)
            return true
        }
        clearOriginalBubbleContainers(bubbleView)
        clearCardForeground(target)
        target.clearViews.forEach { clearViewLayer(it) }
        val existingBubble = bubbleView.background as? ModernBubbleDrawable
        val canUpdateExisting = existingBubble != null &&
                existingBubble.stableKey == renderState.stableKey &&
                existingBubble.side == renderState.side
        if (canUpdateExisting) {
            debug(
                itemView,
                "$source updateBubble key=${renderState.stableKey.takeLast(10)} " +
                        "old=${existingBubble?.position} new=${renderState.position} " +
                        "animate=${existingBubble?.position != renderState.position}"
            )
            existingBubble?.update(
                nextState = renderState,
                nextPalette = palette,
                animateCorners = existingBubble.position != renderState.position
            )
        } else {
            debug(
                itemView,
                "$source newBubble key=${renderState.stableKey.takeLast(10)} side=${renderState.side} " +
                        "group=${renderState.position}"
            )
            bubbleView.background = ModernBubbleDrawable(bubbleView.context, renderState, palette)
        }
        if (target.applyTextPadding) {
            bubbleView.setPadding(dp(bubbleView, 13f), dp(bubbleView, 8.5f), dp(bubbleView, 13f), dp(bubbleView, 8.5f))
        }
        setBubbleTextColors(target, palette.textColor, palette.semanticTextColor)
        tuneRichCardContent(target)
        tuneMediaContent(target)
        applyShadow(bubbleView, renderState)
        applyContentClipIfNeeded(bubbleView, target)
        disableAncestorClipping(bubbleView)

        val layoutView = target.layoutView
        val marginTarget = layoutView.parent as? View ?: layoutView
        setTopMargin(marginTarget, dp(bubbleView, renderState.topMarginDp))
        normalizeMessageColumn(itemView, layoutView, renderState)
        styleQuoteBlock(itemView, target, renderState, palette)
        syncAvatarAndNicknameState(itemView, target, renderState)
        normalizeRow(itemView, layoutView, renderState)
        XposedHelpers.setAdditionalInstanceField(itemView, keyAppliedSignature, signature)
        probe(bubbleView, "$source.applied:${renderState.side}:${renderState.position}:${target.kind}:${text.take(16)}")
        debugApply(itemView, bubbleView, renderState, source, text, "applied")
        probeAvatarApplyState(itemView, target, renderState, "$source.applied", text)
        scheduleRowChromeSyncIfNeeded(itemView, target, renderState, source)
        return true
    }

    private fun refreshVisibleNeighborItemsIfNeeded(
        adapter: Any,
        position: Int,
        itemView: View,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        val appliedPosition = (findBubbleTarget(itemView)?.bubbleView?.background as? ModernBubbleDrawable)?.position
            ?: state.position
        val shouldRefreshGroupNeighbors = state.side == ChatBubbleStylePolicy.Side.RIGHT &&
                appliedPosition != ChatBubbleStylePolicy.GroupPosition.SINGLE
        val shouldRefreshPreviousBoundary = ModernChatBubbleStyler.hasVisibleTimeSeparatorBefore(itemView)
        if (!shouldRefreshGroupNeighbors && !shouldRefreshPreviousBoundary) {
            return
        }
        refreshVisibleNeighborItem(
            adapter = adapter,
            position = position - 1,
            itemView = adjacentVisibleItem(itemView, step = -1, requireMessage = true),
            source = "prev",
            hasTimeBeforeNext = shouldRefreshPreviousBoundary
        )
        if (shouldRefreshGroupNeighbors) {
            refreshVisibleNeighborItem(
                adapter = adapter,
                position = position + 1,
                itemView = adjacentVisibleItem(itemView, step = 1, requireMessage = true),
                source = "next"
            )
        }
    }

    private fun refreshVisibleNeighborItem(
        adapter: Any,
        position: Int,
        itemView: View?,
        source: String,
        hasTimeBeforeNext: Boolean? = null
    ) {
        if (position < 0 || itemView == null) {
            return
        }
        val root = findItemRoot(itemView) ?: itemView
        val resolvedPosition = ModernChatBubbleStyler.rememberedAdapterPosition(adapter, root)
            ?: position
        val nextHasVisibleTime = hasTimeBeforeNext
            ?: adjacentVisibleItem(root, step = 1, requireMessage = true)
                ?.let { ModernChatBubbleStyler.hasVisibleTimeSeparatorBefore(it) }
            ?: false
        val state = ModernChatBubbleStyler.resolveRenderStateFromAdapter(
            adapter = adapter,
            position = resolvedPosition,
            hasTimeBeforeCurrent = ModernChatBubbleStyler.hasVisibleTimeSeparatorBefore(root),
            hasTimeBeforeNext = nextHasVisibleTime,
            probeContext = root.context
        ) ?: return
        applyState(root, state, "adapter-$source:${adapter.javaClass.simpleName}:$resolvedPosition")
    }

    private fun clearOriginalBubbleContainers(messageView: View) {
        val parent = messageView.parent as? View ?: return
        if (parent.id != View.NO_ID && resourceName(parent) == resourceMessage) {
            return
        }
        clearViewLayer(parent)
    }

    private fun clearViewLayer(view: View) {
        view.background = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.foreground = null
        }
    }

    private fun clearCardForeground(target: BubbleTarget) {
        if (target.applyTextPadding || target.kind == "voice" || target.kind == "call") {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            target.bubbleView.foreground = null
        }
    }

    private fun normalizeRow(itemView: View, messageView: View, state: ChatBubbleStylePolicy.RenderState) {
        val row = findViewByResourceName(itemView, "bkj")
        forceTopGravity(row)
        if (state.position != ChatBubbleStylePolicy.GroupPosition.SINGLE) {
            clearDirectVerticalMargins(row)
        }
        listOfNotNull(itemView, row, messageView.parent as? View).forEach { view ->
            view.minimumHeight = 0
        }
    }

    private fun normalizeMessageColumn(
        itemView: View,
        messageView: View,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        val row = findViewByResourceName(itemView, "bkj") as? ViewGroup ?: return
        val column = directChildContaining(row, messageView) as? ViewGroup ?: return
        clearNestedVerticalMargins(column, messageView, depth = 0)
        syncMessageParentTopMargin(itemView, messageView, state)
    }

    private fun styleQuoteBlock(
        itemView: View,
        target: BubbleTarget,
        state: ChatBubbleStylePolicy.RenderState,
        palette: ChatBubbleStylePolicy.BubblePalette
    ) {
        if (target.kind != "text") {
            return
        }
        val messageView = target.layoutView
        val quoteText = findViewByResourceName(itemView, resourceQuoteText) ?: return
        val quote = renderedText(quoteText)
        if (!isRealQuotePreviewText(quote) || quoteText === messageView) {
            return
        }
        val quoteContainer = findViewByResourceName(itemView, resourceQuoteContainer)
            ?.takeIf { containsView(it, quoteText) && !containsView(it, messageView) }
            ?: quoteText
        val quoteBackplate = findViewByResourceName(itemView, resourceQuoteBackplate)
            ?.takeIf { (it.parent as? ViewGroup) === quoteContainer.parent }
        quoteBackplate?.apply {
            alpha = 0f
            background = null
        }

        quoteContainer.background = createQuoteDrawable(quoteContainer, palette)
        quoteContainer.minimumHeight = 0
        setTextColor(quoteText, palette.quoteTextColor, palette.quoteTextColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            quoteContainer.elevation = dp(quoteContainer, 1f).toFloat()
            quoteContainer.translationZ = 0f
        }
        disableAncestorClipping(quoteContainer)
        probe(quoteText, "quote.precise:${state.side}:${state.position}:${quote.orEmpty().take(18)}")
    }

    private fun isRealQuotePreviewText(text: String?): Boolean {
        val value = text?.trim() ?: return false
        if (value.isBlank()) {
            return false
        }
        return value != "{source}" && value != "{title}" && value != "{content}"
    }

    private fun createQuoteDrawable(
        view: View,
        palette: ChatBubbleStylePolicy.BubblePalette
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(palette.quoteFillColor)
            if (Color.alpha(palette.quoteStrokeColor) > 0) {
                setStroke(dp(view, 0.75f), palette.quoteStrokeColor)
            }
            cornerRadius = dp(view, 5.5f).toFloat()
        }
    }

    private fun applyShadow(view: View, state: ChatBubbleStylePolicy.RenderState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        view.elevation = dp(view, 5.5f).toFloat()
        view.translationZ = dp(view, 0.75f).toFloat()
        view.outlineProvider = object : ViewOutlineProvider() {
            private val outlinePath = Path()
            private val outlineRect = RectF()

            override fun getOutline(target: View, outline: Outline) {
                if (target.width <= 0 || target.height <= 0) {
                    return
                }
                outlineRect.set(0f, 0f, target.width.toFloat(), target.height.toFloat())
                outlinePath.reset()
                outlinePath.addRoundRect(
                    outlineRect,
                    floatArrayOf(
                        dp(target, state.cornerRadii.topLeft).toFloat(),
                        dp(target, state.cornerRadii.topLeft).toFloat(),
                        dp(target, state.cornerRadii.topRight).toFloat(),
                        dp(target, state.cornerRadii.topRight).toFloat(),
                        dp(target, state.cornerRadii.bottomRight).toFloat(),
                        dp(target, state.cornerRadii.bottomRight).toFloat(),
                        dp(target, state.cornerRadii.bottomLeft).toFloat(),
                        dp(target, state.cornerRadii.bottomLeft).toFloat()
                    ),
                    Path.Direction.CW
                )
                try {
                    @Suppress("DEPRECATION")
                    outline.setConvexPath(outlinePath)
                } catch (_: Throwable) {
                    val radius = if (state.position == ChatBubbleStylePolicy.GroupPosition.SINGLE) 24f else 20f
                    outline.setRoundRect(0, 0, target.width, target.height, dp(target, radius).toFloat())
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadowColor = Color.argb(34, 0, 0, 0)
            view.outlineAmbientShadowColor = shadowColor
            view.outlineSpotShadowColor = shadowColor
        }
    }

    private fun setTextColor(view: View, color: Int, semanticColor: Int) {
        if (view is TextView) {
            if (view.currentTextColor != color) {
                view.setTextColor(color)
                view.setHintTextColor(color)
            }
            view.setLinkTextColor(semanticColor)
            SemanticTextColorizer.apply(view, semanticColor)
            return
        }
        listOf("setTextColor", "setHintTextColor").forEach { method ->
            try {
                XposedHelpers.callMethod(view, method, color)
            } catch (_: Throwable) {
            }
        }
        try {
            XposedHelpers.callMethod(view, "setLinkTextColor", semanticColor)
        } catch (_: Throwable) {
        }
        SemanticTextColorizer.apply(view, semanticColor)
    }

    private fun setBubbleTextColors(target: BubbleTarget, color: Int, semanticColor: Int) {
        target.textView?.let { setTextColor(it, color, semanticColor) }
        setTextColorDeep(target.bubbleView, color, semanticColor)
    }

    private fun setTextColorDeep(view: View, color: Int, semanticColor: Int) {
        setTextColor(view, color, semanticColor)
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            setTextColorDeep(group.getChildAt(index), color, semanticColor)
        }
    }

    private fun tuneRichCardContent(target: BubbleTarget) {
        if (!isTunableRichCard(target.kind)) {
            return
        }
        tuneRichCardContentNow(target)
    }

    private fun tuneMediaContent(target: BubbleTarget) {
        if (target.kind != "video") {
            return
        }
        val imageView = findViewByResourceName(target.bubbleView, "bkm") as? ImageView ?: return
        if (imageView.scaleType != ImageView.ScaleType.CENTER_CROP) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        imageView.adjustViewBounds = false
        clearViewLayer(imageView)
        listOf("bqy", "boy").forEach { name ->
            findViewByResourceName(target.bubbleView, name)?.bringToFront()
        }
    }

    private fun isTunableRichCard(kind: String): Boolean {
        return kind == "mini-program" ||
                kind == "contact-card" ||
                kind == "position" ||
                kind == "rich-card" ||
                kind == "transfer" ||
                kind == "transfer-received" ||
                kind == "redpacket"
    }

    private fun tuneRichCardContentNow(target: BubbleTarget) {
        when (target.kind) {
            "mini-program" -> tuneMiniProgramCard(target.bubbleView)
            "contact-card" -> tuneContactCard(target.bubbleView)
            "position" -> tunePositionCard(target.bubbleView)
            "rich-card" -> tuneGenericCard(target.bubbleView)
            "transfer" -> tunePaymentCard(target.bubbleView, dividerColor = 0x33FFFFFF, received = false)
            "transfer-received" -> tunePaymentCard(target.bubbleView, dividerColor = 0x33E0852A, received = true)
            "redpacket" -> tuneRedpacketCard(target)
        }
    }

    private fun tuneMiniProgramCard(card: View) {
        val needsMeasuredTune = card.width <= dp(card, 34f) || card.height <= dp(card, 34f)
        setTextColorByName(card, "biu", 0xFF4F5850.toInt())
        setTextColorByName(card, "biq", ChatBubbleStylePolicy.DEFAULT_LEFT_TEXT_COLOR)
        setTextColorByName(card, "bit", 0xFF8F968E.toInt())
        tuneMiniProgramTitle(card)
        setMiniProgramPreviewStyle(card)
        tuneMiniProgramFooter(card)
        normalizeMiniProgramCardHeight(card)
        if (needsMeasuredTune) {
            scheduleMiniProgramMeasuredTune(card)
        }
    }

    private fun scheduleMiniProgramMeasuredTune(card: View) {
        if (XposedHelpers.getAdditionalInstanceField(card, keyPendingMiniProgramMeasuredTune) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(card, keyPendingMiniProgramMeasuredTune, true)
        card.post {
            XposedHelpers.removeAdditionalInstanceField(card, keyPendingMiniProgramMeasuredTune)
            if (card.width > dp(card, 34f) && card.visibility == View.VISIBLE) {
                tuneMiniProgramCard(card)
            }
        }
    }

    private fun tuneMiniProgramTitle(card: View) {
        val title = findViewByResourceName(card, "biq") as? TextView ?: return
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        title.setLineSpacing(0f, 1.0f)
        if (title.maxLines != 2) {
            title.maxLines = 2
        }
        if (title.ellipsize != TextUtils.TruncateAt.END) {
            title.ellipsize = TextUtils.TruncateAt.END
        }
        title.setMinLines(0)
        title.setMinHeight(0)
        title.minimumHeight = 0
        title.includeFontPadding = false
        setInsetWidthAndMargins(
            view = title,
            contentWidth = card.width,
            left = dp(card, 17f),
            right = dp(card, 17f)
        )
        setLayoutHeight(title, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setMiniProgramPreviewStyle(card: View) {
        val imageView = findViewByResourceName(card, "big") as? ImageView ?: return
        val panel = imageView.parent as? View ?: imageView
        setExactHeight(panel, dp(card, 98.5f))
        setInsetWidthAndMargins(
            view = panel,
            contentWidth = card.width,
            left = dp(card, 17f),
            right = dp(card, 17f)
        )
        if (panel !== imageView) {
            setExactHeight(imageView, ViewGroup.LayoutParams.MATCH_PARENT)
            setInsetWidthAndMargins(
                view = imageView,
                contentWidth = panel.width.takeIf { it > 0 } ?: (card.width - dp(card, 34f)),
                left = 0,
                right = 0
            )
        }
        panel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFF7F8F7.toInt())
            setStroke(dp(card, 0.75f), 0x0F000000)
            cornerRadius = dp(card, 12f).toFloat()
        }
        if (imageView.scaleType != ImageView.ScaleType.CENTER_CROP) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        panel.minimumHeight = 0
        clipRounded(panel, radiusDp = 12f)
    }

    private fun tuneMiniProgramFooter(card: View) {
        val footer = findViewByResourceName(card, "bir") as? ViewGroup ?: return
        footer.minimumHeight = 0
        setTopMargin(footer, dp(card, 10f))
        if (footer.childCount > 0) {
            tuneFixedCardDividerView(
                view = footer.getChildAt(0),
                contentWidth = card.width,
                horizontalInset = dp(card, 17f),
                dividerColor = 0x0F000000,
                dividerHeight = dp(card, 1f)
            )
        }
        if (footer.childCount > 1) {
            val footerRow = footer.getChildAt(1)
            setTopMargin(footerRow, dp(card, 8f))
            setInsetWidthAndMargins(
                view = footerRow,
                contentWidth = card.width,
                left = dp(card, 9f),
                right = dp(card, 17f)
            )
        }
    }

    private fun normalizeMiniProgramCardHeight(card: View) {
        if (card.width <= dp(card, 34f)) {
            return
        }
        val currentContentBottom = deepestVisibleChildBottom(card)
        val referenceHeight = scaledByMiniProgramCardWidth(card, miniProgramReferenceCardHeight)
        val desiredHeight = maxOf(referenceHeight, currentContentBottom + dp(card, 1f))
        if (desiredHeight <= dp(card, 34f)) {
            return
        }
        if (card.minimumHeight != 0) {
            card.minimumHeight = 0
        }
        setLayoutHeight(card, desiredHeight)
    }

    private fun tuneContactCard(card: View) {
        val needsMeasuredTune = card.width <= dp(card, 34f)
        setTextColorByName(card, "bpv", ChatBubbleStylePolicy.DEFAULT_LEFT_TEXT_COLOR)
        setTextColorByName(card, "br9", 0xFF8F968E.toInt())
        setContactCardMinHeight(card)
        tuneContactHeader(card)
        tuneContactDivider(card)
        tuneContactFooter(card)
        if (needsMeasuredTune) {
            scheduleContactMeasuredTune(card)
        }
    }

    private fun setContactCardMinHeight(card: View) {
        if (card.width <= dp(card, 34f)) {
            return
        }
        val desiredHeight = (card.width * contactReferenceCardHeight / contactReferenceCardWidth + 0.5f).toInt()
        setExactHeight(card, desiredHeight)
    }

    private fun scheduleContactMeasuredTune(card: View) {
        if (XposedHelpers.getAdditionalInstanceField(card, keyPendingContactMeasuredTune) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(card, keyPendingContactMeasuredTune, true)
        card.post {
            XposedHelpers.removeAdditionalInstanceField(card, keyPendingContactMeasuredTune)
            if (card.width > dp(card, 34f) && card.visibility == View.VISIBLE) {
                tuneContactCard(card)
            }
        }
    }

    private fun tuneContactHeader(card: View) {
        val avatar = findViewByResourceName(card, "bk2") ?: return
        setExactSize(avatar, dp(card, 40f), dp(card, 40f))
        val row = avatar.parent as? ViewGroup
        row?.setPadding(dp(card, 17f), dp(card, 13f), dp(card, 17f), 0)
        row?.getChildAt(1)?.let { setLeftMargin(it, dp(card, 12f)) }
    }

    private fun tuneContactDivider(card: View) {
        val divider = findWideThinImageView(card) ?: return
        tuneFixedCardDividerView(
            view = divider,
            contentWidth = card.width,
            horizontalInset = dp(card, 17f),
            dividerColor = 0x0F000000,
            dividerHeight = dp(card, 1f)
        )
    }

    private fun tuneContactFooter(card: View) {
        val footer = findViewByResourceName(card, "br9") as? TextView ?: return
        footer.minimumHeight = dp(card, 20f)
        footer.setPadding(dp(card, 17f), 0, dp(card, 17f), 0)
        setTopMargin(footer, dp(card, 8f))
    }

    private fun tunePositionCard(card: View) {
        val needsMeasuredTune = card.width <= dp(card, 34f) || card.height <= dp(card, 34f)
        setTextColorByName(card, "bp8", ChatBubbleStylePolicy.DEFAULT_LEFT_TEXT_COLOR)
        setTextColorByName(card, "bp6", 0xFF5F665F.toInt())
        findViewByResourceName(card, "bko")?.let { background ->
            clearViewLayer(background)
            if (background is ImageView) {
                background.setImageDrawable(null)
                background.clearColorFilter()
                background.alpha = 0f
            }
        }
        tunePositionHeader(card)
        tunePositionDivider(card)
        tunePositionMapPreview(card)
        if (needsMeasuredTune) {
            schedulePositionMeasuredTune(card)
        }
    }

    private fun schedulePositionMeasuredTune(card: View) {
        if (XposedHelpers.getAdditionalInstanceField(card, keyPendingPositionMeasuredTune) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(card, keyPendingPositionMeasuredTune, true)
        card.post {
            XposedHelpers.removeAdditionalInstanceField(card, keyPendingPositionMeasuredTune)
            if (card.width > dp(card, 34f) && card.visibility == View.VISIBLE) {
                tunePositionCard(card)
            }
        }
    }

    private fun tunePositionHeader(card: View) {
        if (card.width <= dp(card, 34f)) {
            return
        }
        val header = findViewByResourceName(card, "bp7") ?: return
        val headerFrame = header.parent as? View ?: header
        setLayoutHeight(headerFrame, scaledByPositionCardHeight(card, positionReferenceHeaderHeight))
        setHorizontalMargins(headerFrame, left = 0, right = 0, forceMatchParent = true)
        setLayoutHeight(header, ViewGroup.LayoutParams.MATCH_PARENT)
        header.setPadding(
            scaledByPositionCardWidth(card, 13f),
            scaledByPositionCardHeight(card, 13f),
            scaledByPositionCardWidth(card, 13f),
            0
        )
    }

    private fun tunePositionDivider(card: View) {
        val divider = findWideThinImageView(card) ?: return
        tuneFixedCardDividerView(
            view = divider,
            contentWidth = card.width,
            horizontalInset = 0,
            dividerColor = 0x0D000000,
            dividerHeight = dp(card, 0.75f)
        )
    }

    private fun tunePositionMapPreview(card: View) {
        if (card.width <= dp(card, 34f)) {
            return
        }
        val imageView = findViewByResourceName(card, "bp5") as? ImageView ?: return
        val panel = imageView.parent as? View ?: imageView
        clearViewLayer(panel)
        panel.setPadding(0, 0, 0, 0)
        panel.minimumHeight = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            panel.clipToOutline = false
        }
        setLayoutHeight(panel, scaledByPositionCardHeight(card, positionReferenceMapHeight))
        setHorizontalMargins(panel, left = 0, right = 0, forceMatchParent = true)
        setLayoutHeight(imageView, ViewGroup.LayoutParams.MATCH_PARENT)
        setHorizontalMargins(imageView, left = 0, right = 0, forceMatchParent = true)
        imageView.setPadding(0, 0, 0, 0)
        if (imageView.scaleType != ImageView.ScaleType.CENTER_CROP) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        findViewByResourceName(card, "bkp")?.let { marker ->
            marker.bringToFront()
        }
    }

    private fun tuneGenericCard(card: View) {
        tuneWebShareCard(card)
        val footerText = deepestVisibleTextView(card) { text ->
            text.contains("小程序") ||
                    text.contains("个人名片") ||
                    text.contains("链接") ||
                    text.contains("位置")
        }
        footerText?.let { setTextColor(it, 0xFF8F968E.toInt(), 0xFF8F968E.toInt()) }
        tuneDividerLines(card, horizontalInsetDp = 14f)
    }

    private fun tuneWebShareCard(card: View) {
        val needsMeasuredTune = card.width <= dp(card, 34f) ||
                (findViewByResourceName(card, "biy")?.height ?: 0) <= dp(card, 34f)
        setTextColorByName(card, "bjx", ChatBubbleStylePolicy.DEFAULT_LEFT_TEXT_COLOR)
        setTextColorByName(card, "bju", ChatBubbleStylePolicy.DEFAULT_LEFT_TEXT_COLOR)
        setTextColorByName(card, "bj2", 0xFF4F5850.toInt())
        setTextColorByName(card, "bjp", 0xFF8F968E.toInt())
        listOf("bjl", "bjp").forEach { name ->
            findViewByResourceName(card, name)?.let { clearViewLayer(it) }
        }
        setWebSharePreviewStyle(card)
        normalizeWebShareCardHeight(card)
        if (needsMeasuredTune) {
            scheduleWebShareMeasuredTune(card)
        }
    }

    private fun setWebSharePreviewStyle(card: View) {
        val imageView = findViewByResourceName(card, "bjs") as? ImageView ?: return
        val panel = findViewByResourceName(card, "bjr") ?: (imageView.parent as? View) ?: imageView
        panel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFF7F8F7.toInt())
            setStroke(dp(card, 0.75f), 0x0F000000)
            cornerRadius = dp(card, 12f).toFloat()
        }
        panel.minimumHeight = 0
        clipRounded(panel, radiusDp = 12f)
    }

    private fun normalizeWebShareCardHeight(card: View) {
        val content = findViewByResourceName(card, "biy") ?: return
        val desiredHeight = content.bottom + dp(card, 1f)
        if (desiredHeight <= dp(card, 34f)) {
            return
        }
        if (card.minimumHeight != 0) {
            card.minimumHeight = 0
        }
        setExactHeight(card, desiredHeight)
    }

    private fun scheduleWebShareMeasuredTune(card: View) {
        if (XposedHelpers.getAdditionalInstanceField(card, keyPendingWebShareMeasuredTune) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(card, keyPendingWebShareMeasuredTune, true)
        card.post {
            XposedHelpers.removeAdditionalInstanceField(card, keyPendingWebShareMeasuredTune)
            if (card.visibility == View.VISIBLE) {
                tuneWebShareCard(card)
            }
        }
    }

    private fun tunePaymentCard(card: View, dividerColor: Int, received: Boolean) {
        val needsMeasuredTune = card.width <= dp(card, 34f)
        setPaymentCardHeight(card)
        tunePaymentHeader(card, received)
        tunePaymentFooter(card)
        tunePaymentDivider(card, dividerColor, received)
        if (needsMeasuredTune) {
            schedulePaymentMeasuredTune(card, dividerColor, received)
        }
    }

    private fun setPaymentCardHeight(card: View) {
        if (card.width <= dp(card, 34f)) {
            return
        }
        setExactHeight(card, scaledByCardWidth(card, transferReferenceCardHeight))
    }

    private fun tunePaymentHeader(card: View, received: Boolean) {
        if (card.width <= dp(card, 34f)) {
            return
        }
        val icon = findViewByResourceName(card, "a45") ?: return
        val header = icon.parent as? ViewGroup ?: return
        setExactHeight(header, scaledByCardWidth(card, transferReferenceHeaderHeight))
        setExactSize(
            view = icon,
            width = scaledByCardWidth(card, 40f),
            height = scaledByCardWidth(card, 40f)
        )
        setPaymentMargins(
            view = icon,
            left = 0,
            top = scaledByCardWidth(card, 2.7f)
        )
        setPaymentIconStyle(icon, received)
        header.getChildAt(1)?.let { textColumn ->
            setExactHeight(textColumn, scaledByCardWidth(card, 42f))
            setPaymentMargins(
                view = textColumn,
                left = scaledByCardWidth(card, 11.4f),
                top = scaledByCardWidth(card, 4.7f)
            )
            tunePaymentTextColumn(card, textColumn)
        }
    }

    private fun setPaymentIconStyle(icon: View, received: Boolean) {
        val image = icon as? ImageView ?: return
        image.clearColorFilter()
        image.imageAlpha = 255
        image.setPadding(0, 0, 0, 0)
        image.scaleType = ImageView.ScaleType.FIT_XY
        image.setImageDrawable(TransferIconDrawable(received))
    }

    private fun tunePaymentFooter(card: View) {
        if (card.width <= dp(card, 34f)) {
            return
        }
        val footer = findViewByResourceName(card, "gbh") ?: return
        setExactHeight(footer, scaledByCardWidth(card, transferReferenceFooterHeight))
        findViewByResourceName(card, "a46")?.let {
            setTopMargin(it, scaledByCardWidth(card, 16f))
        }
    }

    private fun tunePaymentTextColumn(card: View, textColumn: View) {
        (textColumn as? ViewGroup)?.getChildAt(0)?.let { innerColumn ->
            setExactHeight(innerColumn, scaledByCardWidth(card, 42f))
        }
        setExactHeight(textColumn, scaledByCardWidth(card, 42f))
        (findViewByResourceName(textColumn, "a48") as? TextView)?.let { amount ->
            setExactHeight(amount, scaledByCardWidth(card, 18f))
            amount.includeFontPadding = false
            amount.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        (findViewByResourceName(textColumn, "a44") as? TextView)?.let { status ->
            setExactHeight(status, scaledByCardWidth(card, 17f))
            setTopMargin(status, scaledByCardWidth(card, 4f))
            status.includeFontPadding = true
            status.maxLines = 1
            status.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
    }

    private fun tunePaymentDivider(card: View, dividerColor: Int, received: Boolean) {
        val footer = findViewByResourceName(card, "gbh") ?: return
        val divider = findViewByResourceName(footer, "d0v") ?: return
        if (divider is ImageView) {
            divider.setImageDrawable(null)
            divider.clearColorFilter()
        }
        divider.alpha = 1f
        divider.minimumHeight = 0
        divider.setPadding(0, 0, 0, 0)
        divider.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(dividerColor)
        }
        val params = divider.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val outerInset = scaledByCardWidth(card, 17f)
        val innerInset = scaledByCardWidth(card, 12.4f)
        val leftInset = if (received) outerInset else innerInset
        val rightInset = if (received) innerInset else outerInset
        val width = card.width - leftInset - rightInset
        var changed = false
        if (width > 0 && params.width != width) {
            params.width = width
            changed = true
        }
        val height = scaledByCardWidth(card, 1f).coerceAtLeast(1)
        if (params.height != height) {
            params.height = height
            changed = true
        }
        if (params.leftMargin != leftInset) {
            params.leftMargin = leftInset
            changed = true
        }
        if (params.rightMargin != rightInset) {
            params.rightMargin = rightInset
            changed = true
        }
        if (changed) {
            divider.layoutParams = params
            divider.requestLayout()
        }
    }

    private fun setPaymentMargins(view: View, left: Int, top: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var changed = false
        if (params.leftMargin != left) {
            params.leftMargin = left
            changed = true
        }
        if (params.topMargin != top) {
            params.topMargin = top
            changed = true
        }
        if (changed) {
            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun schedulePaymentMeasuredTune(card: View, dividerColor: Int, received: Boolean) {
        if (XposedHelpers.getAdditionalInstanceField(card, keyPendingPaymentMeasuredTune) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(card, keyPendingPaymentMeasuredTune, true)
        card.post {
            XposedHelpers.removeAdditionalInstanceField(card, keyPendingPaymentMeasuredTune)
            if (card.width > dp(card, 34f) && card.visibility == View.VISIBLE) {
                tunePaymentCard(card, dividerColor, received)
            }
        }
    }

    private fun scaledByCardWidth(card: View, referenceValue: Float): Int {
        return (card.width * referenceValue / transferReferenceCardWidth + 0.5f).toInt()
    }

    private fun scaledByMiniProgramCardWidth(card: View, referenceValue: Float): Int {
        return (card.width * referenceValue / miniProgramReferenceCardWidth + 0.5f).toInt()
    }

    private fun scaledByPositionCardWidth(card: View, referenceValue: Float): Int {
        return (card.width * referenceValue / positionReferenceCardWidth + 0.5f).toInt()
    }

    private fun scaledByPositionCardHeight(card: View, referenceValue: Float): Int {
        val height = card.height.takeIf { it > dp(card, 34f) }
        if (height != null) {
            return (height * referenceValue / positionReferenceCardHeight + 0.5f).toInt()
        }
        return scaledByPositionCardWidth(card, referenceValue)
    }

    private fun tuneRedpacketCard(target: BubbleTarget) {
        val card = target.bubbleView
        tuneDividerLines(card, horizontalInsetDp = 14f, dividerColor = 0x33FFFFFF)
        target.namedViews["adsView"]?.let { hideRedpacketAdsView(it) }
        target.namedViews["msgView"]?.let {
            setTextColor(it, 0xFFFFFFFF.toInt(), 0xFFFFEBDD.toInt())
        }
        target.namedViews["msgView1"]?.let {
            setTextColor(it, 0xFFFFEBDD.toInt(), 0xFFFFEBDD.toInt())
        }
        target.namedViews["titleView"]?.let {
            setTextColor(it, 0xCCFFFFFF.toInt(), 0xCCFFFFFF.toInt())
        }
        target.namedViews["leftPicView"]?.let { tuneRedpacketIcon(it) }
    }

    private fun hideRedpacketAdsView(view: View) {
        clearViewLayer(view)
        view.alpha = 0f
        view.visibility = View.GONE
        view.minimumHeight = 0
        val params = view.layoutParams ?: return
        if (params.height != 0) {
            params.height = 0
            view.layoutParams = params
        }
    }

    private fun tuneRedpacketIcon(view: View) {
        if (view is ImageView) {
            view.alpha = 0.95f
            view.clearColorFilter()
        }
    }

    private fun setPreviewPanelStyle(
        card: View,
        imageName: String,
        desiredHeightDp: Float,
        horizontalInsetDp: Float = 0f,
        topMarginDp: Float = 0f,
        bottomMarginDp: Float = 0f
    ) {
        val imageView = findViewByResourceName(card, imageName) as? ImageView ?: return
        val panel = imageView.parent as? View ?: imageView
        setExactHeight(panel, dp(card, desiredHeightDp))
        setPanelMargins(
            view = panel,
            horizontalInset = dp(card, horizontalInsetDp),
            topMargin = dp(card, topMarginDp),
            bottomMargin = dp(card, bottomMarginDp)
        )
        if (panel !== imageView) {
            setExactHeight(imageView, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        panel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFF7F8F7.toInt())
            setStroke(dp(card, 0.75f), 0x0F000000)
            cornerRadius = dp(card, 12f).toFloat()
        }
        if (imageView.scaleType != ImageView.ScaleType.CENTER_CROP) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        panel.minimumHeight = 0
        clipRounded(panel, radiusDp = 12f)
    }

    private fun setExactHeight(view: View, height: Int) {
        val params = view.layoutParams ?: return
        if (params.height == height) {
            return
        }
        params.height = height
        view.layoutParams = params
    }

    private fun setExactSize(view: View, width: Int, height: Int) {
        val params = view.layoutParams ?: return
        var changed = false
        if (params.width != width) {
            params.width = width
            changed = true
        }
        if (params.height != height) {
            params.height = height
            changed = true
        }
        if (changed) {
            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun setLayoutHeight(view: View, height: Int) {
        val params = view.layoutParams ?: return
        if (params.height == height) {
            return
        }
        params.height = height
        view.layoutParams = params
        view.requestLayout()
    }

    private fun setPanelMargins(
        view: View,
        horizontalInset: Int,
        topMargin: Int,
        bottomMargin: Int
    ) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var changed = false
        if (horizontalInset > 0) {
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (params.leftMargin != horizontalInset || params.rightMargin != horizontalInset) {
                params.leftMargin = horizontalInset
                params.rightMargin = horizontalInset
                changed = true
            }
        }
        if (params.topMargin != topMargin) {
            params.topMargin = topMargin
            changed = true
        }
        if (params.bottomMargin != bottomMargin) {
            params.bottomMargin = bottomMargin
            changed = true
        }
        if (changed) {
            view.layoutParams = params
        }
    }

    private fun setHorizontalMargins(
        view: View,
        left: Int,
        right: Int,
        forceMatchParent: Boolean = false
    ) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var changed = false
        if (forceMatchParent && params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (params.leftMargin != left) {
            params.leftMargin = left
            changed = true
        }
        if (params.rightMargin != right) {
            params.rightMargin = right
            changed = true
        }
        if (changed) {
            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun setLeftMargin(view: View, left: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.leftMargin == left) {
            return
        }
        params.leftMargin = left
        view.layoutParams = params
        view.requestLayout()
    }

    private fun setInsetWidthAndMargins(
        view: View,
        contentWidth: Int,
        left: Int,
        right: Int
    ) {
        if (contentWidth <= left + right) {
            return
        }
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var changed = false
        val width = contentWidth - left - right
        if (params.width != width) {
            params.width = width
            changed = true
        }
        if (params.leftMargin != left) {
            params.leftMargin = left
            changed = true
        }
        if (params.rightMargin != right) {
            params.rightMargin = right
            changed = true
        }
        if (changed) {
            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun clipRounded(view: View, radiusDp: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        val radiusPx = dp(view, radiusDp).toFloat()
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                if (target.width <= 0 || target.height <= 0) {
                    return
                }
                outline.setRoundRect(0, 0, target.width, target.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }

    private fun setTextColorByName(root: View, name: String, color: Int) {
        findViewByResourceName(root, name)?.let { setTextColor(it, color, color) }
    }

    private fun tuneDividerLines(
        root: View,
        horizontalInsetDp: Float,
        dividerColor: Int = 0x0A000000
    ) {
        val cardWidth = root.width
        if (cardWidth <= 0 || root.visibility != View.VISIBLE) {
            return
        }
        val inset = dp(root, horizontalInsetDp)
        fun visit(view: View, depth: Int) {
            if (depth > 8 || view.visibility != View.VISIBLE) {
                return
            }
            if (view !== root && isLikelyCardDivider(root, view, inset)) {
                tuneCardDividerView(view, inset, dividerColor)
            }
            val group = view as? ViewGroup ?: return
            for (index in 0 until group.childCount) {
                visit(group.getChildAt(index), depth + 1)
            }
        }
        visit(root, 0)
    }

    private fun findWideThinImageView(root: View): View? {
        val rootWidth = root.width
        if (rootWidth <= 0) {
            return null
        }
        fun visit(view: View, depth: Int): View? {
            if (depth > 8 || view.visibility != View.VISIBLE) {
                return null
            }
            if (view is ImageView &&
                view.width > rootWidth / 2 &&
                view.height in 1..dp(root, 3f)
            ) {
                return view
            }
            val group = view as? ViewGroup ?: return null
            for (index in 0 until group.childCount) {
                visit(group.getChildAt(index), depth + 1)?.let { return it }
            }
            return null
        }
        return visit(root, 0)
    }

    private fun tuneCardDividerView(view: View, horizontalInset: Int, dividerColor: Int) {
        if (view is ImageView) {
            view.setImageDrawable(null)
            view.clearColorFilter()
        }
        view.alpha = 1f
        view.minimumHeight = 0
        view.setPadding(0, 0, 0, 0)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(dividerColor)
        }
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = 1
            params.leftMargin = horizontalInset
            params.rightMargin = horizontalInset
            view.layoutParams = params
        }
    }

    private fun tuneFixedCardDividerView(
        view: View,
        contentWidth: Int,
        horizontalInset: Int,
        dividerColor: Int,
        dividerHeight: Int = 1
    ) {
        if (view is ImageView) {
            view.setImageDrawable(null)
            view.clearColorFilter()
        }
        view.alpha = 1f
        view.minimumHeight = 0
        view.setPadding(0, 0, 0, 0)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(dividerColor)
        }
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var changed = false
        if (contentWidth <= horizontalInset * 2) {
            return
        }
        val width = contentWidth - horizontalInset * 2
        if (params.width != width) {
            params.width = width
            changed = true
        }
        val height = dividerHeight.coerceAtLeast(1)
        if (params.height != height) {
            params.height = height
            changed = true
        }
        if (params.leftMargin != horizontalInset) {
            params.leftMargin = horizontalInset
            changed = true
        }
        if (params.rightMargin != horizontalInset) {
            params.rightMargin = horizontalInset
            changed = true
        }
        if (changed) {
            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun isLikelyCardDivider(root: View, view: View, inset: Int): Boolean {
        if (view is TextView || view.width <= inset * 4) {
            return false
        }
        val maxDividerHeight = maxOf(2, dp(root, 1f))
        return view.height in 1..maxDividerHeight &&
                view.width >= root.width / 2
    }

    private fun deepestVisibleTextView(root: View, predicate: (String) -> Boolean): TextView? {
        if (root.visibility != View.VISIBLE) {
            return null
        }
        var result: TextView? = null
        fun visit(view: View, depth: Int) {
            if (depth > 8 || view.visibility != View.VISIBLE) {
                return
            }
            if (view is TextView) {
                val text = view.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank() && predicate(text)) {
                    result = view
                }
            }
            val group = view as? ViewGroup ?: return
            for (index in 0 until group.childCount) {
                visit(group.getChildAt(index), depth + 1)
            }
        }
        visit(root, 0)
        return result
    }

    private fun deepestVisibleChildBottom(root: View): Int {
        val group = root as? ViewGroup ?: return 0
        var result = 0
        fun visit(parent: ViewGroup, parentTop: Int, depth: Int) {
            if (depth > 8 || parent.visibility != View.VISIBLE) {
                return
            }
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (child.visibility != View.VISIBLE) {
                    continue
                }
                result = maxOf(result, parentTop + child.bottom)
                (child as? ViewGroup)?.let {
                    visit(it, parentTop + child.top, depth + 1)
                }
            }
        }
        visit(group, 0, 0)
        return result
    }

    private fun syncAvatarAndNicknameState(
        itemView: View,
        target: BubbleTarget,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        syncRowChromeState(itemView, target, state)
    }

    private fun syncRowChromeState(
        itemView: View,
        target: BubbleTarget?,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        val avatarView = findViewByResourceName(itemView, resourceAvatar)
        setAvatarVisibility(avatarView, state.showAvatar)
        setNicknameVisibility(findNicknameView(itemView), state.showNickname)
        resetAvatarPlacement(avatarView)
        if (target != null && target.kind != "text" && state.showAvatar) {
            alignVisibleAvatarToBubbleBottom(itemView, target.layoutView, state)
        }
    }

    private fun renderTextForSignature(target: BubbleTarget): String? {
        target.signatureText?.takeIf { it.isNotBlank() }?.let { return it }
        renderedText(target.textView)?.takeIf { it.isNotBlank() }?.let { return it }
        renderedText(target.bubbleView)?.takeIf { it.isNotBlank() }?.let { return it }
        target.bubbleView.contentDescription
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return target.kind
    }

    private fun setAvatarVisibility(avatarView: View?, visible: Boolean) {
        avatarView ?: return
        val container = avatarView.parent as? View ?: avatarView
        val params = container.layoutParams
        if (params != null) {
            val remembered = XposedHelpers.getAdditionalInstanceField(container, keyOriginalAvatarHeight) as? Int
            val observedSize = maxOf(
                params.height,
                params.width,
                container.height,
                container.width,
                avatarView.height,
                avatarView.width,
                dp(avatarView, 45f)
            )
            if (remembered == null || remembered <= 0) {
                XposedHelpers.setAdditionalInstanceField(container, keyOriginalAvatarHeight, observedSize)
            }
        }
        setAvatarRowGravity(container, visible)
        if (visible) {
            val originalHeight = (XposedHelpers.getAdditionalInstanceField(container, keyOriginalAvatarHeight) as? Int)
                ?.takeIf { it > 0 }
                ?: dp(avatarView, 45f)
            if (params != null && params.height != originalHeight) {
                params.height = originalHeight
                if (params.width == 0) {
                    params.width = originalHeight
                }
                container.layoutParams = params
            }
            container.visibility = View.VISIBLE
            avatarView.visibility = View.VISIBLE
        } else {
            if (params != null && params.height != 0) {
                params.height = 0
                container.layoutParams = params
            }
            container.visibility = View.INVISIBLE
            avatarView.visibility = View.INVISIBLE
        }
    }

    private fun setNicknameVisibility(nicknameView: View?, visible: Boolean) {
        nicknameView ?: return
        nicknameView.visibility = if (visible && !renderedText(nicknameView).isNullOrBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun findNicknameView(itemView: View): View? {
        findViewByResourceName(itemView, resourceNicknameModern)?.let { return it }
        return findViewByResourceName(itemView, resourceNickname)
            ?.takeUnless { isTimeSeparatorView(it) }
    }

    private fun findBubbleTarget(itemView: View): BubbleTarget? {
        findKnownRichBubbleTarget(itemView)?.let { return it }
        findResourceSignalCardTarget(itemView)?.let { return it }
        findCallBubbleTarget(itemView)?.let { return it }
        findVoiceBubbleTarget(itemView)?.let { return it }
        findRedpacketBubbleTargetByStructure(itemView)?.let { return it }
        val messageView = findViewByResourceName(itemView, resourceMessage)
        if (messageView != null) {
            return BubbleTarget(
                bubbleView = messageView,
                textView = messageView,
                kind = "text",
                applyTextPadding = true
            )
        }
        findVideoBubbleTarget(itemView)?.let { return it }
        findImageBubbleTargetByStructure(itemView)?.let { return it }
        return if (enableGenericRichCardHeuristic) {
            findRichCardBubbleTarget(itemView)
        } else {
            null
        }
    }

    private fun findVideoBubbleTarget(itemView: View): BubbleTarget? {
        if (findViewByResourceName(itemView, resourceVoiceBubble) != null) {
            return null
        }
        val mediaBubble = findViewByResourceName(itemView, resourceCallBubble) ?: return null
        val imageView = findViewByResourceName(mediaBubble, "bkm") as? ImageView ?: return null
        val durationText = renderedText(findViewByResourceName(mediaBubble, "boy"))
        val hasVideoChrome = !durationText.isNullOrBlank() ||
                findViewByResourceName(mediaBubble, "bqy") != null
        if (!hasVideoChrome) {
            return null
        }
        if (!isLikelyChatImage(itemView, imageView)) {
            return null
        }
        return BubbleTarget(
            bubbleView = mediaBubble,
            textView = null,
            kind = "video",
            applyTextPadding = false,
            layoutView = mediaBubble,
            signatureText = "video:${durationText.orEmpty()}:${mediaBubble.width}x${mediaBubble.height}",
            clipToOutline = true
        )
    }

    private fun findRedpacketBubbleTargetByStructure(itemView: View): BubbleTarget? {
        if (findViewByResourceName(itemView, resourceMessage) != null ||
            findViewByResourceName(itemView, resourceVoiceBubble) != null ||
            findViewByResourceName(itemView, resourceCallBubble) != null
        ) {
            return null
        }
        val sample = renderedTextDeep(itemView).orEmpty()
        if (!ChatBubbleStylePolicy.hasRedpacketCardTextSignal(sample)) {
            return null
        }
        val row = findViewByResourceName(itemView, "bkj") ?: itemView
        val root = row as? ViewGroup ?: return null
        val candidates = mutableListOf<RichCardCandidate>()
        collectRichCardCandidates(
            root = root,
            itemView = itemView,
            current = root,
            depth = 0,
            candidates = candidates
        )
        val candidate = candidates
            .filter { ChatBubbleStylePolicy.hasRedpacketCardTextSignal(it.metrics.textSample) }
            .maxByOrNull { it.score }
            ?: return null
        return BubbleTarget(
            bubbleView = candidate.view,
            textView = null,
            kind = "redpacket",
            applyTextPadding = false,
            layoutView = candidate.view,
            signatureText = candidate.metrics.textSample.ifBlank { sample.take(160) },
            clipToOutline = true
        )
    }

    private fun findVoiceBubbleTarget(itemView: View): BubbleTarget? {
        val voiceBubble = findViewByResourceName(itemView, resourceVoiceBubble) ?: return null
        val voiceContainer = (voiceBubble.parent as? View)
            ?.takeIf { resourceName(it) == resourceVoiceContainer }
            ?: voiceBubble
        val durationView = findViewByResourceName(voiceContainer, resourceMessage)
            ?: findViewByResourceName(itemView, resourceMessage)
        val hasVoiceSignal = voiceBubble.contentDescription
            ?.toString()
            ?.contains("语音") == true ||
                renderedText(durationView)?.contains("\"") == true
        if (!hasVoiceSignal) {
            return null
        }
        return BubbleTarget(
            bubbleView = voiceContainer,
            textView = durationView,
            kind = "voice",
            applyTextPadding = false,
            layoutView = voiceBubble,
            clearViews = if (voiceContainer !== voiceBubble) listOf(voiceBubble) else emptyList()
        )
    }

    private fun findCallBubbleTarget(itemView: View): BubbleTarget? {
        val callBubble = findViewByResourceName(itemView, resourceCallBubble) ?: return null
        val textView = findViewByResourceName(callBubble, resourceCallText)
            ?: findViewByResourceName(itemView, resourceCallText)
        val text = renderedText(textView) ?: renderedText(callBubble)
        val hasCallSignal = text?.contains("通话") == true ||
                text?.contains("接听") == true ||
                text?.contains("拒绝") == true ||
                text?.contains("已取消") == true
        if (textView == null || !hasCallSignal) {
            return null
        }
        return BubbleTarget(
            bubbleView = callBubble,
            textView = textView,
            kind = "call",
            applyTextPadding = false
        )
    }

    private fun findKnownRichBubbleTarget(itemView: View): BubbleTarget? {
        knownRichSpecs.forEach { spec ->
            val target = spec.resolve(itemView)
            if (target != null) {
                return target
            }
        }
        return null
    }

    private val knownRichSpecs: List<KnownRichSpec> by lazy {
        listOf(
            KnownRichSpec(
                tree = VTTV.ChatLeftContactCardItem,
                targetKey = "bgView",
                kind = "contact-card"
            ),
            KnownRichSpec(
                tree = VTTV.ChatRightContactCardItem,
                targetKey = "bgView",
                kind = "contact-card"
            ),
            KnownRichSpec(
                tree = VTTV.ChatLeftPositionItem,
                targetKey = "bgView",
                kind = "position",
                clipToOutline = true
            ),
            KnownRichSpec(
                tree = VTTV.ChatRightPositionItem,
                targetKey = "bgView",
                kind = "position",
                clipToOutline = true
            ),
            KnownRichSpec(
                tree = VTTV.ChatLeftSharingItem,
                targetKey = "miniProgramBgView",
                kind = "mini-program",
                clearKeys = listOf("miniProgramBgView_bgView"),
                clearKeysRoot = ClearKeysRoot.TARGET,
                clipToOutline = true
            ),
            KnownRichSpec(
                tree = VTTV.ChatRightSharingItem,
                targetKey = "miniProgramBgView",
                kind = "mini-program",
                clearKeys = listOf("miniProgramBgView_bgView"),
                clearKeysRoot = ClearKeysRoot.TARGET,
                clipToOutline = true
            ),
            KnownRichSpec(
                tree = VTTV.ChatLeftRedPacketItem,
                targetKey = "bgView",
                kind = "redpacket",
                clearKeys = listOf("adsView"),
                clipToOutline = true
            ),
            KnownRichSpec(
                tree = VTTV.ChatRightRedPacketItem,
                targetKey = "bgView",
                kind = "redpacket",
                clearKeys = listOf("adsView"),
                clipToOutline = true
            ),
            KnownRichSpec(
                tree = VTTV.ChatLeftPictureItem,
                targetPath = intArrayOf(4, 1, 1),
                kind = "image",
                clipToOutline = true
            ),
            KnownRichSpec(
                tree = VTTV.ChatRightPictureItem,
                targetPath = intArrayOf(4, 1, 1, 0),
                kind = "image",
                clipToOutline = true
            )
        )
    }

    private enum class ClearKeysRoot {
        ITEM,
        TARGET
    }

    private data class KnownRichSpec(
        val tree: ViewTree,
        val targetKey: String? = null,
        val targetPath: IntArray? = null,
        val kind: String,
        val clearKeys: List<String> = emptyList(),
        val clearKeysRoot: ClearKeysRoot = ClearKeysRoot.ITEM,
        val clipToOutline: Boolean = false
    ) {
        fun resolve(itemView: View): BubbleTarget? {
            if (!ViewTreeUtils.equals(tree.item, itemView)) {
                return null
            }
            val target = when {
                targetPath != null -> ViewUtils.getChildView1(itemView, targetPath)
                targetKey != null -> ViewUtils.getChildView1(itemView, tree.treeStacks[targetKey])
                else -> null
            } ?: return null
            val clearViews = clearKeys.mapNotNull { key ->
                val root = if (clearKeysRoot == ClearKeysRoot.TARGET) target else itemView
                ViewUtils.getChildView1(root, tree.treeStacks[key])
            }
            val namedViews = mutableMapOf<String, View>()
            tree.treeStacks.forEach { entry ->
                try {
                    ViewUtils.getChildView1(itemView, entry.value)?.let { namedViews[entry.key] = it }
                } catch (_: Throwable) {
                }
            }
            val text = renderedTextDeep(target)
                ?: target.contentDescription?.toString()
                ?: kind
            return BubbleTarget(
                bubbleView = target,
                textView = null,
                kind = classifyKind(kind, text, target),
                applyTextPadding = false,
                layoutView = target,
                clearViews = clearViews,
                namedViews = namedViews,
                signatureText = text.ifBlank { kind },
                clipToOutline = clipToOutline
            )
        }

        private fun classifyKind(fallback: String, text: String, target: View): String {
            val hasMiniProgramFooter = hasMiniProgramFooterSignal(target)
            val hasWebShareSignals = findViewByResourceName(target, "biy") != null &&
                    hasAnyResourceName(target, "bjx", "bju", "bjr", "bjs", "bjl", "bjp")
            if (fallback == "mini-program") {
                return when {
                    hasMiniProgramFooter -> "mini-program"
                    hasWebShareSignals -> "rich-card"
                    else -> "mini-program"
                }
            }
            return when {
                fallback == "redpacket" -> "redpacket"
                text.contains("微信转账") ||
                        text.contains("转账") ||
                        text.contains("收款") ||
                        text.contains("¥") ||
                        text.contains("￥") -> classifyTransferKind(text)
                ChatBubbleStylePolicy.hasRedpacketCardTextSignal(text) -> "redpacket"
                hasMiniProgramFooter -> "mini-program"
                text.contains("个人名片") || text.contains("名片") -> "contact-card"
                hasWebShareSignals -> "rich-card"
                else -> fallback
            }
        }
    }

    private fun findResourceSignalCardTarget(itemView: View): BubbleTarget? {
        val target = findViewByResourceName(itemView, resourceCallBubble) ?: return null
        val sample = renderedTextDeep(target).orEmpty()
        val hasTransferOrGenericCardSignals = hasAnyResourceName(
            target,
            "a3u",
            "a3m",
            "a3y",
            "a3o",
            "a3n"
        )
        val hasModernTransferSignals = findViewByResourceName(target, "a48") != null &&
                findViewByResourceName(target, "a46") != null &&
                (findViewByResourceName(target, "a44") != null ||
                        findViewByResourceName(target, "a45") != null ||
                        findViewByResourceName(target, "gbh") != null)
        val hasTransferTextSignal = hasTransferTextSignal(sample)
        val hasMiniProgramFooter = hasMiniProgramFooterSignal(target)
        val hasWebShareSignals = hasAnyResourceName(
            target,
            "bju",
            "bj2",
            "bjr",
            "bjs"
        )
        if (!hasTransferOrGenericCardSignals &&
            !hasModernTransferSignals &&
            !hasTransferTextSignal &&
            !hasMiniProgramFooter &&
            !hasWebShareSignals
        ) {
            return null
        }
        val clearViews = listOfNotNull(
            findViewByResourceName(target, "bma"),
            findViewByResourceName(target, "bmb"),
            findViewByResourceName(target, "bko")
        )
        return BubbleTarget(
            bubbleView = target,
            textView = null,
            kind = classifyKnownCardKind(
                text = sample,
                hasTransferSignals = hasTransferOrGenericCardSignals ||
                        hasModernTransferSignals ||
                        hasTransferTextSignal,
                hasMiniProgramFooter = hasMiniProgramFooter,
                hasWebShareSignals = hasWebShareSignals
            ),
            applyTextPadding = false,
            layoutView = target,
            clearViews = clearViews,
            signatureText = sample.ifBlank { "card:${resourceName(target).orEmpty()}" },
            clipToOutline = true
        )
    }

    private fun classifyKnownCardKind(
        text: String,
        hasTransferSignals: Boolean,
        hasMiniProgramFooter: Boolean,
        hasWebShareSignals: Boolean
    ): String {
        return when {
            hasTransferSignals -> classifyTransferKind(text)
            hasMiniProgramFooter -> "mini-program"
            ChatBubbleStylePolicy.hasRedpacketCardTextSignal(text) -> "redpacket"
            text.contains("个人名片") || text.contains("名片") -> "contact-card"
            hasWebShareSignals -> "rich-card"
            else -> "rich-card"
        }
    }

    private fun hasTransferTextSignal(text: String): Boolean {
        val hasCurrency = text.contains("¥") || text.contains("￥")
        val hasTransferStatus = text.contains("请收款") ||
                text.contains("已收款") ||
                text.contains("待入账") ||
                text.contains("已退还") ||
                text.contains("已过期") ||
                text.contains("已取消")
        return text.contains("微信转账") ||
                text.contains("转账") ||
                (hasCurrency && hasTransferStatus)
    }

    private fun classifyTransferKind(text: String): String {
        return if (text.contains("已收款") ||
            text.contains("已被接收") ||
            text.contains("待入账") ||
            text.contains("已退还") ||
            text.contains("已过期") ||
            text.contains("已取消")
        ) {
            "transfer-received"
        } else {
            "transfer"
        }
    }

    private fun findImageBubbleTargetByStructure(itemView: View): BubbleTarget? {
        if (findViewByResourceName(itemView, resourceMessage) != null ||
            findViewByResourceName(itemView, resourceCallBubble) != null ||
            findViewByResourceName(itemView, resourceVoiceBubble) != null
        ) {
            return null
        }
        val candidates = mutableListOf<ImageBubbleCandidate>()
        collectImageBubbleCandidates(itemView, itemView, candidates, depth = 0)
        val candidate = candidates.maxByOrNull { it.score } ?: return null
        val target = nearestCompactImageContainer(candidate.view)
        return BubbleTarget(
            bubbleView = target,
            textView = null,
            kind = "image",
            applyTextPadding = false,
            layoutView = target,
            signatureText = "image:${target.width}x${target.height}",
            clipToOutline = true
        )
    }

    private data class ImageBubbleCandidate(
        val view: ImageView,
        val score: Int
    )

    private fun collectImageBubbleCandidates(
        itemView: View,
        current: View,
        candidates: MutableList<ImageBubbleCandidate>,
        depth: Int
    ) {
        if (depth > 9 || current.visibility != View.VISIBLE) {
            return
        }
        if (current is ImageView && isLikelyChatImage(itemView, current)) {
            candidates += ImageBubbleCandidate(current, current.width * current.height)
        }
        val group = current as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectImageBubbleCandidates(itemView, group.getChildAt(index), candidates, depth + 1)
        }
    }

    private fun isLikelyChatImage(itemView: View, image: ImageView): Boolean {
        findViewByResourceName(itemView, resourceAvatar)?.let { avatar ->
            if (image === avatar || containsView(image, avatar) || containsView(avatar, image)) {
                return false
            }
        }
        val name = resourceName(image)
        if (name in setOf("bqx", "ott", "br0", "bkq", "bqz", "bjq", "bjk", "ins")) {
            return false
        }
        val minSide = dp(image, 48f)
        val maxSide = dp(image, 340f)
        if (image.width < minSide || image.height < minSide) {
            return false
        }
        return image.width <= maxSide && image.height <= dp(image, 520f)
    }

    private fun nearestCompactImageContainer(image: ImageView): View {
        var target: View = image
        var current = image.parent as? View
        var depth = 0
        while (current is ViewGroup && depth < 3) {
            val widthDelta = kotlin.math.abs(current.width - image.width)
            val heightDelta = kotlin.math.abs(current.height - image.height)
            val compact = current.width > 0 &&
                    current.height > 0 &&
                    widthDelta <= dp(image, 24f) &&
                    heightDelta <= dp(image, 24f)
            if (!compact || current.childCount > 4) {
                break
            }
            target = current
            current = current.parent as? View
            depth++
        }
        return target
    }

    private fun findRichCardBubbleTarget(itemView: View): BubbleTarget? {
        val row = findViewByResourceName(itemView, "bkj") ?: itemView
        val group = row as? ViewGroup ?: return null
        val candidates = mutableListOf<RichCardCandidate>()
        collectRichCardCandidates(
            root = group,
            itemView = itemView,
            current = group,
            depth = 0,
            candidates = candidates
        )
        val candidate = candidates.maxByOrNull { it.score } ?: return null
        val kind = classifyRichCard(candidate.metrics.textSample)
        return BubbleTarget(
            bubbleView = candidate.view,
            textView = null,
            kind = kind,
            applyTextPadding = false,
            layoutView = candidate.view,
            signatureText = candidate.metrics.textSample.ifBlank { kind },
            clipToOutline = true
        )
    }

    private fun collectRichCardCandidates(
        root: ViewGroup,
        itemView: View,
        current: View,
        depth: Int,
        candidates: MutableList<RichCardCandidate>
    ): RichCardMetrics {
        if (current.visibility != View.VISIBLE) {
            return RichCardMetrics(textCount = 0, imageCount = 0, textSample = "")
        }
        var textCount = if (isTextBearingLeaf(current)) 1 else 0
        var imageCount = if (isImageBearingLeaf(current)) 1 else 0
        val samples = mutableListOf<String>()
        renderedText(current)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isTimeLikeText(it) }
            ?.let { samples.add(it) }

        val group = current as? ViewGroup
        if (group != null && depth < 8) {
            for (index in 0 until group.childCount) {
                val metrics = collectRichCardCandidates(
                    root = root,
                    itemView = itemView,
                    current = group.getChildAt(index),
                    depth = depth + 1,
                    candidates = candidates
                )
                textCount += metrics.textCount
                imageCount += metrics.imageCount
                if (metrics.textSample.isNotBlank()) {
                    samples.add(metrics.textSample)
                }
            }
        }

        val textSample = samples
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(160)
        val metrics = RichCardMetrics(
            textCount = textCount,
            imageCount = imageCount,
            textSample = textSample
        )
        if (group != null && isRichCardCandidate(root, itemView, current, metrics)) {
            val score = richCardCandidateScore(current, metrics)
            candidates.add(RichCardCandidate(current, metrics, score))
        }
        return metrics
    }

    private fun isRichCardCandidate(
        root: ViewGroup,
        itemView: View,
        view: View,
        metrics: RichCardMetrics
    ): Boolean {
        if (view === root || view === itemView) {
            return false
        }
        val name = resourceName(view)
        if (name == resourceItemRoot ||
            name == "bkj" ||
            name == resourceMessage ||
            name == resourceAvatar ||
            name == resourceNickname ||
            name == resourceNicknameModern ||
            name == resourceVoiceContainer ||
            name == resourceVoiceBubble ||
            name == resourceCallBubble
        ) {
            return false
        }
        findViewByResourceName(itemView, resourceAvatar)?.let { avatar ->
            if (containsView(view, avatar)) {
                return false
            }
        }
        findNicknameView(itemView)?.let { nickname ->
            if (containsView(view, nickname)) {
                return false
            }
        }
        if (view.width < dp(view, 96f) ||
            view.height < dp(view, 52f) ||
            view.width > dp(view, 360f) ||
            view.height > dp(view, 560f)
        ) {
            return false
        }
        if (itemView.width > 0 && view.width >= itemView.width - dp(view, 72f)) {
            return false
        }
        val hasRichStructure = metrics.textCount >= 2 ||
                (metrics.textCount >= 1 && metrics.imageCount >= 1)
        return hasRichStructure || hasRichCardTextMarker(metrics.textSample)
    }

    private fun richCardCandidateScore(view: View, metrics: RichCardMetrics): Int {
        val markerScore = if (hasRichCardTextMarker(metrics.textSample)) 120 else 0
        val backgroundScore = if (view.background != null) 90 else 0
        val clickableScore = if (view.isClickable || view.isLongClickable) 30 else 0
        val structureScore = metrics.textCount * 18 + metrics.imageCount * 12
        val areaScore = ((view.width * view.height) / 8000).coerceIn(0, 160)
        return markerScore + backgroundScore + clickableScore + structureScore + areaScore
    }

    private fun classifyRichCard(text: String): String {
        return when {
            text.contains("微信转账") ||
                    text.contains("转账") ||
                    text.contains("收款") ||
                    text.contains("¥") ||
                    text.contains("￥") -> classifyTransferKind(text)
            ChatBubbleStylePolicy.hasRedpacketCardTextSignal(text) -> "redpacket"
            text.contains("小程序") -> "mini-program"
            text.contains("个人名片") || text.contains("名片") -> "contact-card"
            else -> "rich-card"
        }
    }

    private fun hasRichCardTextMarker(text: String): Boolean {
        if (ChatBubbleStylePolicy.hasRedpacketCardTextSignal(text)) {
            return true
        }
        return listOf(
            "微信转账",
            "转账",
            "小程序",
            "个人名片",
            "名片",
            "公众号",
            "文件",
            "链接"
        ).any { marker ->
            text.contains(marker, ignoreCase = true)
        }
    }

    private fun isTextBearingLeaf(view: View): Boolean {
        if (view is ViewGroup) {
            return false
        }
        return !renderedText(view).isNullOrBlank()
    }

    private fun isImageBearingLeaf(view: View): Boolean {
        if (view is ImageView) {
            return true
        }
        return view.javaClass.name.contains("ImageView")
    }

    private fun isTimeLikeText(text: String): Boolean {
        val value = text.trim()
        return timeTextPattern.matches(value) ||
                value.contains("昨天") ||
                value.contains("星期") ||
                value.contains("周") ||
                value.contains("月") && value.contains("日")
    }

    private fun hasBubbleTarget(itemView: View): Boolean {
        return findBubbleTarget(itemView) != null
    }

    private fun findItemRoot(view: View): View? {
        if (resourceName(view) == resourceItemRoot && hasBubbleTarget(view)) {
            return view
        }
        findViewByResourceName(view, resourceItemRoot)?.let {
            if (hasBubbleTarget(it)) {
                return it
            }
        }
        var current = view.parent as? View
        var depth = 0
        while (current != null && depth < 8) {
            if (resourceName(current) == resourceItemRoot && hasBubbleTarget(current)) {
                return current
            }
            current = current.parent as? View
            depth++
        }
        return null
    }

    private fun findViewByResourceName(root: View, name: String): View? {
        val id = resourceId(root, name)
        if (id == 0) {
            return null
        }
        return root.findViewById(id)
    }

    private fun resourceId(root: View, name: String): Int {
        val context = root.context ?: return 0
        val ids = synchronized(resourceIdCache) {
            resourceIdCache.getOrPut(context) { mutableMapOf() }
        }
        synchronized(ids) {
            ids[name]?.let { return it }
            val id = try {
                root.resources.getIdentifier(name, "id", root.context.packageName)
            } catch (_: Throwable) {
                0
            }
            ids[name] = id
            return id
        }
    }

    private fun hasReadableText(view: View?): Boolean {
        return !renderedText(view).isNullOrBlank()
    }

    private fun renderedText(view: View?): String? {
        return ModernChatBubbleStyler.extractRenderedText(view)
    }

    private fun renderedTextDeep(view: View?, depth: Int = 0): String? {
        view ?: return null
        if (depth > 8 || view.visibility != View.VISIBLE) {
            return null
        }
        val parts = mutableListOf<String>()
        renderedText(view)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isTimeLikeText(it) }
            ?.let { parts.add(it) }
        val group = view as? ViewGroup
        if (group != null) {
            for (index in 0 until group.childCount) {
                renderedTextDeep(group.getChildAt(index), depth + 1)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parts.add(it) }
            }
        }
        return parts
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun hasMiniProgramFooterSignal(root: View): Boolean {
        val footer = findViewByResourceName(root, "bit") ?: return false
        if (!isVisibleDescendant(root, footer)) {
            return false
        }
        return renderedText(footer)?.trim() == "小程序"
    }

    private fun hasAnyResourceName(root: View, vararg names: String): Boolean {
        val wanted = names.toSet()
        return containsAnyResourceName(root, wanted)
    }

    private fun isVisibleDescendant(root: View, child: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current.visibility != View.VISIBLE) {
                return false
            }
            if (current === root) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun containsAnyResourceName(view: View, names: Set<String>): Boolean {
        if (resourceName(view) in names) {
            return true
        }
        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (containsAnyResourceName(group.getChildAt(index), names)) {
                return true
            }
        }
        return false
    }

    private fun probe(view: View, message: String) {
        if (!enableRendererProbe) {
            return
        }
        if (probeKeys.size >= 80 || probeKeys.contains(message)) {
            return
        }
        probeKeys.add(message)
        RuntimeProbe.append(view.context, probeFile, "ModernNativeBubble $message")
    }

    private fun debug(view: View, message: String) {
        ModernChatBubbleStyler.debugBubbleProbe(view.context, "renderer $message")
    }

    private fun debugApply(
        itemView: View,
        messageView: View,
        state: ChatBubbleStylePolicy.RenderState,
        source: String,
        text: String,
        branch: String
    ) {
        val avatarView = findViewByResourceName(itemView, resourceAvatar)
        val avatarContainer = avatarView?.parent as? View
        val nicknameView = findNicknameView(itemView)
        debug(
            itemView,
            "$source $branch key=${state.stableKey.takeLast(10)} side=${state.side} group=${state.position} " +
                    "wantAvatar=${state.showAvatar} avatar=${visibilityName(avatarView)} " +
                    "avatarContainer=${visibilityName(avatarContainer)} avatarH=${avatarContainer?.height}/${avatarView?.height} " +
                    "wantNick=${state.showNickname} nick=${visibilityName(nicknameView)} " +
                    "item=${boundsText(itemView)} msg=${boundsText(messageView)} text=${shortText(text)}"
        )
    }

    private fun probeAvatarApplyState(
        itemView: View,
        target: BubbleTarget?,
        state: ChatBubbleStylePolicy.RenderState,
        source: String,
        text: String?
    ) {
        if (!ModernChatBubbleStyler.isAvatarGroupingProbeEnabled()) {
            return
        }
        val avatarView = findViewByResourceName(itemView, resourceAvatar)
        ModernChatBubbleStyler.probeAvatarApply(
            context = itemView.context,
            source = source,
            state = state,
            targetKind = target?.kind,
            hasTimeBefore = ModernChatBubbleStyler.hasVisibleTimeSeparatorBefore(itemView),
            hasAvatarView = avatarView != null,
            avatarVisibility = visibilityName(avatarView),
            nicknameText = renderedText(findNicknameView(itemView)),
            text = text,
            itemBounds = boundsText(itemView)
        )
    }

    private fun visibilityName(view: View?): String {
        return when (view?.visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            null -> "null"
            else -> view.visibility.toString()
        }
    }

    private fun boundsText(view: View?): String {
        view ?: return "null"
        return "${view.left},${view.top},${view.right},${view.bottom}:${view.width}x${view.height}"
    }

    private fun shortText(text: String): String {
        return text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(24)
    }

    private fun forceTopGravity(view: View?) {
        val group = view as? ViewGroup ?: return
        if (group is LinearLayout) {
            val horizontal = group.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
            group.gravity = horizontal or Gravity.TOP
        }
    }

    private fun clearDirectVerticalMargins(view: View?) {
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            if (params.topMargin == 0 && params.bottomMargin == 0) {
                continue
            }
            params.topMargin = 0
            params.bottomMargin = 0
            child.layoutParams = params
        }
    }

    private fun clearNestedVerticalMargins(root: ViewGroup, messageView: View, depth: Int) {
        if (depth > 3) {
            return
        }
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (!containsView(child, messageView) && renderedText(child).isNullOrBlank()) {
                continue
            }
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && (params.topMargin != 0 || params.bottomMargin != 0)) {
                params.topMargin = 0
                params.bottomMargin = 0
                child.layoutParams = params
            }
            (child as? ViewGroup)?.let {
                clearNestedVerticalMargins(it, messageView, depth + 1)
            }
        }
    }

    private fun directChildContaining(parent: ViewGroup, target: View): View? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (containsView(child, target)) {
                return child
            }
        }
        return null
    }

    private fun containsView(root: View, target: View): Boolean {
        if (root === target) {
            return true
        }
        val group = root as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (containsView(group.getChildAt(index), target)) {
                return true
            }
        }
        return false
    }

    private fun adjacentVisibleItem(itemView: View, step: Int, requireMessage: Boolean): View? {
        val parent = findRecyclerParent(itemView) ?: itemView.parent as? ViewGroup ?: return null
        val currentTop = screenTop(itemView)
        var bestChild: View? = null
        var bestTop = if (step > 0) Int.MAX_VALUE else Int.MIN_VALUE
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val candidate = findItemRoot(child) ?: child
            if (candidate === itemView || resourceName(candidate) != resourceItemRoot) {
                continue
            }
            if (requireMessage && !hasBubbleTarget(candidate)) {
                continue
            }
            val childTop = screenTop(candidate)
            if (step > 0) {
                if (childTop > currentTop && childTop < bestTop) {
                    bestTop = childTop
                    bestChild = candidate
                }
            } else if (step < 0) {
                if (childTop < currentTop && childTop > bestTop) {
                    bestTop = childTop
                    bestChild = candidate
                }
            }
        }
        return bestChild
    }

    private fun findRecyclerParent(view: View): ViewGroup? {
        var current = view.parent
        var depth = 0
        while (current is ViewGroup && depth < 12) {
            if (isRecyclerViewLike(current)) {
                return current
            }
            current = current.parent
            depth++
        }
        return null
    }

    private fun isRecyclerViewLike(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("RecyclerView") || name.contains("WxRecyclerView")
    }

    private fun screenTop(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[1]
    }

    private fun shouldDeferCompactTopEdgeTextItem(itemView: View, target: BubbleTarget): Boolean {
        if (target.kind != "text") {
            return false
        }
        val recycler = findRecyclerParent(itemView) ?: return false
        val recyclerTop = screenTop(recycler)
        val itemTop = screenTop(itemView)
        val itemBottom = itemTop + itemView.height
        val tolerance = dp(itemView, 1f)
        val clippedThroughTop = itemTop < recyclerTop - tolerance &&
                itemBottom > recyclerTop + tolerance
        val compactAtTopEdge = itemTop <= recyclerTop + tolerance &&
                itemView.height in 1..dp(itemView, 72f)
        return clippedThroughTop || compactAtTopEdge
    }

    private fun isTimeSeparatorView(view: View): Boolean {
        if (view.visibility != View.VISIBLE) {
            return false
        }
        val text = renderedText(view)?.trim() ?: return false
        if (text.isBlank()) {
            return false
        }
        return timeTextPattern.matches(text) ||
                text.contains("昨天") ||
                text.contains("星期") ||
                text.contains("周") ||
                text.contains("月") && text.contains("日")
    }

    private fun setTopMargin(view: View, margin: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin == margin) {
            return
        }
        params.topMargin = margin
        view.layoutParams = params
    }

    private fun disableAncestorClipping(view: View) {
        var parent = view.parent
        var depth = 0
        while (parent is ViewGroup && depth < 6) {
            parent.clipChildren = false
            parent.clipToPadding = false
            parent = parent.parent
            depth++
        }
    }

    private fun resourceName(view: View): String? {
        if (view.id == View.NO_ID) {
            return null
        }
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun dp(view: View, value: Float): Int {
        return (value * view.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun syncReusableState(
        itemView: View,
        target: BubbleTarget,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        syncMessageParentTopMargin(itemView, target.layoutView, state)
        syncAvatarAndNicknameState(itemView, target, state)
    }

    private fun scheduleRowChromeSyncIfNeeded(
        itemView: View,
        target: BubbleTarget,
        state: ChatBubbleStylePolicy.RenderState,
        source: String
    ) {
        if (target.kind == "text" || (!state.showAvatar && !state.showNickname)) {
            return
        }
        val currentBubble = target.bubbleView.background as? ModernBubbleDrawable ?: return
        if (currentBubble.stableKey != state.stableKey) {
            return
        }
        val pendingKey = XposedHelpers.getAdditionalInstanceField(itemView, keyPendingRowChromeSync) as? String
        if (pendingKey == state.stableKey) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(itemView, keyPendingRowChromeSync, state.stableKey)
        rowChromeSyncDelaysMs.forEachIndexed { index, delayMs ->
            itemView.postDelayed({
                val delayedBubble = target.bubbleView.background as? ModernBubbleDrawable
                if (delayedBubble?.stableKey == state.stableKey) {
                    val delayedState = stateForCurrentBubble(state, delayedBubble)
                    syncReusableState(itemView, target, delayedState)
                    probeAvatarApplyState(
                        itemView = itemView,
                        target = target,
                        state = delayedState,
                        source = "$source.rowChrome$index",
                        text = null
                    )
                } else {
                    probeAvatarApplyState(
                        itemView = itemView,
                        target = null,
                        state = state,
                        source = "$source.rowChrome$index.miss",
                        text = null
                    )
                }
                if (index == rowChromeSyncDelaysMs.lastIndex &&
                    XposedHelpers.getAdditionalInstanceField(itemView, keyPendingRowChromeSync) == state.stableKey
                ) {
                    XposedHelpers.removeAdditionalInstanceField(itemView, keyPendingRowChromeSync)
                }
            }, delayMs)
        }
    }

    private fun stateForCurrentBubble(
        state: ChatBubbleStylePolicy.RenderState,
        drawable: ModernBubbleDrawable
    ): ChatBubbleStylePolicy.RenderState {
        if (drawable.side == state.side && drawable.position == state.position) {
            return state
        }
        val side = drawable.side
        val position = drawable.position
        return state.copy(
            side = side,
            position = position,
            showAvatar = ChatBubbleStylePolicy.showAvatar(position),
            showNickname = ChatBubbleStylePolicy.showNickname(side, position),
            topMarginDp = ChatBubbleStylePolicy.topMarginDp(position),
            cornerRadii = ChatBubbleStylePolicy.cornerRadii(side, position)
        )
    }

    private fun syncMessageParentTopMargin(
        itemView: View,
        messageView: View,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        val messageParent = messageView.parent as? View ?: messageView
        val nicknameView = findNicknameView(itemView)
        val hasVisibleNickname = state.showNickname && hasReadableText(nicknameView)
        val bubbleTopMargin = ChatBubbleStylePolicy.bubbleTopMarginDp(
            state.position,
            hasVisibleNickname = hasVisibleNickname
        )
        setTopMargin(messageParent, dp(messageView, bubbleTopMargin))
    }

    private fun setAvatarRowGravity(container: View, visible: Boolean) {
        val params = container.layoutParams as? LinearLayout.LayoutParams ?: return
        val vertical = if (visible) Gravity.BOTTOM else Gravity.TOP
        val horizontal = if (params.gravity >= 0) {
            params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        } else {
            0
        }
        val gravity = horizontal or vertical
        if (params.gravity == gravity) {
            return
        }
        params.gravity = gravity
        container.layoutParams = params
    }

    private fun resetAvatarPlacement(avatarView: View?) {
        avatarView ?: return
        val container = avatarView.parent as? View ?: avatarView
        if (container.translationY != 0f) {
            container.translationY = 0f
        }
        val params = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin == 0 && params.bottomMargin == 0) {
            return
        }
        params.topMargin = 0
        params.bottomMargin = 0
        container.layoutParams = params
    }

    private fun alignVisibleAvatarToBubbleBottom(
        itemView: View,
        bubbleView: View,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        if (!state.showAvatar) {
            return
        }
        alignVisibleAvatarToBubbleBottomNow(itemView, bubbleView)
    }

    private fun alignVisibleAvatarToBubbleBottomNow(itemView: View, bubbleView: View) {
        val avatarView = findViewByResourceName(itemView, resourceAvatar) ?: return
        val container = avatarView.parent as? View ?: avatarView
        if (container.visibility != View.VISIBLE || avatarView.visibility != View.VISIBLE) {
            return
        }
        val parent = container.parent as? ViewGroup ?: return
        if (!containsView(parent, bubbleView)) {
            return
        }
        val bubbleBottom = bottomRelativeTo(parent, bubbleView)
        val avatarHeight = maxOf(container.height, avatarView.height, dp(avatarView, 45f))
        if (bubbleBottom <= 0 || avatarHeight <= 0) {
            return
        }
        val desiredTop = (bubbleBottom - avatarHeight).coerceAtLeast(0)
        val currentTop = topRelativeTo(parent, container)
        val translation = (desiredTop - currentTop).toFloat()
        if (container.translationY == translation) {
            return
        }
        container.translationY = translation
    }

    private fun topRelativeTo(ancestor: View, child: View?): Int {
        child ?: return 0
        var current: View? = child
        var top = 0
        var depth = 0
        while (current != null && current !== ancestor && depth < 12) {
            top += current.top
            current = current.parent as? View
            depth++
        }
        return if (current === ancestor) top else 0
    }

    private fun bottomRelativeTo(ancestor: View, child: View?): Int {
        child ?: return 0
        val height = child.height.takeIf { it > 0 } ?: child.measuredHeight
        if (height <= 0 || child.visibility == View.GONE) {
            return 0
        }
        return topRelativeTo(ancestor, child) + height
    }

    private fun renderSignature(
        state: ChatBubbleStylePolicy.RenderState,
        text: String,
        palette: ChatBubbleStylePolicy.BubblePalette
    ): String {
        return "${state.stableKey}:${state.side}:${state.position}:${text.hashCode()}:${palette.signature}"
    }

    private fun paletteForTarget(
        target: BubbleTarget,
        state: ChatBubbleStylePolicy.RenderState
    ): ChatBubbleStylePolicy.BubblePalette {
        val base = ModernChatBubbleColors.palette(state.side)
        return when (target.kind) {
            "transfer" -> base.copy(
                bubbleColor = 0xFFF39B3B.toInt(),
                pressedBubbleColor = scaleRgb(0xFFF39B3B.toInt(), 0.95f),
                textColor = 0xFFFFFFFF.toInt(),
                semanticTextColor = 0xFFFFF2DE.toInt(),
                quoteFillColor = 0x26FFFFFF,
                quoteTextColor = 0xE6FFFFFF.toInt(),
                strokeColor = 0x00FFFFFF,
                strokeWidthDp = 0f,
                useGradient = false
            )
            "transfer-received" -> base.copy(
                bubbleColor = 0xFFFBE3C5.toInt(),
                pressedBubbleColor = scaleRgb(0xFFFBE3C5.toInt(), 0.97f),
                textColor = 0xFFE0852A.toInt(),
                semanticTextColor = 0xFFE0852A.toInt(),
                quoteFillColor = 0x33F39B3B,
                quoteTextColor = 0xFFE0852A.toInt(),
                strokeColor = 0x00FFFFFF,
                strokeWidthDp = 0f,
                useGradient = false
            )
            "redpacket" -> base.copy(
                bubbleColor = 0xFFE86D36.toInt(),
                pressedBubbleColor = scaleRgb(0xFFE86D36.toInt(), 0.95f),
                textColor = 0xFFFFFFFF.toInt(),
                semanticTextColor = 0xFFFFEBDD.toInt(),
                quoteFillColor = 0x26FFFFFF,
                quoteTextColor = 0xE6FFFFFF.toInt(),
                strokeColor = 0x33FFFFFF,
                strokeWidthDp = 1f
            )
            "contact-card",
            "position",
            "mini-program",
            "rich-card" -> ChatBubbleStylePolicy.cardPalette()
            "image",
            "video" -> ChatBubbleStylePolicy.imagePalette()
            else -> base
        }
    }

    private fun applyContentClipIfNeeded(view: View, target: BubbleTarget) {
        if (!target.clipToOutline || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        view.clipToOutline = true
    }

    private fun scaleRgb(color: Int, factor: Float): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun isCurrentRender(
        itemView: View,
        messageView: View,
        state: ChatBubbleStylePolicy.RenderState,
        signature: String
    ): Boolean {
        return XposedHelpers.getAdditionalInstanceField(itemView, keyAppliedSignature) == signature &&
                messageView.background is ModernBubbleDrawable &&
                isNicknameVisibilityCurrent(itemView, state) &&
                isAvatarVisibilityCurrent(itemView, state)
    }

    private fun isNicknameVisibilityCurrent(
        itemView: View,
        state: ChatBubbleStylePolicy.RenderState
    ): Boolean {
        val nicknameView = findNicknameView(itemView) ?: return true
        val shouldShow = state.showNickname && hasReadableText(nicknameView)
        return if (shouldShow) {
            nicknameView.visibility == View.VISIBLE
        } else {
            nicknameView.visibility == View.GONE
        }
    }

    private fun isAvatarVisibilityCurrent(
        itemView: View,
        state: ChatBubbleStylePolicy.RenderState
    ): Boolean {
        val avatarView = findViewByResourceName(itemView, resourceAvatar) ?: return true
        val container = avatarView.parent as? View ?: avatarView
        return if (state.showAvatar) {
            container.visibility == View.VISIBLE && avatarView.visibility == View.VISIBLE &&
                    (container.layoutParams?.height ?: 1) != 0
        } else {
            container.visibility == View.INVISIBLE && avatarView.visibility == View.INVISIBLE &&
                    (container.layoutParams?.height ?: 0) == 0
        }
    }

    private class TransferIconDrawable(
        private val received: Boolean
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var drawableAlpha = 255

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val size = minOf(bounds.width(), bounds.height()).toFloat()
            if (size <= 0f) {
                return
            }
            val cx = bounds.left + bounds.width() / 2f
            val cy = bounds.top + bounds.height() / 2f
            val radius = size / 2f
            val fillColor = if (received) 0xFFF9D5A6.toInt() else 0xFFF5AF62.toInt()
            val markColor = if (received) 0xFFE0852A.toInt() else 0xFFFFFFFF.toInt()

            paint.style = Paint.Style.FILL
            paint.color = withAlpha(fillColor)
            canvas.drawCircle(cx, cy, radius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = size * 0.075f
            paint.color = withAlpha(markColor)

            if (received) {
                drawCheck(canvas, cx, cy, size)
            } else {
                drawTransferArrows(canvas, cx, cy, size)
            }
        }

        private fun drawTransferArrows(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            val head = size * 0.11f
            val topY = cy - size * 0.10f
            val topStart = cx - size * 0.20f
            val topEnd = cx + size * 0.16f
            canvas.drawLine(topStart, topY, topEnd, topY, paint)
            canvas.drawLine(topEnd, topY, topEnd - head, topY - head, paint)
            canvas.drawLine(topEnd, topY, topEnd - head, topY + head, paint)

            val bottomY = cy + size * 0.12f
            val bottomStart = cx + size * 0.20f
            val bottomEnd = cx - size * 0.16f
            canvas.drawLine(bottomStart, bottomY, bottomEnd, bottomY, paint)
            canvas.drawLine(bottomEnd, bottomY, bottomEnd + head, bottomY - head, paint)
            canvas.drawLine(bottomEnd, bottomY, bottomEnd + head, bottomY + head, paint)
        }

        private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            val path = Path().apply {
                moveTo(cx - size * 0.24f, cy + size * 0.02f)
                lineTo(cx - size * 0.07f, cy + size * 0.20f)
                lineTo(cx + size * 0.27f, cy - size * 0.17f)
            }
            canvas.drawPath(path, paint)
        }

        private fun withAlpha(color: Int): Int {
            return Color.argb(
                Color.alpha(color) * drawableAlpha / 255,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = 100

        override fun getIntrinsicHeight(): Int = 100
    }
}
