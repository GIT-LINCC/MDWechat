package com.blanke.mdwechat.hookers

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
    private const val keyBoundaryRefreshSignature = "mdwechat_native_bubble_boundary_refresh_signature"
    private const val keyPendingAppendRefresh = "mdwechat_native_bubble_pending_append_refresh"
    private const val probeFile = "native_bubble_renderer.txt"
    private const val enableRendererProbe = false
    private const val enableGenericRichCardHeuristic = false
    private val appendRefreshDelaysMs = longArrayOf(80L, 220L, 520L, 900L)
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
        val state = ModernChatBubbleStyler.resolveRenderStateFromAdapter(adapter, position)
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
        ModernChatBubbleStyler.rememberBoundRenderState(adapter, position, itemView, state)
        val applied = applyState(itemView, state, "adapter:${adapter.javaClass.simpleName}:$position")
        if (applied) {
            refreshPreviousItemAcrossTimeSeparator(adapter, position, itemView)
            refreshVisibleNeighborItemsIfNeeded(adapter, position, findItemRoot(itemView) ?: itemView, state)
        }
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
        if (applied && context.adapter != null && context.position >= 0) {
            val itemView = findItemRoot(boundView) ?: boundView
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
            probe(boundView, "$source.noBubble:${boundView.javaClass.name}:${resourceName(boundView)}")
            debug(boundView, "$source noBubbleTarget item=${resourceName(itemView)}")
            return false
        }
        val bubbleView = target.bubbleView
        val text = renderTextForSignature(target)
        if (text.isNullOrBlank()) {
            probe(bubbleView, "$source.noText:${bubbleView.javaClass.name}:${resourceName(bubbleView)}:${target.kind}")
            debug(itemView, "$source noText target=${resourceName(bubbleView)} kind=${target.kind}")
            return false
        }
        val renderState = applyVisibleBoundaries(itemView, state)
        val palette = paletteForTarget(target, renderState)
        val signature = renderSignature(renderState, text, palette)
        if (isCurrentRender(itemView, bubbleView, renderState, signature)) {
            syncReusableState(itemView, target, renderState)
            debugApply(itemView, bubbleView, renderState, source, text, "current")
            scheduleBoundaryRefresh(itemView, state, renderState, source)
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
        applyShadow(bubbleView, renderState)
        applyContentClipIfNeeded(bubbleView, target)
        disableAncestorClipping(bubbleView)

        val layoutView = target.layoutView
        val marginTarget = layoutView.parent as? View ?: layoutView
        setTopMargin(marginTarget, dp(bubbleView, renderState.topMarginDp))
        normalizeMessageColumn(itemView, layoutView, renderState)
        styleQuoteBlock(itemView, layoutView, renderState, palette)
        setAvatarVisibility(findViewByResourceName(itemView, resourceAvatar), renderState.showAvatar)
        setNicknameVisibility(findNicknameView(itemView), renderState.showNickname)
        normalizeRow(itemView, layoutView, renderState)
        XposedHelpers.setAdditionalInstanceField(itemView, keyAppliedSignature, signature)
        probe(bubbleView, "$source.applied:${renderState.side}:${renderState.position}:${target.kind}:${text.take(16)}")
        debugApply(itemView, bubbleView, renderState, source, text, "applied")
        scheduleBoundaryRefresh(itemView, state, renderState, source)
        return true
    }

    private fun refreshVisibleNeighborItemsIfNeeded(
        adapter: Any,
        position: Int,
        itemView: View,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        if (state.side != ChatBubbleStylePolicy.Side.RIGHT ||
            state.position == ChatBubbleStylePolicy.GroupPosition.SINGLE
        ) {
            return
        }
        refreshVisibleNeighborItem(
            adapter = adapter,
            position = position - 1,
            itemView = adjacentVisibleItem(itemView, step = -1, requireMessage = true),
            source = "prev"
        )
        refreshVisibleNeighborItem(
            adapter = adapter,
            position = position + 1,
            itemView = adjacentVisibleItem(itemView, step = 1, requireMessage = true),
            source = "next"
        )
    }

    private fun refreshVisibleNeighborItem(
        adapter: Any,
        position: Int,
        itemView: View?,
        source: String
    ) {
        if (position < 0 || itemView == null) {
            return
        }
        val state = ModernChatBubbleStyler.resolveRenderStateFromAdapter(adapter, position) ?: return
        applyState(itemView, state, "adapter-$source:${adapter.javaClass.simpleName}:$position")
    }

    private fun scheduleRightAppendRefresh(
        boundView: View,
        holder: Any?,
        msgInfo: Any,
        initialContext: ModernChatBubbleStyler.RenderContext
    ) {
        if (initialContext.adapter != null &&
            initialContext.position >= 0 &&
            initialContext.state.position != ChatBubbleStylePolicy.GroupPosition.SINGLE
        ) {
            return
        }
        val itemView = findItemRoot(boundView) ?: boundView
        if (XposedHelpers.getAdditionalInstanceField(itemView, keyPendingAppendRefresh) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(itemView, keyPendingAppendRefresh, true)
        scheduleRightAppendRefreshAttempt(itemView, holder, msgInfo, attempt = 0)
    }

    private fun scheduleRightAppendRefreshAttempt(
        itemView: View,
        holder: Any?,
        msgInfo: Any,
        attempt: Int
    ) {
        val delay = appendRefreshDelaysMs.getOrNull(attempt)
        if (delay == null) {
            XposedHelpers.removeAdditionalInstanceField(itemView, keyPendingAppendRefresh)
            return
        }
        itemView.postDelayed({
            val root = findItemRoot(itemView) ?: itemView
            val context = ModernChatBubbleStyler.resolveRenderContextFromChattingItemBind(root, holder, msgInfo)
            val shouldRetry = if (context != null && context.state.side == ChatBubbleStylePolicy.Side.RIGHT) {
                val applied = applyState(root, context.state, "chatItem-delayed:${attempt + 1}")
                if (applied && context.adapter != null && context.position >= 0) {
                    refreshVisibleNeighborItemsIfNeeded(context.adapter, context.position, root, context.state)
                }
                context.adapter == null ||
                        context.position < 0 ||
                        context.state.position == ChatBubbleStylePolicy.GroupPosition.SINGLE
            } else {
                true
            }
            if (shouldRetry && attempt + 1 < appendRefreshDelaysMs.size) {
                scheduleRightAppendRefreshAttempt(root, holder, msgInfo, attempt + 1)
            } else {
                XposedHelpers.removeAdditionalInstanceField(root, keyPendingAppendRefresh)
                if (root !== itemView) {
                    XposedHelpers.removeAdditionalInstanceField(itemView, keyPendingAppendRefresh)
                }
            }
        }, delay)
    }

    private fun applyVisibleBoundaries(
        itemView: View,
        state: ChatBubbleStylePolicy.RenderState
    ): ChatBubbleStylePolicy.RenderState {
        val splitBefore = hasVisibleTimeSeparator(itemView)
        val splitAfter = nextVisibleItem(itemView)?.let { hasVisibleTimeSeparator(it) } == true
        if (!splitBefore && !splitAfter) {
            return state
        }
        val hasPrevious = !splitBefore && when (state.position) {
            ChatBubbleStylePolicy.GroupPosition.MIDDLE,
            ChatBubbleStylePolicy.GroupPosition.BOTTOM -> true
            ChatBubbleStylePolicy.GroupPosition.TOP,
            ChatBubbleStylePolicy.GroupPosition.SINGLE -> false
        }
        val hasNext = !splitAfter && when (state.position) {
            ChatBubbleStylePolicy.GroupPosition.TOP,
            ChatBubbleStylePolicy.GroupPosition.MIDDLE -> true
            ChatBubbleStylePolicy.GroupPosition.BOTTOM,
            ChatBubbleStylePolicy.GroupPosition.SINGLE -> false
        }
        val position = ChatBubbleStylePolicy.groupPosition(hasPrevious = hasPrevious, hasNext = hasNext)
        if (position == state.position) {
            return state
        }
        return state.copy(
            position = position,
            showAvatar = ChatBubbleStylePolicy.showAvatar(position),
            showNickname = ChatBubbleStylePolicy.showNickname(position),
            topMarginDp = ChatBubbleStylePolicy.topMarginDp(position),
            cornerRadii = ChatBubbleStylePolicy.cornerRadii(state.side, position)
        )
    }

    private fun scheduleBoundaryRefresh(
        itemView: View,
        state: ChatBubbleStylePolicy.RenderState,
        appliedState: ChatBubbleStylePolicy.RenderState,
        source: String
    ) {
        if (state.position == ChatBubbleStylePolicy.GroupPosition.SINGLE) {
            return
        }
        val refreshSignature = "${state.stableKey}:${state.position}:${appliedState.position}"
        if (XposedHelpers.getAdditionalInstanceField(itemView, keyBoundaryRefreshSignature) == refreshSignature) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(itemView, keyBoundaryRefreshSignature, refreshSignature)
        itemView.post {
            val latestState = applyVisibleBoundaries(itemView, state)
            if (latestState.position != appliedState.position) {
                applyState(itemView, state, "$source.post")
            }
        }
    }

    private fun refreshPreviousItemAcrossTimeSeparator(adapter: Any, position: Int, itemView: View) {
        if (!hasVisibleTimeSeparator(itemView) || position <= 0) {
            return
        }
        val previousItem = previousVisibleMessageItem(itemView) ?: return
        val previousState = ModernChatBubbleStyler.resolveRenderStateFromAdapter(adapter, position - 1) ?: return
        applyState(previousItem, previousState, "adapter-neighbor:${adapter.javaClass.simpleName}:${position - 1}")
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
        messageView: View,
        state: ChatBubbleStylePolicy.RenderState,
        palette: ChatBubbleStylePolicy.BubblePalette
    ) {
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
        view.elevation = dp(view, 4.5f).toFloat()
        view.translationZ = dp(view, 0.5f).toFloat()
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
            val shadowColor = Color.argb(32, 0, 0, 0)
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
        if (params != null && XposedHelpers.getAdditionalInstanceField(container, keyOriginalAvatarHeight) == null) {
            XposedHelpers.setAdditionalInstanceField(container, keyOriginalAvatarHeight, maxOf(params.height, params.width))
        }
        setAvatarRowGravity(container, visible)
        if (visible) {
            val originalHeight = XposedHelpers.getAdditionalInstanceField(container, keyOriginalAvatarHeight) as? Int
            if (params != null && originalHeight != null && params.height != originalHeight) {
                params.height = originalHeight
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
        val messageView = findViewByResourceName(itemView, resourceMessage)
        if (messageView != null) {
            return BubbleTarget(
                bubbleView = messageView,
                textView = messageView,
                kind = "text",
                applyTextPadding = true
            )
        }
        findImageBubbleTargetByStructure(itemView)?.let { return it }
        return if (enableGenericRichCardHeuristic) {
            findRichCardBubbleTarget(itemView)
        } else {
            null
        }
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
            val text = renderedTextDeep(target)
                ?: target.contentDescription?.toString()
                ?: kind
            return BubbleTarget(
                bubbleView = target,
                textView = null,
                kind = classifyKind(kind, text),
                applyTextPadding = false,
                layoutView = target,
                clearViews = clearViews,
                signatureText = text.ifBlank { kind },
                clipToOutline = clipToOutline
            )
        }

        private fun classifyKind(fallback: String, text: String): String {
            return when {
                text.contains("微信转账") ||
                        text.contains("转账") ||
                        text.contains("收款") ||
                        text.contains("¥") ||
                        text.contains("￥") -> classifyTransferKind(text)
                text.contains("微信红包") || text.contains("红包") -> "redpacket"
                text.contains("小程序") -> "mini-program"
                text.contains("个人名片") || text.contains("名片") -> "contact-card"
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
        val hasMiniProgramSignals = hasAnyResourceName(
            target,
            "biq",
            "biu",
            "big",
            "bit",
            "bif",
            "bko"
        )
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
            !hasMiniProgramSignals &&
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
                hasMiniProgramSignals = hasMiniProgramSignals,
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
        hasMiniProgramSignals: Boolean,
        hasWebShareSignals: Boolean
    ): String {
        return when {
            hasTransferSignals -> classifyTransferKind(text)
            text.contains("微信红包") || text.contains("红包") -> "redpacket"
            hasMiniProgramSignals || text.contains("小程序") -> "mini-program"
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
            text.contains("微信红包") || text.contains("红包") -> "redpacket"
            text.contains("小程序") -> "mini-program"
            text.contains("个人名片") || text.contains("名片") -> "contact-card"
            else -> "rich-card"
        }
    }

    private fun hasRichCardTextMarker(text: String): Boolean {
        return listOf(
            "微信转账",
            "转账",
            "微信红包",
            "红包",
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

    private fun hasAnyResourceName(root: View, vararg names: String): Boolean {
        val wanted = names.toSet()
        return containsAnyResourceName(root, wanted)
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

    private fun previousVisibleMessageItem(itemView: View): View? {
        return adjacentVisibleItem(itemView, step = -1, requireMessage = true)
    }

    private fun nextVisibleItem(itemView: View): View? {
        return adjacentVisibleItem(itemView, step = 1, requireMessage = false)
    }

    private fun adjacentVisibleItem(itemView: View, step: Int, requireMessage: Boolean): View? {
        val parent = itemView.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(itemView)
        if (index < 0) {
            return null
        }
        var cursor = index + step
        while (cursor in 0 until parent.childCount) {
            val child = parent.getChildAt(cursor)
            if (resourceName(child) == resourceItemRoot &&
                (!requireMessage || hasBubbleTarget(child))
            ) {
                return child
            }
            cursor += step
        }
        return null
    }

    private fun hasVisibleTimeSeparator(itemView: View): Boolean {
        val view = findViewByResourceName(itemView, resourceNickname) ?: return false
        return isTimeSeparatorView(view)
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
        setAvatarVisibility(findViewByResourceName(itemView, resourceAvatar), state.showAvatar)
        setNicknameVisibility(findNicknameView(itemView), state.showNickname)
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
                strokeColor = ChatBubbleStylePolicy.TRANSPARENT_COLOR,
                strokeWidthDp = 0f
            )
            "transfer-received" -> base.copy(
                bubbleColor = 0xFFFBE3C5.toInt(),
                pressedBubbleColor = scaleRgb(0xFFFBE3C5.toInt(), 0.97f),
                textColor = 0xFFE0852A.toInt(),
                semanticTextColor = 0xFFE0852A.toInt(),
                quoteFillColor = 0x33F39B3B,
                quoteTextColor = 0xFFE0852A.toInt(),
                strokeColor = ChatBubbleStylePolicy.TRANSPARENT_COLOR,
                strokeWidthDp = 0f
            )
            "redpacket" -> base.copy(
                bubbleColor = 0xFFE86D36.toInt(),
                pressedBubbleColor = scaleRgb(0xFFE86D36.toInt(), 0.95f),
                textColor = 0xFFFFFFFF.toInt(),
                semanticTextColor = 0xFFFFEBDD.toInt(),
                quoteFillColor = 0x26FFFFFF,
                quoteTextColor = 0xE6FFFFFF.toInt(),
                strokeColor = ChatBubbleStylePolicy.TRANSPARENT_COLOR,
                strokeWidthDp = 0f
            )
            "contact-card",
            "position",
            "mini-program",
            "rich-card" -> ChatBubbleStylePolicy.cardPalette()
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
}
