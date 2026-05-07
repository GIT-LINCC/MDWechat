package com.blanke.mdwechat.hookers

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import com.blanke.mdwechat.util.ChatBubbleStylePolicy
import com.blanke.mdwechat.util.RuntimeProbe
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
        val clearViews: List<View> = emptyList()
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
        val palette = ModernChatBubbleColors.palette(renderState.side)
        val signature = renderSignature(renderState, text, palette)
        if (isCurrentRender(itemView, bubbleView, renderState, signature)) {
            syncReusableState(itemView, target, renderState)
            debugApply(itemView, bubbleView, renderState, source, text, "current")
            scheduleBoundaryRefresh(itemView, state, renderState, source)
            return true
        }
        clearOriginalBubbleContainers(bubbleView)
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
            override fun getOutline(target: View, outline: Outline) {
                if (target.width <= 0 || target.height <= 0) {
                    return
                }
                val radius = if (state.position == ChatBubbleStylePolicy.GroupPosition.SINGLE) 24f else 20f
                outline.setRoundRect(0, 0, target.width, target.height, dp(target, radius).toFloat())
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
        findCallBubbleTarget(itemView)?.let { return it }
        findVoiceBubbleTarget(itemView)?.let { return it }
        val messageView = findViewByResourceName(itemView, resourceMessage) ?: return null
        return BubbleTarget(
            bubbleView = messageView,
            textView = messageView,
            kind = "text",
            applyTextPadding = true
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
        if (textView == null && renderedText(callBubble).isNullOrBlank()) {
            return null
        }
        return BubbleTarget(
            bubbleView = callBubble,
            textView = textView,
            kind = "call",
            applyTextPadding = false
        )
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
