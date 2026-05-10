package com.blanke.mdwechat.hookers

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.util.ChatBubbleStylePolicy
import com.blanke.mdwechat.util.ChatBubbleStylePolicy.GroupPosition
import com.blanke.mdwechat.util.ChatBubbleStylePolicy.MessageCandidate
import com.blanke.mdwechat.util.ChatBubbleStylePolicy.Side
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.NightModeUtils
import com.blanke.mdwechat.util.RuntimeProbe
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap

object ModernChatBubbleStyler {
    private const val resourceItemRoot = "bn1"
    private const val resourceMessage = "bkl"
    private const val resourceAvatar = "bk1"
    private const val resourceNickname = "br1"
    private const val resourceNicknameModern = "brc"
    private const val resourceTimeLabel = "br1"

    private const val keyReplacingBackground = "mdwechat_modern_chat_bubble_replacing"
    private const val keyCandidate = "mdwechat_modern_chat_bubble_candidate"
    private const val keySender = "mdwechat_modern_chat_bubble_sender"
    private const val keyItemSide = "mdwechat_modern_chat_bubble_item_side"
    private const val keyLastSide = "mdwechat_modern_chat_bubble_last_side"
    private const val keyLastPosition = "mdwechat_modern_chat_bubble_last_position"
    private const val keyLastTextHash = "mdwechat_modern_chat_bubble_last_text_hash"
    private const val keyOriginalAvatarHeight = "mdwechat_modern_chat_bubble_avatar_original_height"
    private const val keyPendingRowCompact = "mdwechat_modern_chat_bubble_pending_row_compact"
    private const val keyPendingDecision = "mdwechat_modern_chat_bubble_pending_decision"
    private const val keyPendingLegacyAppendRefresh = "mdwechat_modern_chat_bubble_legacy_append_refresh"
    private const val keyPendingVisibleClusterRefresh = "mdwechat_modern_chat_bubble_visible_cluster_refresh"
    private const val keyBoundAdapterClass = "mdwechat_modern_chat_bubble_bound_adapter_class"
    private const val keyBoundAdapterPosition = "mdwechat_modern_chat_bubble_bound_adapter_position"
    private const val keyBoundStableKey = "mdwechat_modern_chat_bubble_bound_stable_key"
    private const val timeSeparatorGapMs = 5 * 60 * 1000L
    private const val adapterWindowPadding = 16
    private const val maxVisibleClusterItems = 8
    private const val bubbleProbeFile = "chat_bubble_probe.txt"
    private const val debugProbeFile = "chat_bubble_debug.txt"
    private const val enableVerboseBubbleProbe = false
    private const val enableDebugBubbleProbe = false
    private val legacyAppendRefreshDelaysMs = longArrayOf(80L, 220L, 520L, 900L)

    private val shadowColor = Color.argb(32, 0, 0, 0)
    private val diagnosticLogs = mutableSetOf<String>()
    private val adapterProbeLogs = mutableSetOf<String>()
    private val bindProbeLogs = mutableSetOf<String>()
    private val chatItemProbeLogs = mutableSetOf<String>()
    private val styleProbeLogs = mutableSetOf<String>()
    private val layoutProbeLogs = mutableSetOf<String>()
    private val debugProbeLogs = mutableSetOf<String>()
    private val messageViewIdCache = Collections.synchronizedMap(WeakHashMap<Context, Int>())
    private val bubbleDecisionCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, BubbleDecision>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BubbleDecision>?): Boolean {
                return size > 1600
            }
        }
    )
    data class RenderContext(
        val state: ChatBubbleStylePolicy.RenderState,
        val adapter: Any?,
        val position: Int
    )

    data class VisibleRenderState(
        val itemView: View,
        val state: ChatBubbleStylePolicy.RenderState
    )

    val isEnabled: Boolean
        get() = HookConfig.is_hook_chat_settings &&
                ((!NightModeUtils.isNightMode()) || HookConfig.is_hook_bubble_in_night_mode)

    fun isReplacingBackground(view: View): Boolean {
        return XposedHelpers.getAdditionalInstanceField(view, keyReplacingBackground) == true
    }

    fun isKnownMessageView(view: View): Boolean {
        if (XposedHelpers.getAdditionalInstanceField(view, keyCandidate) != true) {
            return false
        }
        val lastTextHash = XposedHelpers.getAdditionalInstanceField(view, keyLastTextHash) as? Int
            ?: return false
        return lastTextHash == textHash(view)
    }

    fun createLastBubbleDrawable(view: View): Drawable? {
        if (!isKnownMessageView(view)) {
            return null
        }
        val side = XposedHelpers.getAdditionalInstanceField(view, keyLastSide) as? Side ?: return null
        val position = XposedHelpers.getAdditionalInstanceField(view, keyLastPosition) as? GroupPosition
            ?: GroupPosition.SINGLE
        return createBubbleDrawable(view, side, position, visualStableKey(view, null))
    }

    fun restoreLastBubbleBackground(view: View) {
        val drawable = createLastBubbleDrawable(view) ?: return
        XposedHelpers.setAdditionalInstanceField(view, keyReplacingBackground, true)
        try {
            view.background = drawable
        } finally {
            XposedHelpers.removeAdditionalInstanceField(view, keyReplacingBackground)
        }
    }

    fun canHandleBubbleBackground(view: View): Boolean {
        return isMessageTextView(view) || isKnownMessageView(view)
    }

    fun createPendingBubbleDrawable(view: View): Drawable? {
        if (!isMessageTextView(view)) {
            return null
        }
        val decision = readPendingDecision(view) ?: return null
        return createBubbleDrawable(view, decision.side, decision.position, visualStableKey(view, null))
    }

    fun applyPendingBubbleBackground(view: View): Boolean {
        if (!isMessageTextView(view) || isReplacingBackground(view)) {
            return false
        }
        val drawable = createPendingBubbleDrawable(view) ?: return false
        XposedHelpers.setAdditionalInstanceField(view, keyReplacingBackground, true)
        try {
            view.background = drawable
        } finally {
            XposedHelpers.removeAdditionalInstanceField(view, keyReplacingBackground)
        }
        return true
    }

    fun shouldWatchView(view: View): Boolean {
        if (XposedHelpers.getAdditionalInstanceField(view, keyCandidate) == true) {
            return true
        }
        val name = getResourceEntryName(view) ?: return false
        return name == resourceItemRoot || name == resourceMessage
    }

    fun hasStyledMessage(itemView: View): Boolean {
        val messageView = when {
            isMessageTextView(itemView) -> itemView
            else -> findViewByResourceName(itemView, resourceMessage)
        } ?: return false
        return isKnownMessageView(messageView)
    }

    fun isMessageTextView(view: View): Boolean {
        if (view.id == View.NO_ID) {
            return false
        }
        val cachedId = messageViewIdCache[view.context]
        if (cachedId != null) {
            return view.id == cachedId
        }
        val resolvedId = try {
            view.resources.getIdentifier(resourceMessage, "id", view.context.packageName)
        } catch (_: Throwable) {
            0
        }
        if (resolvedId != 0) {
            messageViewIdCache[view.context] = resolvedId
            return view.id == resolvedId
        }
        return getResourceEntryName(view) == resourceMessage
    }

    fun drawSignature(view: View): String? {
        if (!isMessageTextView(view)) {
            return null
        }
        val itemView = findItemRoot(view) ?: return null
        val textHash = extractText(view)?.hashCode() ?: 0
        return "${System.identityHashCode(itemView)}:${view.left},${view.top}:${view.width}x${view.height}:$textHash"
    }

    fun applyFromChangedView(view: View) {
        if (!isEnabled) {
            return
        }
        val itemView = findItemRoot(view) ?: return
        val messageView = findViewByResourceName(itemView, resourceMessage) ?: return
        applyFromRenderedMessage(messageView)
    }

    fun applyFromBoundItem(itemView: View) {
        if (!isEnabled) {
            return
        }
        val normalizedItem = findItemRoot(itemView) ?: return
        val messageView = findViewByResourceName(normalizedItem, resourceMessage) ?: return
        applyFromRenderedMessage(messageView)
    }

    fun prepareFromAdapterBind(adapter: Any, position: Int, itemView: View): Boolean {
        if (!isEnabled || position < 0) {
            return false
        }
        val normalizedItem = findItemRoot(itemView) ?: itemView
        val state = resolveRenderStateFromVisibleAdapterItem(adapter, position, normalizedItem)
        val decision = if (state != null) {
            BubbleDecision(state.side, state.position, false)
        } else {
            computeDecisionFromAdapter(adapter, position)
        } ?: return false
        setPendingDecision(normalizedItem, decision)
        return true
    }

    fun applyFromAdapterBind(adapter: Any, position: Int, itemView: View): Boolean {
        if (!isEnabled) {
            return false
        }
        val normalizedItem = findItemRoot(itemView) ?: itemView
        val info = readDirectTextItemInfo(normalizedItem)
        if (info == null) {
            diagnoseRejectedItem(normalizedItem)
            return false
        }
        resolveRenderStateFromVisibleAdapterItem(adapter, position, normalizedItem)?.let { state ->
            rememberBoundRenderStateOnView(adapter, position, state.stableKey, normalizedItem)
            rememberBoundRenderStateOnView(adapter, position, state.stableKey, info.messageView)
            applyMessageVisuals(
                msgView = info.messageView,
                side = state.side,
                position = state.position,
                stableKey = state.stableKey,
                itemView = info.itemView,
                marginTarget = info.messageView.parent as? View ?: info.itemView,
                avatarView = info.avatarView,
                nicknameView = info.nicknameView
            )
            return true
        }
        val window = AdapterMessageReader.readWindow(
            adapter = adapter,
            startPosition = position - adapterWindowPadding,
            endPosition = position + adapterWindowPadding,
            anchorPosition = position
        )
        if (window == null) {
            logDiagnosticOnce(
                "ModernChatBubbleStyler.noAdapterData.${adapter.javaClass.name}",
                "ModernChatBubble no adapter message data adapter=${adapter.javaClass.name}"
            )
            probeAdapterOnce(itemView, adapter, position)
            return false
        }
        val visibleItem = VisibleTextItem(position, 0, normalizedItem, info, null)
        val matchedEntry = findBestWindowMatch(window, visibleItem) ?: return false
        val neighbors = createBoundNeighbors(
            window = window,
            matchedEntry = matchedEntry,
            requestPosition = position,
            hasTimeBeforeCurrent = info.hasTimeSeparator,
            hasTimeBeforeNext = false
        )
        logDiagnosticOnce(
            "ModernChatBubbleStyler.adapterData.${adapter.javaClass.name}",
            "ModernChatBubble adapter data source=${neighbors.sourceLabel} adapter=${adapter.javaClass.name}"
        )
        probeAdapterOnce(itemView, adapter, position, neighbors.sourceLabel)
        return applyItem(normalizedItem, neighbors, info)
    }

    fun applyFromAdapterBindAndVisibleNeighbors(adapter: Any, position: Int, itemView: View): Boolean {
        val normalizedItem = findItemRoot(itemView) ?: itemView
        val applied = applyFromAdapterBind(adapter, position, normalizedItem)
        if (!applied) {
            return false
        }
        refreshVisibleNeighbor(adapter, position - 1, adjacentVisibleItem(normalizedItem, step = -1))
        refreshVisibleNeighbor(adapter, position + 1, adjacentVisibleItem(normalizedItem, step = 1))
        return true
    }

    private fun refreshVisibleNeighbor(adapter: Any, position: Int, itemView: View?) {
        if (position < 0 || itemView == null) {
            return
        }
        applyFromAdapterBind(adapter, position, itemView)
    }

    private fun adjacentVisibleItem(itemView: View, step: Int): View? {
        val parent = itemView.parent as? ViewGroup ?: return null
        val currentTop = screenTop(itemView)
        var bestChild: View? = null
        var bestTop = if (step > 0) Int.MAX_VALUE else Int.MIN_VALUE
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child === itemView) {
                continue
            }
            val childTop = screenTop(findItemRoot(child) ?: child)
            if (step > 0) {
                if (childTop > currentTop && childTop < bestTop) {
                    bestTop = childTop
                    bestChild = child
                }
            } else if (step < 0) {
                if (childTop < currentTop && childTop > bestTop) {
                    bestTop = childTop
                    bestChild = child
                }
            }
        }
        return bestChild
    }

    private fun screenTop(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[1]
    }

    private fun scheduleLegacyAppendRefresh(adapter: Any, position: Int, itemView: View) {
        if (XposedHelpers.getAdditionalInstanceField(itemView, keyPendingLegacyAppendRefresh) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(itemView, keyPendingLegacyAppendRefresh, true)
        scheduleLegacyAppendRefreshAttempt(adapter, position, itemView, attempt = 0)
    }

    private fun scheduleLegacyAppendRefreshAttempt(
        adapter: Any,
        position: Int,
        itemView: View,
        attempt: Int
    ) {
        val delay = legacyAppendRefreshDelaysMs.getOrNull(attempt)
        if (delay == null) {
            XposedHelpers.removeAdditionalInstanceField(itemView, keyPendingLegacyAppendRefresh)
            return
        }
        itemView.postDelayed({
            val root = findItemRoot(itemView) ?: itemView
            val applied = applyFromAdapterBindAndVisibleNeighbors(adapter, position, root)
            val state = if (applied) resolveRenderStateFromVisibleAdapterItem(adapter, position, root) else null
            val shouldRetry = state == null || state.position == GroupPosition.SINGLE
            if (shouldRetry && attempt + 1 < legacyAppendRefreshDelaysMs.size) {
                scheduleLegacyAppendRefreshAttempt(adapter, position, root, attempt + 1)
            } else {
                XposedHelpers.removeAdditionalInstanceField(root, keyPendingLegacyAppendRefresh)
                if (root !== itemView) {
                    XposedHelpers.removeAdditionalInstanceField(itemView, keyPendingLegacyAppendRefresh)
                }
            }
        }, delay)
    }

    fun probeBindHit(itemView: View, adapter: Any, holder: Any, position: Int, source: String) {
        if (!isEnabled) {
            return
        }
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val text = findViewByResourceName(itemView, resourceMessage)
            ?.let { extractText(it)?.take(40) }
            ?: ""
        val key = "$source:${adapter.javaClass.name}:${holder.javaClass.name}:${getResourceEntryName(itemView)}:$position:$text"
        if (bindProbeLogs.size >= 32 || bindProbeLogs.contains(key)) {
            return
        }
        bindProbeLogs.add(key)
        RuntimeProbe.append(
            itemView.context,
            bubbleProbeFile,
            "ModernChatBubble bind hit source=$source adapter=${adapter.javaClass.name} " +
                    "holder=${holder.javaClass.name} item=${itemView.javaClass.name} " +
                    "res=${getResourceEntryName(itemView)} pos=$position text=$text"
        )
        LogUtil.log(
            "ModernChatBubble bind hit source=$source adapter=${adapter.javaClass.name} " +
                    "holder=${holder.javaClass.name} pos=$position"
        )
    }

    fun probeRuntime(context: android.content.Context?, fileName: String, message: String) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        RuntimeProbe.append(context, fileName, message)
    }

    fun shouldWriteVerboseProbe(): Boolean = enableVerboseBubbleProbe

    fun debugBubbleProbe(context: Context?, message: String) {
        if (!enableDebugBubbleProbe) {
            return
        }
        val key = message.take(260)
        if (debugProbeLogs.size >= 420 || debugProbeLogs.contains(key)) {
            return
        }
        debugProbeLogs.add(key)
        RuntimeProbe.append(context, debugProbeFile, "ModernChatBubbleDebug $message")
    }

    fun rememberBoundRenderState(
        adapter: Any,
        position: Int,
        itemView: View,
        state: ChatBubbleStylePolicy.RenderState
    ) {
        val normalizedItem = findItemRoot(itemView) ?: itemView
        rememberBoundRenderStateOnView(adapter, position, state.stableKey, normalizedItem)
        findViewByResourceName(normalizedItem, resourceMessage)?.let { messageView ->
            rememberBoundRenderStateOnView(adapter, position, state.stableKey, messageView)
        }
    }

    private fun rememberBoundRenderStateOnView(
        adapter: Any,
        position: Int,
        stableKey: String,
        view: View
    ) {
        XposedHelpers.setAdditionalInstanceField(view, keyBoundAdapterClass, adapter.javaClass.name)
        XposedHelpers.setAdditionalInstanceField(view, keyBoundAdapterPosition, position)
        XposedHelpers.setAdditionalInstanceField(view, keyBoundStableKey, stableKey)
    }

    fun extractRenderedText(view: View?): String? {
        return extractText(view)
    }

    fun hasVisibleTimeSeparatorBefore(itemView: View?): Boolean {
        val root = itemView?.let { findItemRoot(it) ?: it } ?: return false
        return findTimeSeparatorView(root) != null
    }

    fun resolveRenderStateFromAdapter(adapter: Any, position: Int): ChatBubbleStylePolicy.RenderState? {
        return resolveRenderStateFromAdapter(
            adapter = adapter,
            position = position,
            hasTimeBeforeCurrent = false,
            hasTimeBeforeNext = false
        )
    }

    fun resolveRenderStateFromVisibleAdapterItem(
        adapter: Any,
        position: Int,
        itemView: View
    ): ChatBubbleStylePolicy.RenderState? {
        val root = findItemRoot(itemView) ?: itemView
        val nextRoot = adjacentVisibleItem(root, step = 1)?.let { findItemRoot(it) ?: it }
        return resolveRenderStateFromAdapter(
            adapter = adapter,
            position = position,
            hasTimeBeforeCurrent = hasVisibleTimeSeparatorBefore(root),
            hasTimeBeforeNext = hasVisibleTimeSeparatorBefore(nextRoot)
        )
    }

    fun resolveRenderStateFromAdapter(
        adapter: Any,
        position: Int,
        hasTimeBeforeCurrent: Boolean,
        hasTimeBeforeNext: Boolean
    ): ChatBubbleStylePolicy.RenderState? {
        if (!isEnabled || position < 0) {
            return null
        }
        val window = AdapterMessageReader.readWindow(
            adapter = adapter,
            startPosition = position - adapterWindowPadding,
            endPosition = position + adapterWindowPadding,
            anchorPosition = position
        ) ?: return null
        val current = window.byPosition[position]?.meta ?: return null
        val currentIndex = window.entries.indexOfFirst { it.position == position }
        val nextEntry = if (currentIndex >= 0) {
            window.entries.getOrNull(currentIndex + 1)
        } else {
            null
        }
        val rows = window.entries.map { entry ->
            entry.meta.toMessageRow(
                hasTimeSeparatorBefore = hasTimeSeparatorBefore(
                    entry = entry,
                    currentStableKey = current.stableKey,
                    hasTimeBeforeCurrent = hasTimeBeforeCurrent,
                    nextStableKey = nextEntry?.meta?.stableKey,
                    hasTimeBeforeNext = hasTimeBeforeNext
                )
            )
        }
        return ChatBubbleStylePolicy.resolveRenderStates(rows)[current.stableKey]
    }

    fun resolveVisibleRenderStatesFromAdapter(
        recycler: ViewGroup,
        adapter: Any
    ): List<VisibleRenderState> {
        if (!isEnabled) {
            return emptyList()
        }
        val visibleItems = collectVisibleTextItems(recycler)
        if (visibleItems.isEmpty()) {
            return emptyList()
        }
        val window = readWindowForVisibleResolution(adapter, visibleItems) ?: return emptyList()
        val matchedItems = matchVisibleItems(window, visibleItems)
        val timeBeforeByPosition = matchedItems.associate { (visibleItem, matchedEntry) ->
            matchedEntry.position to visibleItem.info.hasTimeSeparator
        }
        val timeBeforeByStableKey = matchedItems.associate { (visibleItem, matchedEntry) ->
            matchedEntry.meta.stableKey to visibleItem.info.hasTimeSeparator
        }
        val states = ChatBubbleStylePolicy.resolveRenderStates(
            window.entries.map { entry ->
                entry.meta.toMessageRow(
                    hasTimeSeparatorBefore = timeBeforeByPosition[entry.position] == true ||
                            timeBeforeByStableKey[entry.meta.stableKey] == true
                )
            }
        )
        debugVisibleResolution(recycler, adapter, window, visibleItems, matchedItems, states)
        return matchedItems.mapNotNull { (visibleItem, matchedEntry) ->
            rememberWindowEntry(adapter, visibleItem, matchedEntry)
            states[matchedEntry.meta.stableKey]?.let { state ->
                VisibleRenderState(visibleItem.itemView, state)
            }
        }
    }

    fun invalidateAdapterData(adapter: Any) {
        AdapterMessageReader.invalidate(adapter)
    }

    private fun hasTimeSeparatorBefore(
        entry: WindowEntry,
        currentStableKey: String,
        hasTimeBeforeCurrent: Boolean,
        nextStableKey: String?,
        hasTimeBeforeNext: Boolean
    ): Boolean {
        return entry.meta.stableKey == currentStableKey && hasTimeBeforeCurrent ||
                entry.meta.stableKey == nextStableKey && hasTimeBeforeNext
    }

    fun resolveRenderStateFromMessage(msgInfo: Any): ChatBubbleStylePolicy.RenderState? {
        if (!isEnabled) {
            return null
        }
        val meta = AdapterMessageReader.readMeta(msgInfo) ?: return null
        val side = meta.side ?: return null
        return ChatBubbleStylePolicy.RenderState(
            stableKey = meta.stableKey,
            side = side,
            position = GroupPosition.SINGLE,
            showAvatar = true,
            showNickname = side == Side.LEFT,
            topMarginDp = ChatBubbleStylePolicy.topMarginDp(GroupPosition.SINGLE),
            cornerRadii = ChatBubbleStylePolicy.cornerRadii(side, GroupPosition.SINGLE)
        )
    }

    fun resolveRenderContextFromChattingItemBind(
        boundView: View,
        holder: Any?,
        msgInfo: Any
    ): RenderContext? {
        if (!isEnabled) {
            return null
        }
        val itemView = findItemRoot(boundView) ?: boundView
        val recyclerChild = findRecyclerChild(itemView)
        val recycler = recyclerChild?.parent as? ViewGroup
        val adapter = recycler?.let { getRecyclerAdapter(it) }
        val position = if (recycler != null && recyclerChild != null) {
            val recyclerPosition = getChildAdapterPosition(recycler, recyclerChild)
            if (recyclerPosition >= 0) recyclerPosition else readAdapterPositionFromHolder(holder)
        } else {
            readAdapterPositionFromHolder(holder)
        }
        if (adapter != null && position >= 0) {
            resolveRenderStateFromVisibleAdapterItem(adapter, position, itemView)?.let {
                return RenderContext(it, adapter, position)
            }
        }
        val state = resolveRenderStateFromMessage(msgInfo) ?: return null
        return RenderContext(state, null, -1)
    }

    fun applyFromChattingItemBind(boundView: View, holder: Any?, msgInfo: Any): Boolean {
        if (!isEnabled) {
            return false
        }
        val itemView = findItemRoot(boundView) ?: boundView
        val recyclerChild = findRecyclerChild(itemView)
        val recycler = recyclerChild?.parent as? ViewGroup
        val adapter = recycler?.let { getRecyclerAdapter(it) }
        val position = if (recycler != null && recyclerChild != null) {
            val recyclerPosition = getChildAdapterPosition(recycler, recyclerChild)
            if (recyclerPosition >= 0) recyclerPosition else readAdapterPositionFromHolder(holder)
        } else {
            readAdapterPositionFromHolder(holder)
        }
        if (adapter != null && position >= 0 && applyFromAdapterBind(adapter, position, itemView)) {
            return true
        }
        val meta = AdapterMessageReader.readMeta(msgInfo)
        if (meta != null && applyCachedMetaDecision(itemView, meta)) {
            logDiagnosticOnce(
                "ModernChatBubbleStyler.chattingItemData.${msgInfo.javaClass.name}",
                "ModernChatBubble chatting item data msg=${msgInfo.javaClass.name}"
            )
            return true
        }
        return false
    }

    fun prepareFromChattingItemBind(boundView: View, holder: Any?, msgInfo: Any): Boolean {
        if (!isEnabled) {
            return false
        }
        val itemView = findItemRoot(boundView) ?: boundView
        val recyclerChild = findRecyclerChild(itemView)
        val recycler = recyclerChild?.parent as? ViewGroup
        val adapter = recycler?.let { getRecyclerAdapter(it) }
        val position = if (recycler != null && recyclerChild != null) {
            val recyclerPosition = getChildAdapterPosition(recycler, recyclerChild)
            if (recyclerPosition >= 0) recyclerPosition else readAdapterPositionFromHolder(holder)
        } else {
            readAdapterPositionFromHolder(holder)
        }
        if (adapter != null && position >= 0 && prepareFromAdapterBind(adapter, position, itemView)) {
            return true
        }
        val meta = AdapterMessageReader.readMeta(msgInfo) ?: return false
        val decision = cachedDecisionForMeta(meta) ?: return false
        setPendingDecision(itemView, decision)
        return true
    }

    fun probeChattingItemBindHit(boundView: View, holder: Any, msgInfo: Any, source: String) {
        if (!isEnabled) {
            return
        }
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val itemView = readTextItemInfo(boundView)?.itemView
            ?: findViewByResourceName(boundView, resourceItemRoot)
            ?: boundView
        val messageView = findViewByResourceName(itemView, resourceMessage)
        val text = messageView?.let { extractText(it)?.take(40) } ?: ""
        val key = "$source:${holder.javaClass.name}:${msgInfo.javaClass.name}:${getResourceEntryName(itemView)}:$text"
        if (chatItemProbeLogs.size >= 24 || chatItemProbeLogs.contains(key)) {
            return
        }
        chatItemProbeLogs.add(key)
        RuntimeProbe.append(
            boundView.context,
            bubbleProbeFile,
            "ModernChatBubble chat-item bind source=$source holder=${holder.javaClass.name} " +
                    "msg=${msgInfo.javaClass.name} item=${itemView.javaClass.name} " +
                    "res=${getResourceEntryName(itemView)} meta=${AdapterMessageReader.readMeta(msgInfo)} text=$text"
        )
        LogUtil.log(
            "ModernChatBubble chat-item bind source=$source holder=${holder.javaClass.name} " +
                    "msg=${msgInfo.javaClass.name}"
        )
    }

    fun applyFromRenderedMessage(messageView: View): Boolean {
        if (!isEnabled) {
            return false
        }
        val itemView = findItemRoot(messageView) ?: return false
        val recyclerChild = findRecyclerChild(itemView)
        val recycler = recyclerChild?.parent as? ViewGroup
        val adapter = recycler?.let { getRecyclerAdapter(it) }
        val position = if (recycler != null && recyclerChild != null) {
            getChildAdapterPosition(recycler, recyclerChild)
        } else {
            -1
        }
        if (adapter != null && position >= 0) {
            return applyFromAdapterBind(adapter, position, itemView)
        }
        probeRenderLookupOnce(messageView, itemView, recycler, adapter, position)
        return false
    }

    fun applyVisibleChildren(parent: ViewGroup) {
        if (!isEnabled) {
            return
        }
        disableAncestorClipping(parent)
        val adapter = getRecyclerAdapter(parent)
        if (adapter != null) {
            applyVisibleChildrenFromAdapter(parent, adapter)
            return
        }
        for (index in 0 until parent.childCount) {
            applyItem(parent.getChildAt(index))
        }
    }

    fun applyVisibleChildrenFromAdapter(recycler: ViewGroup, adapter: Any): Int {
        if (!isEnabled) {
            return 0
        }
        disableAncestorClipping(recycler)
        var visibleItems = collectVisibleTextItems(recycler)
        if (visibleItems.isEmpty()) {
            probeVisibleScan(recycler, adapter, 0)
            probeVisibleScanDetails(recycler, adapter)
            visibleItems = collectVisibleTextItems(recycler)
            if (visibleItems.isEmpty()) {
                return 0
            }
        }
        val window = readWindowForVisibleResolution(adapter, visibleItems)
        if (window == null) {
            logDiagnosticOnce(
                "ModernChatBubbleStyler.noVisibleWindow.${adapter.javaClass.name}",
                "ModernChatBubble no visible message window adapter=${adapter.javaClass.name}"
            )
            return 0
        }

        val matchedItems = matchVisibleItems(window, visibleItems)
        val timeBeforeByPosition = matchedItems.associate { (visibleItem, matchedEntry) ->
            matchedEntry.position to visibleItem.info.hasTimeSeparator
        }
        val timeBeforeByStableKey = matchedItems.associate { (visibleItem, matchedEntry) ->
            matchedEntry.meta.stableKey to visibleItem.info.hasTimeSeparator
        }
        val states = ChatBubbleStylePolicy.resolveRenderStates(
            window.entries.map { entry ->
                entry.meta.toMessageRow(
                    hasTimeSeparatorBefore = timeBeforeByPosition[entry.position] == true ||
                            timeBeforeByStableKey[entry.meta.stableKey] == true
                )
            }
        )
        debugVisibleResolution(recycler, adapter, window, visibleItems, matchedItems, states)
        val visibleByMatchedPosition = matchedItems.associate { (visibleItem, matchedEntry) ->
            matchedEntry.position to visibleItem
        }
        probeVisibleWindow(recycler, adapter, window, visibleItems.size, matchedItems.size)

        val topVisibleIndex = matchedItems.minOfOrNull { it.first.visibleIndex } ?: Int.MAX_VALUE
        var applied = 0
        for ((visibleItem, matchedEntry) in matchedItems) {
            rememberWindowEntry(adapter, visibleItem, matchedEntry)
            val state = states[matchedEntry.meta.stableKey]
            if (state != null) {
                applyMessageVisuals(
                    msgView = visibleItem.info.messageView,
                    side = state.side,
                    position = state.position,
                    stableKey = state.stableKey,
                    itemView = visibleItem.info.itemView,
                    marginTarget = visibleItem.info.messageView.parent as? View ?: visibleItem.info.itemView,
                    avatarView = visibleItem.info.avatarView,
                    nicknameView = visibleItem.info.nicknameView
                )
                applied++
                continue
            }
            val hasTimeBeforeNext = visibleByMatchedPosition[matchedEntry.position + 1]
                ?.info
                ?.hasTimeSeparator == true
            val neighbors = createBoundNeighbors(
                window = window,
                matchedEntry = matchedEntry,
                requestPosition = visibleItem.requestPosition,
                hasTimeBeforeCurrent = visibleItem.info.hasTimeSeparator,
                hasTimeBeforeNext = hasTimeBeforeNext
            )
            if (applyItem(
                    itemView = visibleItem.itemView,
                    boundNeighbors = neighbors,
                    knownInfo = visibleItem.info,
                    mayRefreshTopDecision = visibleItem.visibleIndex == topVisibleIndex
                )
            ) {
                applied++
            }
        }
        return applied
    }

    private fun readWindowForVisibleResolution(
        adapter: Any,
        visibleItems: List<VisibleTextItem>
    ): MessageWindow? {
        val positionedItems = visibleItems.filter { it.requestPosition >= 0 }
        val boundStableKeys = visibleItems
            .mapNotNull { it.boundStableKey?.takeIf { key -> key.isNotBlank() } }
            .distinct()
        val positionWindow = if (positionedItems.isNotEmpty()) {
            val minPosition = positionedItems.minOf { it.requestPosition }
            val maxPosition = positionedItems.maxOf { it.requestPosition }
            AdapterMessageReader.readWindow(
                adapter = adapter,
                startPosition = minPosition - adapterWindowPadding,
                endPosition = maxPosition + adapterWindowPadding,
                anchorPosition = minPosition
            )
        } else {
            null
        }
        if (positionWindow != null && boundStableKeys.all { positionWindow.byStableKey.containsKey(it) }) {
            return positionWindow
        }
        if (boundStableKeys.isNotEmpty()) {
            AdapterMessageReader.readWindowForBoundStableKeys(
                adapter = adapter,
                stableKeys = boundStableKeys,
                padding = adapterWindowPadding
            )?.let { return it }
        }
        return positionWindow ?: AdapterMessageReader.readWindowForVisibleItems(
            adapter = adapter,
            visibleItems = visibleItems,
            padding = adapterWindowPadding
        )
    }

    private fun rememberWindowEntry(adapter: Any, visibleItem: VisibleTextItem, entry: WindowEntry) {
        rememberBoundRenderStateOnView(adapter, entry.position, entry.meta.stableKey, visibleItem.itemView)
        rememberBoundRenderStateOnView(adapter, entry.position, entry.meta.stableKey, visibleItem.info.messageView)
    }

    private fun collectVisibleTextItems(recycler: ViewGroup): List<VisibleTextItem> {
        val result = mutableListOf<VisibleTextItem>()
        val adapter = getRecyclerAdapter(recycler)
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index)
            val itemView = findItemRoot(child) ?: continue
            val bound = readRememberedBoundState(itemView, adapter)
            val position = getChildAdapterPosition(recycler, child).takeIf { it >= 0 }
                ?: bound?.position
                ?: -1
            val info = readDirectTextItemInfo(itemView) ?: continue
            result += VisibleTextItem(position, index, itemView, info, bound?.stableKey)
        }
        return result
    }

    private fun readRememberedBoundState(itemView: View, adapter: Any?): RememberedBoundState? {
        val views = buildList {
            add(itemView)
            findViewByResourceName(itemView, resourceMessage)?.let { add(it) }
        }
        for (view in views) {
            val adapterClass = XposedHelpers.getAdditionalInstanceField(view, keyBoundAdapterClass) as? String
            if (adapter != null && adapterClass != adapter.javaClass.name) {
                continue
            }
            val position = XposedHelpers.getAdditionalInstanceField(view, keyBoundAdapterPosition) as? Int
            val stableKey = XposedHelpers.getAdditionalInstanceField(view, keyBoundStableKey) as? String
            if (position != null && position >= 0 && !stableKey.isNullOrBlank()) {
                return RememberedBoundState(position, stableKey)
            }
        }
        return null
    }

    private fun matchVisibleItems(
        window: MessageWindow,
        visibleItems: List<VisibleTextItem>
    ): List<Pair<VisibleTextItem, WindowEntry>> {
        val hasBoundStableKeys = visibleItems.any { !it.boundStableKey.isNullOrBlank() }
        val sortedItems = visibleItems.sortedWith(
            compareBy<VisibleTextItem> {
                if (!hasBoundStableKeys && it.requestPosition >= 0) it.requestPosition else it.visibleIndex
            }.thenBy { it.visibleIndex }
        )
        if (sortedItems.isEmpty()) {
            return emptyList()
        }
        if (sortedItems.all { it.requestPosition < 0 }) {
            val sequenceMatches = matchVisibleItemsBySequence(window, sortedItems)
            if (sequenceMatches.isNotEmpty()) {
                return sequenceMatches
            }
        }
        val usedPositions = mutableSetOf<Int>()
        return sortedItems.mapNotNull { visibleItem ->
            val matchedEntry = findBestWindowMatch(window, visibleItem, usedPositions) ?: return@mapNotNull null
            usedPositions += matchedEntry.position
            visibleItem to matchedEntry
        }
    }

    private fun debugVisibleResolution(
        recycler: ViewGroup,
        adapter: Any,
        window: MessageWindow,
        visibleItems: List<VisibleTextItem>,
        matchedItems: List<Pair<VisibleTextItem, WindowEntry>>,
        states: Map<String, ChatBubbleStylePolicy.RenderState>?
    ) {
        if (!enableDebugBubbleProbe) {
            return
        }
        val visibleSummary = visibleItems.take(12).joinToString("|") { item ->
            "#${item.visibleIndex}:req=${item.requestPosition}:txt=${debugText(item.info.visibleText)}:" +
                    "side=${item.info.side}:time=${item.info.hasTimeSeparator}:h=${item.itemView.height}:" +
                    "bound=${item.boundStableKey?.takeLast(10)}"
        }
        val matchedSummary = matchedItems.take(12).joinToString("|") { (visible, entry) ->
            val state = states?.get(entry.meta.stableKey)
            "#${visible.visibleIndex}:req=${visible.requestPosition}->${entry.position}:" +
                    "state=${state?.position}:meta=${debugMeta(entry.meta)}"
        }
        debugBubbleProbe(
            recycler.context,
            "visibleResolve adapter=${adapter.javaClass.name} source=${window.sourceLabel} " +
                    "children=${recycler.childCount} visible=${visibleItems.size} matched=${matchedItems.size} " +
                    "visibleItems=$visibleSummary matches=$matchedSummary"
        )
    }

    private fun debugMeta(meta: RowMeta?): String {
        meta ?: return "null"
        return "${meta.side}:${meta.isTextMessage}:${meta.createTimeMs}:${debugText(meta.contentText)}:" +
                meta.stableKey.takeLast(10)
    }

    private fun debugText(text: String?): String {
        return text
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.take(24)
            ?: ""
    }

    private fun matchVisibleItemsBySequence(
        window: MessageWindow,
        sortedItems: List<VisibleTextItem>
    ): List<Pair<VisibleTextItem, WindowEntry>> {
        val candidatesByItem = sortedItems.map { visibleItem ->
            val visibleText = normalizedTextForMatch(visibleItem.info.visibleText)
            if (visibleText.isBlank()) {
                emptyList()
            } else {
                window.entries
                    .filter { entry -> entry.meta.isTextMessage }
                    .filter { entry -> matchesVisibleText(entry.meta, visibleText) }
                    .map { entry ->
                        SequenceCandidate(
                            entry = entry,
                            baseScore = matchTextPenalty(entry.meta, visibleText) * 100 +
                                    matchSidePenalty(entry.meta, visibleItem.info.side) * 20
                        )
                    }
            }
        }
        if (candidatesByItem.any { it.isEmpty() }) {
            return emptyList()
        }

        var states = candidatesByItem.first().map { candidate ->
            SequenceState(
                entry = candidate.entry,
                score = candidate.baseScore,
                path = listOf(candidate.entry)
            )
        }
        for (itemIndex in 1 until candidatesByItem.size) {
            val nextStates = mutableListOf<SequenceState>()
            for (candidate in candidatesByItem[itemIndex]) {
                val bestPrevious = states
                    .asSequence()
                    .filter { state -> state.entry.position < candidate.entry.position }
                    .minByOrNull { state ->
                        state.score + candidate.baseScore +
                                sequenceGapPenalty(state.entry.position, candidate.entry.position)
                    } ?: continue
                nextStates += SequenceState(
                    entry = candidate.entry,
                    score = bestPrevious.score + candidate.baseScore +
                            sequenceGapPenalty(bestPrevious.entry.position, candidate.entry.position),
                    path = bestPrevious.path + candidate.entry
                )
            }
            if (nextStates.isEmpty()) {
                return emptyList()
            }
            states = nextStates
        }
        val bestState = states.minByOrNull { state ->
            state.score + (state.path.last().position - state.path.first().position)
        } ?: return emptyList()
        return sortedItems.zip(bestState.path)
    }

    private fun sequenceGapPenalty(previousPosition: Int, currentPosition: Int): Int {
        val gap = currentPosition - previousPosition - 1
        return when {
            gap <= 0 -> 0
            gap <= 2 -> gap
            else -> minOf(40, gap * 2)
        }
    }

    private fun probeVisibleScan(recycler: ViewGroup, adapter: Any, visibleCount: Int) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val key = "scan:${adapter.javaClass.name}:${System.identityHashCode(recycler)}:$visibleCount:${recycler.childCount}"
        if (adapterProbeLogs.size >= 64 || adapterProbeLogs.contains(key)) {
            return
        }
        adapterProbeLogs.add(key)
        RuntimeProbe.append(
            recycler.context,
            bubbleProbeFile,
            "ModernChatBubble visible scan adapter=${adapter.javaClass.name} " +
                    "children=${recycler.childCount} visibleTextItems=$visibleCount"
        )
    }

    private fun probeVisibleScanDetails(recycler: ViewGroup, adapter: Any) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val key = "scanDetails:${adapter.javaClass.name}:${System.identityHashCode(recycler)}:${recycler.childCount}"
        if (adapterProbeLogs.size >= 64 || adapterProbeLogs.contains(key)) {
            return
        }
        adapterProbeLogs.add(key)
        val details = buildString {
            val count = minOf(recycler.childCount, 8)
            for (index in 0 until count) {
                val child = recycler.getChildAt(index)
                val position = getChildAdapterPosition(recycler, child)
                val root = findItemRoot(child)
                val messageView = root?.let { findViewByResourceName(it, resourceMessage) }
                val avatarView = root?.let { findViewByResourceName(it, resourceAvatar) }
                val side = if (root != null && messageView != null) {
                    resolveSide(root, messageView, avatarView)
                } else {
                    null
                }
                append("#")
                    .append(index)
                    .append(" child=")
                    .append(child.javaClass.simpleName)
                    .append(":")
                    .append(getResourceEntryName(child))
                    .append(" pos=")
                    .append(position)
                    .append(" ")
                    .append(child.width)
                    .append("x")
                    .append(child.height)
                    .append(" root=")
                    .append(root?.javaClass?.simpleName)
                    .append(":")
                    .append(root?.let { getResourceEntryName(it) })
                    .append(" ")
                    .append(root?.width)
                    .append("x")
                    .append(root?.height)
                    .append(" msg=")
                    .append(messageView?.javaClass?.simpleName)
                    .append(":")
                    .append(messageView?.let { getResourceEntryName(it) })
                    .append(" ")
                    .append(messageView?.width)
                    .append("x")
                    .append(messageView?.height)
                    .append(" text=")
                    .append(messageView?.let { extractText(it)?.take(16) })
                    .append(" avatar=")
                    .append(avatarView?.width)
                    .append("x")
                    .append(avatarView?.height)
                    .append(" side=")
                    .append(side)
                    .append("; ")
            }
        }
        RuntimeProbe.append(
            recycler.context,
            bubbleProbeFile,
            "ModernChatBubble visible scan details adapter=${adapter.javaClass.name} $details"
        )
    }

    private fun findBestWindowMatch(
        window: MessageWindow,
        visibleItem: VisibleTextItem,
        excludedPositions: Set<Int> = emptySet()
    ): WindowEntry? {
        val visibleText = normalizedTextForMatch(visibleItem.info.visibleText)
        if (visibleText.isBlank()) {
            return null
        }
        visibleItem.boundStableKey?.let { stableKey ->
            val entry = window.byStableKey[stableKey]
            if (entry != null &&
                entry.position !in excludedPositions &&
                entry.meta.isTextMessage &&
                matchesVisibleText(entry.meta, visibleText)
            ) {
                return entry
            }
            return null
        }
        if (visibleItem.requestPosition >= 0 && visibleItem.requestPosition !in excludedPositions) {
            window.byPosition[visibleItem.requestPosition]?.let { entry ->
                if (entry.meta.isTextMessage && matchesVisibleText(entry.meta, visibleText)) {
                    return entry
                }
            }
        }
        return window.entries
            .asSequence()
            .filter { entry -> entry.position !in excludedPositions }
            .filter { entry -> entry.meta.isTextMessage }
            .filter { entry -> matchesVisibleText(entry.meta, visibleText) }
            .minWithOrNull(
                compareBy<WindowEntry>(
                    { matchTextPenalty(it.meta, visibleText) },
                    { matchSidePenalty(it.meta, visibleItem.info.side) },
                    { positionPenalty(it.position, visibleItem.requestPosition) }
                )
            )
    }

    private fun createBoundNeighbors(
        window: MessageWindow,
        matchedEntry: WindowEntry,
        requestPosition: Int,
        hasTimeBeforeCurrent: Boolean,
        hasTimeBeforeNext: Boolean
    ): BoundNeighbors {
        val matchedPosition = matchedEntry.position
        val label = if (matchedPosition == requestPosition) {
            "${window.sourceLabel}.window"
        } else {
            "${window.sourceLabel}.window[$requestPosition->$matchedPosition]"
        }
        return BoundNeighbors(
            current = matchedEntry.meta,
            previous = window.byPosition[matchedPosition - 1]?.meta,
            next = window.byPosition[matchedPosition + 1]?.meta,
            sourceLabel = label,
            requestPosition = requestPosition,
            matchedPosition = matchedPosition,
            hasTimeBeforeCurrent = hasTimeBeforeCurrent,
            hasTimeBeforeNext = hasTimeBeforeNext
        )
    }

    private fun matchesVisibleText(meta: RowMeta, normalizedVisibleText: String): Boolean {
        val content = normalizedTextForMatch(meta.contentText)
        if (content.isBlank()) {
            return false
        }
        return content.contains(normalizedVisibleText) || normalizedVisibleText.contains(content)
    }

    private fun matchTextPenalty(meta: RowMeta, normalizedVisibleText: String): Int {
        val content = normalizedTextForMatch(meta.contentText)
        return when {
            content == normalizedVisibleText -> 0
            content.contains(normalizedVisibleText) -> 1
            normalizedVisibleText.contains(content) -> 2
            else -> 100
        }
    }

    private fun matchSidePenalty(meta: RowMeta, visibleSide: Side): Int {
        return when (meta.side) {
            null -> 1
            visibleSide -> 0
            else -> 8
        }
    }

    private fun positionPenalty(position: Int, requestPosition: Int): Int {
        if (requestPosition < 0) {
            return 0
        }
        return kotlin.math.abs(position - requestPosition)
    }

    private fun probeVisibleWindow(
        recycler: ViewGroup,
        adapter: Any,
        window: MessageWindow,
        visibleCount: Int,
        matchedCount: Int
    ) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val firstPosition = window.entries.firstOrNull()?.position ?: -1
        val lastPosition = window.entries.lastOrNull()?.position ?: -1
        val key = "window:${adapter.javaClass.name}:${window.sourceLabel}:$firstPosition:$lastPosition:$matchedCount"
        if (adapterProbeLogs.size >= 64 || adapterProbeLogs.contains(key)) {
            return
        }
        adapterProbeLogs.add(key)
        RuntimeProbe.append(
            recycler.context,
            bubbleProbeFile,
            "ModernChatBubble visible window adapter=${adapter.javaClass.name} source=${window.sourceLabel} " +
                    "range=$firstPosition..$lastPosition entries=${window.entries.size} " +
                    "visible=$visibleCount matched=$matchedCount"
        )
    }

    fun applyItemAndNeighbors(itemView: View) {
        if (!isEnabled) {
            return
        }
        applyFallbackItem(itemView)
    }

    fun applyLegacyTextMessage(itemView: View, msgView: View, side: Side) {
        if (!isEnabled) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(msgView, keyCandidate, true)
        applyMessageVisuals(
            msgView = msgView,
            side = side,
            position = GroupPosition.SINGLE,
            stableKey = visualStableKey(msgView, itemView),
            itemView = itemView,
            marginTarget = msgView.parent as? View ?: itemView,
            avatarView = null,
            nicknameView = null
        )
    }

    fun applyLegacyTextMessageFromAdapter(
        adapter: Any?,
        position: Int,
        itemView: View,
        msgView: View,
        side: Side,
        scheduleAppendRefresh: Boolean
    ) {
        if (!isEnabled) {
            return
        }
        val appliedFromAdapter = adapter != null &&
                position >= 0 &&
                applyFromAdapterBindAndVisibleNeighbors(adapter, position, itemView)
        if (!appliedFromAdapter) {
            applyLegacyTextMessage(itemView, msgView, side)
        }
        if (scheduleAppendRefresh && adapter != null && position >= 0 && side == Side.RIGHT) {
            refreshVisibleNeighbor(adapter, position - 1, adjacentVisibleItem(itemView, step = -1))
            refreshVisibleNeighbor(adapter, position + 1, adjacentVisibleItem(itemView, step = 1))
        }
    }

    fun scheduleVisibleClusterRefresh(itemView: View) {
        val root = findItemRoot(itemView) ?: itemView
        if (XposedHelpers.getAdditionalInstanceField(root, keyPendingVisibleClusterRefresh) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(root, keyPendingVisibleClusterRefresh, true)
        XposedHelpers.removeAdditionalInstanceField(root, keyPendingVisibleClusterRefresh)
        applyVisibleClusterAround(root)
    }

    private fun applyVisibleClusterAround(itemView: View): Boolean {
        val anchorRoot = findItemRoot(itemView) ?: itemView
        val parent = anchorRoot.parent as? ViewGroup ?: return false
        val anchorIndex = parent.indexOfChild(anchorRoot)
        if (anchorIndex < 0) {
            return false
        }
        val anchor = visibleClusterItem(anchorRoot) ?: return false
        val items = mutableListOf(anchor)

        var cursor = anchorIndex - 1
        while (cursor >= 0 && items.size < maxVisibleClusterItems) {
            if (items.first().info.hasTimeSeparator) {
                break
            }
            val candidate = visibleClusterItem(parent.getChildAt(cursor)) ?: break
            if (!canGroupVisible(candidate.info, items.first().info)) {
                break
            }
            items.add(0, candidate)
            cursor--
        }

        cursor = anchorIndex + 1
        while (cursor < parent.childCount && items.size < maxVisibleClusterItems) {
            val candidate = visibleClusterItem(parent.getChildAt(cursor)) ?: break
            if (candidate.info.hasTimeSeparator || !canGroupVisible(items.last().info, candidate.info)) {
                break
            }
            items.add(candidate)
            cursor++
        }

        if (items.size <= 1) {
            return false
        }
        items.forEachIndexed { index, item ->
            val position = ChatBubbleStylePolicy.groupPosition(
                hasPrevious = index > 0,
                hasNext = index < items.lastIndex
            )
            applyMessageVisuals(
                msgView = item.info.messageView,
                side = item.info.side,
                position = position,
                stableKey = visualStableKey(item.info.messageView, item.info.itemView),
                itemView = item.info.itemView,
                marginTarget = item.info.messageView.parent as? View ?: item.info.itemView,
                avatarView = item.info.avatarView,
                nicknameView = item.info.nicknameView
            )
        }
        return true
    }

    private fun visibleClusterItem(view: View): VisibleClusterItem? {
        val root = findItemRoot(view) ?: view
        val info = readDirectTextItemInfo(root) ?: return null
        return VisibleClusterItem(root, info)
    }

    private fun canGroupVisible(current: TextItemInfo, neighbor: TextItemInfo): Boolean {
        return ChatBubbleStylePolicy.canGroupWith(
            current = current.toCandidate(null),
            neighbor = neighbor.toCandidate(null)
        )
    }

    private fun applyItem(
        itemView: View,
        boundNeighbors: BoundNeighbors? = null,
        knownInfo: TextItemInfo? = null,
        mayRefreshTopDecision: Boolean = false
    ): Boolean {
        val rawInfo = knownInfo ?: readTextItemInfo(itemView, boundNeighbors?.current?.side)
        if (rawInfo == null) {
            diagnoseRejectedItem(itemView)
            return false
        }
        val currentMeta = boundNeighbors?.current
        val info = alignInfoWithMeta(rawInfo, currentMeta)
        val current = info.toCandidate(currentMeta)
        val previousCandidate = boundNeighbors?.previous?.toCandidate()
        val nextCandidate = boundNeighbors?.next?.toCandidate()
        val hasTimeBeforeCurrent = boundNeighbors?.hasTimeBeforeCurrent == true ||
                info.hasTimeSeparator ||
                isTimeSplit(boundNeighbors?.previous, currentMeta)
        val hasTimeBeforeNext = boundNeighbors?.hasTimeBeforeNext == true ||
                isTimeSplit(currentMeta, boundNeighbors?.next)
        val hasPrevious = !hasTimeBeforeCurrent && ChatBubbleStylePolicy.canGroupWith(
            current,
            previousCandidate
        )
        val hasNext = !hasTimeBeforeNext && ChatBubbleStylePolicy.canGroupWith(
            current,
            nextCandidate
        )
        val computedPosition = ChatBubbleStylePolicy.groupPosition(
            hasPrevious = hasPrevious,
            hasNext = hasNext
        )
        val position = resolveCachedDecision(
            info = info,
            currentMeta = currentMeta,
            boundNeighbors = boundNeighbors,
            computedPosition = computedPosition,
            mayRefreshTopDecision = mayRefreshTopDecision
        )
        probeStyleApply(info, currentMeta, boundNeighbors, position)
        applyMessageVisuals(
            msgView = info.messageView,
            side = info.side,
            position = position,
            stableKey = currentMeta?.stableKey ?: visualStableKey(info.messageView, info.itemView),
            itemView = info.itemView,
            marginTarget = info.messageView.parent as? View ?: info.itemView,
            avatarView = info.avatarView,
            nicknameView = info.nicknameView
        )
        probeLayoutApply(info, currentMeta, boundNeighbors, position)
        logDiagnosticOnce(
            "ModernChatBubbleStyler.textItem",
            "ModernChatBubble apply text side=${info.side} position=$position data=${boundNeighbors != null} msg=${info.shortText}"
        )
        return true
    }

    private fun computeDecisionFromAdapter(adapter: Any, position: Int): BubbleDecision? {
        val window = AdapterMessageReader.readWindow(
            adapter = adapter,
            startPosition = position - adapterWindowPadding,
            endPosition = position + adapterWindowPadding,
            anchorPosition = position
        ) ?: return null
        val matchedEntry = window.byPosition[position] ?: return null
        val neighbors = createBoundNeighbors(
            window = window,
            matchedEntry = matchedEntry,
            requestPosition = position,
            hasTimeBeforeCurrent = false,
            hasTimeBeforeNext = false
        )
        val meta = neighbors.current
        val side = meta.side ?: return null
        val computedPosition = computeGroupPosition(
            current = meta.toCandidate(),
            previousCandidate = neighbors.previous?.toCandidate(),
            nextCandidate = neighbors.next?.toCandidate(),
            hasTimeBeforeCurrent = isTimeSplit(neighbors.previous, meta),
            hasTimeBeforeNext = isTimeSplit(meta, neighbors.next)
        )
        val finalPosition = resolveCachedDecisionForMeta(
            side = side,
            meta = meta,
            computedPosition = computedPosition,
            mayRefreshTopDecision = false,
            previous = neighbors.previous
        )
        return BubbleDecision(side, finalPosition, false)
    }

    private fun applyCachedMetaDecision(itemView: View, meta: RowMeta): Boolean {
        val side = meta.side ?: return false
        val cached = cachedDecisionForMeta(meta) ?: return false
        val info = readTextItemInfo(itemView, side) ?: return false
        applyMessageVisuals(
            msgView = info.messageView,
            side = side,
            position = cached.position,
            stableKey = meta.stableKey,
            itemView = info.itemView,
            marginTarget = info.messageView.parent as? View ?: info.itemView,
            avatarView = info.avatarView,
            nicknameView = info.nicknameView
        )
        return true
    }

    private fun computeGroupPosition(
        current: MessageCandidate,
        previousCandidate: MessageCandidate?,
        nextCandidate: MessageCandidate?,
        hasTimeBeforeCurrent: Boolean,
        hasTimeBeforeNext: Boolean
    ): GroupPosition {
        val hasPrevious = !hasTimeBeforeCurrent && ChatBubbleStylePolicy.canGroupWith(
            current,
            previousCandidate
        )
        val hasNext = !hasTimeBeforeNext && ChatBubbleStylePolicy.canGroupWith(
            current,
            nextCandidate
        )
        return ChatBubbleStylePolicy.groupPosition(
            hasPrevious = hasPrevious,
            hasNext = hasNext
        )
    }

    private fun resolveCachedDecision(
        info: TextItemInfo,
        currentMeta: RowMeta?,
        boundNeighbors: BoundNeighbors?,
        computedPosition: GroupPosition,
        mayRefreshTopDecision: Boolean
    ): GroupPosition {
        currentMeta ?: return computedPosition
        return resolveCachedDecisionForMeta(
            side = info.side,
            meta = currentMeta,
            computedPosition = computedPosition,
            mayRefreshTopDecision = mayRefreshTopDecision,
            previous = boundNeighbors?.previous
        )
    }

    private fun resolveCachedDecisionForMeta(
        side: Side,
        meta: RowMeta,
        computedPosition: GroupPosition,
        mayRefreshTopDecision: Boolean,
        previous: RowMeta?
    ): GroupPosition {
        val stableKey = meta.stableKey
        val cached = bubbleDecisionCache[stableKey]
        if (cached != null && cached.side == side) {
            val canGrowWithNewNeighbor = ChatBubbleStylePolicy.shouldUpdateCachedPositionForNeighborGrowth(
                cachedPosition = cached.position,
                computedPosition = computedPosition
            )
            if (!canGrowWithNewNeighbor) {
                if (!cached.refreshableFromTop) {
                    return cached.position
                }
                val nextRefreshable = mayRefreshTopDecision && previous == null
                bubbleDecisionCache[stableKey] = BubbleDecision(
                    side = side,
                    position = computedPosition,
                    refreshableFromTop = nextRefreshable
                )
                return computedPosition
            }
            bubbleDecisionCache[stableKey] = BubbleDecision(
                side = side,
                position = computedPosition,
                refreshableFromTop = false
            )
            return computedPosition
        }

        bubbleDecisionCache[stableKey] = BubbleDecision(
            side = side,
            position = computedPosition,
            refreshableFromTop = mayRefreshTopDecision && previous == null
        )
        return computedPosition
    }

    private fun cachedDecisionForMeta(meta: RowMeta): BubbleDecision? {
        val side = meta.side ?: return null
        val cached = bubbleDecisionCache[meta.stableKey] ?: return null
        return cached.takeIf { it.side == side }
    }

    private fun setPendingDecision(itemView: View, decision: BubbleDecision) {
        XposedHelpers.setAdditionalInstanceField(itemView, keyPendingDecision, decision)
        findViewByResourceName(itemView, resourceMessage)?.let { messageView ->
            XposedHelpers.setAdditionalInstanceField(messageView, keyPendingDecision, decision)
        }
    }

    private fun readPendingDecision(view: View): BubbleDecision? {
        (XposedHelpers.getAdditionalInstanceField(view, keyPendingDecision) as? BubbleDecision)?.let {
            return it
        }
        val itemView = findItemRoot(view) ?: return null
        return XposedHelpers.getAdditionalInstanceField(itemView, keyPendingDecision) as? BubbleDecision
    }

    private fun clearPendingDecision(itemView: View?, msgView: View) {
        XposedHelpers.removeAdditionalInstanceField(msgView, keyPendingDecision)
        itemView?.let {
            XposedHelpers.removeAdditionalInstanceField(it, keyPendingDecision)
        }
    }

    private fun alignInfoWithMeta(info: TextItemInfo, meta: RowMeta?): TextItemInfo {
        val metaSide = meta?.side ?: return info
        if (metaSide == info.side) {
            return info
        }
        return info.copy(
            side = metaSide,
            senderKey = when (metaSide) {
                Side.RIGHT -> "self"
                Side.LEFT -> meta.senderKey ?: info.senderKey
            }
        )
    }

    private fun probeStyleApply(
        info: TextItemInfo,
        currentMeta: RowMeta?,
        boundNeighbors: BoundNeighbors?,
        position: GroupPosition
    ) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val key = "${info.shortText}:${info.side}:${currentMeta?.side}:$position:" +
                "${boundNeighbors?.requestPosition}:${boundNeighbors?.matchedPosition}"
        if (styleProbeLogs.size >= 120 || styleProbeLogs.contains(key)) {
            return
        }
        styleProbeLogs.add(key)
        RuntimeProbe.append(
            info.itemView.context,
            bubbleProbeFile,
            "ModernChatBubble style text=${info.shortText} visualSide=${info.side} " +
                    "metaSide=${currentMeta?.side} group=$position req=${boundNeighbors?.requestPosition} " +
                    "match=${boundNeighbors?.matchedPosition} source=${boundNeighbors?.sourceLabel} " +
                    "metaType=${currentMeta?.isTextMessage} metaTime=${currentMeta?.createTimeMs} " +
                    "metaText=${currentMeta?.contentText?.take(40)} " +
                    "prev=${boundNeighbors?.previous?.side}:${boundNeighbors?.previous?.isTextMessage}:" +
                    "${boundNeighbors?.previous?.createTimeMs}:${boundNeighbors?.previous?.contentText?.take(20)} " +
                    "next=${boundNeighbors?.next?.side}:${boundNeighbors?.next?.isTextMessage}:" +
                    "${boundNeighbors?.next?.createTimeMs}:${boundNeighbors?.next?.contentText?.take(20)} " +
                    "visibleTime=${info.hasTimeSeparator} " +
                    "timeCurrent=${isTimeSplit(boundNeighbors?.previous, currentMeta)} " +
                    "timeNext=${isTimeSplit(currentMeta, boundNeighbors?.next)}"
        )
    }

    private fun probeLayoutApply(
        info: TextItemInfo,
        currentMeta: RowMeta?,
        boundNeighbors: BoundNeighbors?,
        position: GroupPosition
    ) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        if (layoutProbeLogs.size >= 120) {
            return
        }
        val avatarContainer = info.avatarView?.parent as? View
        val bubbleRow = findViewByResourceName(info.itemView, "bkj")
        val messageParent = info.messageView.parent as? View
        val key = "${info.shortText}:${position}:${boundNeighbors?.matchedPosition}:" +
                "${info.itemView.height}:${bubbleRow?.height}:${messageParent?.height}:" +
                "${avatarContainer?.height}:${info.avatarView?.height}"
        if (layoutProbeLogs.contains(key)) {
            return
        }
        layoutProbeLogs.add(key)
        RuntimeProbe.append(
            info.itemView.context,
            bubbleProbeFile,
            "ModernChatBubble layout text=${info.shortText} group=$position side=${info.side} " +
                    "source=${boundNeighbors?.sourceLabel} match=${boundNeighbors?.matchedPosition} " +
                    "metaTime=${currentMeta?.createTimeMs} metaType=${currentMeta?.isTextMessage} " +
                    "item=${describeViewGeometry(info.itemView)} " +
                    "row=${describeViewGeometry(bubbleRow)} " +
                    "msgParent=${describeViewGeometry(messageParent)} " +
                    "msg=${describeViewGeometry(info.messageView)} " +
                    "avatarContainer=${describeViewGeometry(avatarContainer)} " +
                    "avatar=${describeViewGeometry(info.avatarView)} " +
                    "nick=${describeViewGeometry(info.nicknameView)}"
        )
    }

    private fun applyFallbackItem(itemView: View) {
        if (!hasStyledMessage(itemView)) {
            return
        }
        applyItem(itemView)
    }

    private fun probeAdapterOnce(itemView: View, adapter: Any, position: Int, sourceLabel: String? = null) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val key = adapter.javaClass.name
        if (adapterProbeLogs.contains(key)) {
            return
        }
        adapterProbeLogs.add(key)
        RuntimeProbe.append(
            itemView.context,
            bubbleProbeFile,
            AdapterMessageReader.describeAdapter(adapter, position, sourceLabel)
        )
        LogUtil.log("ModernChatBubble adapter probe adapter=${adapter.javaClass.name} position=$position source=${sourceLabel ?: "none"}")
    }

    private fun probeRenderLookupOnce(
        messageView: View,
        itemView: View,
        recycler: ViewGroup?,
        adapter: Any?,
        position: Int
    ) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val key = "render:${recycler?.javaClass?.name}:${adapter?.javaClass?.name}:$position"
        if (adapterProbeLogs.contains(key)) {
            return
        }
        adapterProbeLogs.add(key)
        RuntimeProbe.append(
            messageView.context,
            bubbleProbeFile,
            "ModernChatBubble render lookup item=${itemView.javaClass.name} recycler=${recycler?.javaClass?.name} " +
                    "adapter=${adapter?.javaClass?.name} position=$position text=${extractText(messageView)?.take(40)}"
        )
        LogUtil.log(
            "ModernChatBubble render lookup recycler=${recycler?.javaClass?.name} " +
                    "adapter=${adapter?.javaClass?.name} position=$position"
        )
    }

    fun probeDrawEntry(view: TextView, stage: String) {
        if (!shouldWriteVerboseProbe()) {
            return
        }
        val text = extractText(view)?.take(40) ?: ""
        val itemView = findItemRoot(view)
        val recyclerChild = itemView?.let { findRecyclerChild(it) }
        val recycler = recyclerChild?.parent as? ViewGroup
        val adapter = recycler?.let { getRecyclerAdapter(it) }
        val position = if (recycler != null && recyclerChild != null) {
            getChildAdapterPosition(recycler, recyclerChild)
        } else {
            -1
        }
        RuntimeProbe.append(
            view.context,
            bubbleProbeFile,
            "ModernChatBubble draw stage=$stage text=$text item=${itemView?.javaClass?.name} " +
                    "recycler=${recycler?.javaClass?.name} adapter=${adapter?.javaClass?.name} pos=$position " +
                    "known=${isKnownMessageView(view)}"
        )
        LogUtil.log(
            "ModernChatBubble draw stage=$stage recycler=${recycler?.javaClass?.name} " +
                    "adapter=${adapter?.javaClass?.name} pos=$position"
        )
    }

    private fun applyMessageVisuals(
        msgView: View,
        side: Side,
        position: GroupPosition,
        stableKey: String,
        itemView: View?,
        marginTarget: View,
        avatarView: View?,
        nicknameView: View?
    ) {
        XposedHelpers.setAdditionalInstanceField(msgView, keyCandidate, true)
        XposedHelpers.setAdditionalInstanceField(msgView, keyLastTextHash, textHash(msgView))
        val palette = ModernChatBubbleColors.palette(side)
        setTextColors(msgView, palette.textColor, palette.semanticTextColor)
        setBubbleBackground(msgView, side, position, stableKey, palette)

        val horizontal = dp(msgView, 13f)
        val vertical = dp(msgView, 8.5f)
        msgView.setPadding(horizontal, vertical, horizontal, vertical)
        applyFloatingShadow(msgView, position)
        disableAncestorClipping(msgView)
        setTopMargin(marginTarget, dp(msgView, ChatBubbleStylePolicy.topMarginDp(position)))

        setAvatarVisibility(avatarView, ChatBubbleStylePolicy.showAvatar(position))
        setNicknameVisibility(nicknameView, ChatBubbleStylePolicy.showNickname(side, position))
        tuneMessageRowHeight(itemView, msgView, avatarView, nicknameView, position)
        if (HookConfig.is_hook_chat_label_color && nicknameView != null) {
            setTextColors(nicknameView, HookConfig.chat_label_color, HookConfig.chat_label_color)
        }
        clearPendingDecision(itemView, msgView)
    }

    private fun tuneMessageRowHeight(
        itemView: View?,
        msgView: View,
        avatarView: View?,
        nicknameView: View?,
        position: GroupPosition
    ) {
        val rowRoot = itemView ?: findItemRoot(msgView) ?: return
        val bubbleRow = findViewByResourceName(rowRoot, "bkj")
        forceTopGravity(bubbleRow)
        if (position != GroupPosition.SINGLE) {
            clearDirectVerticalChildMargins(bubbleRow)
        }
        listOfNotNull(rowRoot, bubbleRow, msgView.parent as? View).forEach { target ->
            target.minimumHeight = 0
        }
    }

    private fun scheduleRowHeightCompact(
        rowRoot: View,
        msgView: View,
        avatarView: View?,
        nicknameView: View?,
        position: GroupPosition
    ) {
        if (XposedHelpers.getAdditionalInstanceField(rowRoot, keyPendingRowCompact) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(rowRoot, keyPendingRowCompact, true)
        rowRoot.post {
            XposedHelpers.removeAdditionalInstanceField(rowRoot, keyPendingRowCompact)
            val bubbleRow = findViewByResourceName(rowRoot, "bkj")
            forceTopGravity(bubbleRow)
            if (position != GroupPosition.SINGLE) {
                clearDirectVerticalChildMargins(bubbleRow)
            }
            compactRowHeight(rowRoot, bubbleRow, msgView, avatarView, nicknameView, position)
        }
    }

    private fun forceTopGravity(view: View?) {
        forceTopGravity(view, 0)
    }

    private fun forceTopGravity(view: View?, depth: Int) {
        val group = view as? ViewGroup ?: return
        if (group is LinearLayout) {
            val horizontal = group.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
            group.gravity = horizontal or Gravity.TOP
        }
        if (depth >= 3) {
            return
        }
        for (index in 0 until group.childCount) {
            forceTopGravity(group.getChildAt(index), depth + 1)
        }
    }

    private fun clearDirectVerticalChildMargins(view: View?) {
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
            child.requestLayout()
        }
    }

    private fun compactRowHeight(
        rowRoot: View,
        bubbleRow: View?,
        msgView: View,
        avatarView: View?,
        nicknameView: View?,
        position: GroupPosition
    ) {
        val msgBottom = bottomRelativeTo(rowRoot, msgView)
        if (msgBottom <= 0) {
            return
        }
        var desiredHeight = msgBottom
        if (ChatBubbleStylePolicy.showAvatar(position)) {
            val avatarContainer = avatarView?.parent as? View ?: avatarView
            desiredHeight = maxOf(desiredHeight, bottomRelativeTo(rowRoot, avatarContainer))
        }
        if (nicknameView?.visibility == View.VISIBLE) {
            desiredHeight = maxOf(desiredHeight, bottomRelativeTo(rowRoot, nicknameView))
        }
        desiredHeight = desiredHeight.coerceAtLeast(msgView.measuredHeight.takeIf { it > 0 } ?: msgView.height)
        setExactHeight(rowRoot, desiredHeight)

        if (bubbleRow != null) {
            val bubbleTop = topRelativeTo(rowRoot, bubbleRow).coerceAtLeast(0)
            setExactHeight(bubbleRow, (desiredHeight - bubbleTop).coerceAtLeast(0))
        }
        (rowRoot.parent as? View)?.requestLayout()
    }

    private fun setExactHeight(view: View, height: Int) {
        if (height <= 0) {
            return
        }
        val params = view.layoutParams ?: return
        if (params.height == height) {
            return
        }
        params.height = height
        view.layoutParams = params
        view.requestLayout()
    }

    private fun setAvatarVisibility(avatarView: View?, visible: Boolean) {
        avatarView ?: return
        val container = avatarView.parent as? View ?: avatarView
        val params = container.layoutParams as? ViewGroup.LayoutParams
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
            return
        }
        if (params != null && params.height != 0) {
            params.height = 0
            container.layoutParams = params
        }
        container.visibility = View.INVISIBLE
        avatarView.visibility = View.INVISIBLE
    }

    private fun setNicknameVisibility(nicknameView: View?, visible: Boolean) {
        nicknameView ?: return
        val hasText = !extractText(nicknameView).isNullOrBlank()
        nicknameView.visibility = if (visible && hasText) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun setBubbleBackground(
        msgView: View,
        side: Side,
        position: GroupPosition,
        stableKey: String,
        palette: ChatBubbleStylePolicy.BubblePalette
    ) {
        XposedHelpers.setAdditionalInstanceField(msgView, keyLastSide, side)
        XposedHelpers.setAdditionalInstanceField(msgView, keyLastPosition, position)
        XposedHelpers.setAdditionalInstanceField(msgView, keyReplacingBackground, true)
        try {
            val state = renderState(stableKey, side, position)
            val existingBubble = msgView.background as? ModernBubbleDrawable
            val canUpdateExisting = existingBubble != null &&
                    existingBubble.stableKey == stableKey &&
                    existingBubble.side == side
            if (canUpdateExisting) {
                existingBubble?.update(
                    nextState = state,
                    nextPalette = palette,
                    animateCorners = existingBubble.position != position
                )
            } else {
                msgView.background = ModernBubbleDrawable(msgView.context, state, palette)
            }
        } finally {
            XposedHelpers.removeAdditionalInstanceField(msgView, keyReplacingBackground)
        }
    }

    private fun createBubbleDrawable(
        view: View,
        side: Side,
        position: GroupPosition,
        stableKey: String
    ): Drawable {
        val palette = ModernChatBubbleColors.palette(side)
        return ModernBubbleDrawable(view.context, renderState(stableKey, side, position), palette)
    }

    private fun renderState(
        stableKey: String,
        side: Side,
        position: GroupPosition
    ): ChatBubbleStylePolicy.RenderState {
        return ChatBubbleStylePolicy.RenderState(
            stableKey = stableKey,
            side = side,
            position = position,
            showAvatar = ChatBubbleStylePolicy.showAvatar(position),
            showNickname = ChatBubbleStylePolicy.showNickname(side, position),
            topMarginDp = ChatBubbleStylePolicy.topMarginDp(position),
            cornerRadii = ChatBubbleStylePolicy.cornerRadii(side, position)
        )
    }

    private fun visualStableKey(msgView: View, itemView: View?): String {
        val existingBubble = msgView.background as? ModernBubbleDrawable
        if (existingBubble != null) {
            return existingBubble.stableKey
        }
        val item = itemView ?: findItemRoot(msgView)
        val side = XposedHelpers.getAdditionalInstanceField(msgView, keyLastSide) as? Side
        val text = extractText(msgView)?.hashCode() ?: 0
        return "visual:${System.identityHashCode(item ?: msgView)}:${side ?: "unknown"}:$text"
    }

    private fun createBubbleShape(
        view: View,
        side: Side,
        position: GroupPosition,
        palette: ChatBubbleStylePolicy.BubblePalette,
        color: Int
    ): GradientDrawable {
        val radii = ChatBubbleStylePolicy.cornerRadii(side, position)
        val topLeft = dp(view, radii.topLeft).toFloat()
        val topRight = dp(view, radii.topRight).toFloat()
        val bottomRight = dp(view, radii.bottomRight).toFloat()
        val bottomLeft = dp(view, radii.bottomLeft).toFloat()

        val topColor = lightenColor(color, if (side == Side.RIGHT) 1.045f else 1.02f)
        val bottomColor = darkenColor(color, if (side == Side.RIGHT) 0.985f else 0.995f)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, color, bottomColor)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                topLeft,
                topLeft,
                topRight,
                topRight,
                bottomRight,
                bottomRight,
                bottomLeft,
                bottomLeft
            )
            if (palette.strokeWidthDp > 0f && Color.alpha(palette.strokeColor) > 0) {
                setStroke(dp(view, palette.strokeWidthDp), palette.strokeColor)
            }
        }
    }

    private fun applyFloatingShadow(view: View, position: GroupPosition) {
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
                val radius = if (position == GroupPosition.SINGLE) 24f else 20f
                outline.setRoundRect(0, 0, target.width, target.height, dp(target, radius).toFloat())
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.outlineAmbientShadowColor = shadowColor
            view.outlineSpotShadowColor = shadowColor
        }
    }

    private fun setTextColors(view: View, color: Int, semanticColor: Int) {
        if (view is TextView) {
            view.setTextColor(color)
            view.setLinkTextColor(semanticColor)
            view.setHintTextColor(color)
            SemanticTextColorizer.apply(view, semanticColor)
        }
        callColorMethod(view, "setTextColor", color)
        callColorMethod(view, "setLinkTextColor", semanticColor)
        callColorMethod(view, "setHintTextColor", color)
        SemanticTextColorizer.apply(view, semanticColor)
    }

    private fun callColorMethod(view: View, methodName: String, color: Int) {
        try {
            XposedHelpers.callMethod(view, methodName, color)
        } catch (_: Throwable) {
        }
    }

    private fun isTimeSplit(older: RowMeta?, newer: RowMeta?): Boolean {
        val olderTime = older?.createTimeMs ?: return false
        val newerTime = newer?.createTimeMs ?: return false
        return kotlin.math.abs(newerTime - olderTime) >= timeSeparatorGapMs
    }

    private fun findItemRoot(view: View): View? {
        val directRoot = findItemRootByResource(view)
        if (directRoot != null && findViewByResourceName(directRoot, resourceMessage) != null) {
            return directRoot
        }
        val nestedRoot = findViewByResourceName(view, resourceItemRoot)
        if (nestedRoot != null && findViewByResourceName(nestedRoot, resourceMessage) != null) {
            return nestedRoot
        }
        var current: View? = view
        var depth = 0
        while (current != null && depth < 8) {
            val root = findItemRootByResource(current)
            if (root != null && findViewByResourceName(root, resourceMessage) != null) {
                return root
            }
            val parent = current.parent
            current = parent as? View
            depth++
        }
        return null
    }

    private fun findItemRootByResource(view: View): View? {
        if (getResourceEntryName(view) == resourceItemRoot) {
            return view
        }
        findViewByResourceName(view, resourceItemRoot)?.let {
            return it
        }
        var current = view.parent as? View
        var depth = 0
        while (current != null && depth < 8) {
            if (getResourceEntryName(current) == resourceItemRoot) {
                return current
            }
            if (!isRecyclerViewLike(current)) {
                findViewByResourceName(current, resourceItemRoot)?.let {
                    return it
                }
            }
            current = current.parent as? View
            depth++
        }
        return null
    }

    private fun findRecyclerChild(itemView: View): View? {
        var child: View = itemView
        var parent = child.parent
        var depth = 0
        while (parent is ViewGroup && depth < 12) {
            if (isRecyclerViewLike(parent)) {
                return child
            }
            child = parent
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun getRecyclerAdapter(recycler: ViewGroup): Any? {
        return try {
            XposedHelpers.callMethod(recycler, "getAdapter")
        } catch (_: Throwable) {
            null
        }
    }

    private fun getChildAdapterPosition(recycler: ViewGroup, child: View): Int {
        listOf("getChildAdapterPosition", "getChildLayoutPosition").forEach { methodName ->
            val value = try {
                XposedHelpers.callMethod(recycler, methodName, child) as? Int
            } catch (_: Throwable) {
                null
            }
            if (value != null && value >= 0) {
                return value
            }
        }
        val holder = listOf("getChildViewHolder", "findContainingViewHolder")
            .asSequence()
            .mapNotNull { methodName ->
                try {
                    XposedHelpers.callMethod(recycler, methodName, child)
                } catch (_: Throwable) {
                    null
                }
            }
            .firstOrNull()
        val holderPosition = readAdapterPositionFromHolder(holder)
        if (holderPosition >= 0) {
            return holderPosition
        }
        return -1
    }

    private fun readAdapterPositionFromHolder(holder: Any?): Int {
        holder ?: return -1
        listOf("getAdapterPosition", "getLayoutPosition", "getBindingAdapterPosition").forEach { methodName ->
            val value = try {
                XposedHelpers.callMethod(holder, methodName) as? Int
            } catch (_: Throwable) {
                null
            }
            if (value != null && value >= 0) {
                return value
            }
        }
        return try {
            XposedHelpers.getIntField(holder, "a").takeIf { it >= 0 } ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun isRecyclerViewLike(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("RecyclerView") || name.contains("WxRecyclerView")
    }

    private fun readTextItemInfo(view: View, fallbackSide: Side? = null): TextItemInfo? {
        val itemView = findItemRootByResource(view) ?: return null
        return readDirectTextItemInfo(itemView, fallbackSide)
    }

    private fun readDirectTextItemInfo(itemView: View, fallbackSide: Side? = null): TextItemInfo? {
        val messageView = findViewByResourceName(itemView, resourceMessage) ?: return null
        val avatarView = findViewByResourceName(itemView, resourceAvatar)
        val side = resolveSide(itemView, messageView, avatarView) ?: fallbackSide ?: return null
        val nicknameView = findNicknameView(itemView)
        val hasTimeSeparator = findTimeSeparatorView(itemView) != null
        val senderKey = resolveSenderKey(itemView, side, nicknameView, avatarView)
        val visibleText = extractText(messageView)
        XposedHelpers.setAdditionalInstanceField(itemView, keyItemSide, side)
        if (side == Side.LEFT && senderKey != null) {
            XposedHelpers.setAdditionalInstanceField(itemView, keySender, senderKey)
        }
        return TextItemInfo(
            itemView = itemView,
            messageView = messageView,
            avatarView = avatarView,
            nicknameView = nicknameView,
            side = side,
            senderKey = senderKey,
            hasTimeSeparator = hasTimeSeparator,
            visibleText = visibleText,
            shortText = visibleText?.take(20) ?: ""
        )
    }

    private fun resolveSide(itemView: View, messageView: View, avatarView: View?): Side? {
        resolveSideFromMessage(itemView, messageView)?.let {
            return it
        }
        (XposedHelpers.getAdditionalInstanceField(messageView, keyLastSide) as? Side)?.let {
            return it
        }
        (XposedHelpers.getAdditionalInstanceField(itemView, keyItemSide) as? Side)?.let {
            return it
        }
        avatarView ?: return null
        if (itemView.width <= 0 || avatarView.width <= 0) {
            return null
        }
        val itemLocation = IntArray(2)
        val avatarLocation = IntArray(2)
        itemView.getLocationOnScreen(itemLocation)
        avatarView.getLocationOnScreen(avatarLocation)

        val itemCenterX = itemLocation[0] + itemView.width / 2
        val avatarCenterX = avatarLocation[0] + avatarView.width / 2
        return if (avatarCenterX > itemCenterX) Side.RIGHT else Side.LEFT
    }

    private fun resolveSideFromMessage(itemView: View, messageView: View): Side? {
        if (itemView.width <= 0 || messageView.width <= 0) {
            return null
        }
        val itemLocation = IntArray(2)
        val messageLocation = IntArray(2)
        itemView.getLocationOnScreen(itemLocation)
        messageView.getLocationOnScreen(messageLocation)

        val itemCenterX = itemLocation[0] + itemView.width / 2
        val messageCenterX = messageLocation[0] + messageView.width / 2
        return if (messageCenterX > itemCenterX) Side.RIGHT else Side.LEFT
    }

    private fun resolveSenderKey(
        itemView: View,
        side: Side,
        nicknameView: View?,
        avatarView: View?
    ): String? {
        if (side == Side.RIGHT) {
            return "self"
        }
        val nick = extractText(nicknameView)
        if (!nick.isNullOrBlank()) {
            return nick
        }
        val avatarDescription = avatarView?.contentDescription?.toString()
        if (!avatarDescription.isNullOrBlank()) {
            return avatarDescription
        }
        return XposedHelpers.getAdditionalInstanceField(itemView, keySender) as? String
    }

    private fun findNicknameView(itemView: View): View? {
        findViewByResourceName(itemView, resourceNicknameModern)?.let { view ->
            if (!looksLikeTimeSeparator(extractText(view))) {
                return view
            }
        }
        findViewByResourceName(itemView, resourceNickname)?.let { view ->
            if (!looksLikeTimeSeparator(extractText(view))) {
                return view
            }
        }
        return null
    }

    private fun findViewByResourceName(root: View, resourceName: String): View? {
        val id = try {
            root.resources.getIdentifier(resourceName, "id", root.context.packageName)
        } catch (_: Throwable) {
            0
        }
        if (id == 0) {
            return null
        }
        return root.findViewById(id)
    }

    private fun findTimeSeparatorView(root: View): View? {
        val id = try {
            root.resources.getIdentifier(resourceTimeLabel, "id", root.context.packageName)
        } catch (_: Throwable) {
            0
        }
        val itemRoot = when {
            getResourceEntryName(root) == resourceItemRoot -> root
            else -> findItemRootByResource(root) ?: root
        }
        val group = itemRoot as? ViewGroup
        if (id == 0 || group == null) {
            return null
        }
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.id == id &&
                isRenderableTimeSeparatorView(child) &&
                looksLikeTimeSeparator(extractText(child))
            ) {
                return child
            }
        }
        return null
    }

    private fun isRenderableTimeSeparatorView(view: View): Boolean {
        if (view.visibility != View.VISIBLE || view.alpha <= 0.01f) {
            return false
        }
        val params = view.layoutParams
        if (params?.width == 0 || params?.height == 0) {
            return false
        }
        if (view.width > 0 && view.height > 0) {
            return true
        }
        if (view.measuredWidth > 0 && view.measuredHeight > 0) {
            return true
        }
        return params == null ||
                params.width == ViewGroup.LayoutParams.WRAP_CONTENT ||
                params.width == ViewGroup.LayoutParams.MATCH_PARENT ||
                params.height == ViewGroup.LayoutParams.WRAP_CONTENT ||
                params.height == ViewGroup.LayoutParams.MATCH_PARENT
    }

    private fun looksLikeTimeSeparator(text: String?): Boolean {
        val value = text?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return value.matches(Regex("\\d{1,2}:\\d{2}")) ||
                value.contains("昨天") ||
                value.contains("星期") ||
                value.matches(Regex(".*周[一二三四五六日天].*")) ||
                value.matches(Regex(".*\\d{2,4}年\\d{1,2}月\\d{1,2}日?.*")) ||
                value.matches(Regex(".*\\d{1,2}月\\d{1,2}日?.*"))
    }

    private fun getResourceEntryName(view: View): String? {
        if (view.id == View.NO_ID) {
            return null
        }
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun describeViewGeometry(view: View?): String {
        view ?: return "null"
        return buildString {
            append(view.javaClass.simpleName)
            append(":")
            append(getResourceEntryName(view))
            append(" vis=")
            append(visibilityName(view.visibility))
            append(" frame=")
            append(view.left)
            append(",")
            append(view.top)
            append(" ")
            append(view.width)
            append("x")
            append(view.height)
            append(" measured=")
            append(view.measuredWidth)
            append("x")
            append(view.measuredHeight)
            append(" minH=")
            append(view.minimumHeight)
            append(" pad=")
            append(view.paddingLeft)
            append(",")
            append(view.paddingTop)
            append(",")
            append(view.paddingRight)
            append(",")
            append(view.paddingBottom)
            append(" lp=")
            append(describeLayoutParams(view.layoutParams))
        }
    }

    private fun visibilityName(visibility: Int): String {
        return when (visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> visibility.toString()
        }
    }

    private fun describeLayoutParams(params: ViewGroup.LayoutParams?): String {
        params ?: return "null"
        val base = "${params.javaClass.simpleName}{${params.width}x${params.height}"
        val margins = params as? ViewGroup.MarginLayoutParams
        return if (margins == null) {
            "$base}"
        } else {
            "$base m=${margins.leftMargin},${margins.topMargin},${margins.rightMargin},${margins.bottomMargin}}"
        }
    }

    private fun topRelativeTo(ancestor: View, child: View?): Int {
        child ?: return 0
        if (child.width <= 0 && child.height <= 0) {
            return 0
        }
        val ancestorLocation = IntArray(2)
        val childLocation = IntArray(2)
        return try {
            ancestor.getLocationOnScreen(ancestorLocation)
            child.getLocationOnScreen(childLocation)
            childLocation[1] - ancestorLocation[1]
        } catch (_: Throwable) {
            0
        }
    }

    private fun bottomRelativeTo(ancestor: View, child: View?): Int {
        child ?: return 0
        val height = child.height.takeIf { it > 0 } ?: child.measuredHeight
        if (height <= 0 || child.visibility == View.GONE) {
            return 0
        }
        return topRelativeTo(ancestor, child) + height
    }

    private fun diagnoseRejectedItem(itemView: View) {
        if (diagnosticLogs.size >= 40) {
            return
        }
        val signature = "${itemView.javaClass.name}#${getResourceEntryName(itemView)}"
        if (diagnosticLogs.contains(signature)) {
            return
        }
        diagnosticLogs.add(signature)
        val childCount = (itemView as? ViewGroup)?.childCount ?: -1
        val hasMessage = findViewByResourceName(itemView, resourceMessage) != null
        val hasAvatar = findViewByResourceName(itemView, resourceAvatar) != null
        val hasNickname = findViewByResourceName(itemView, resourceNicknameModern) != null ||
                findViewByResourceName(itemView, resourceNickname) != null
        val parentView = itemView.parent as? View
        LogUtil.log(
            "ModernChatBubble reject class=${itemView.javaClass.name} res=${getResourceEntryName(itemView)} " +
                    "children=$childCount hasBkl=$hasMessage hasBk1=$hasAvatar hasBr1=$hasNickname " +
                    "parent=${parentView?.javaClass?.name} parentRes=${parentView?.let { getResourceEntryName(it) }}"
        )
    }

    private fun logDiagnosticOnce(key: String, message: String) {
        if (diagnosticLogs.contains(key)) {
            return
        }
        diagnosticLogs.add(key)
        LogUtil.log(message)
    }

    private fun extractText(view: View?): String? {
        view ?: return null
        if (view is TextView) {
            return view.text?.toString()
        }
        try {
            val value = XposedHelpers.callMethod(view, "getText")
            if (value != null) {
                return value.toString()
            }
        } catch (_: Throwable) {
        }
        try {
            val nodeInfo = AccessibilityNodeInfo.obtain()
            try {
                view.onInitializeAccessibilityNodeInfo(nodeInfo)
                val text = nodeInfo.text?.toString()
                if (!text.isNullOrBlank()) {
                    return text
                }
            } finally {
                nodeInfo.recycle()
            }
        } catch (_: Throwable) {
        }
        val description = view.contentDescription?.toString()
        if (!description.isNullOrBlank()) {
            return description
        }
        return try {
            XposedHelpers.getObjectField(view, "mText")?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun textHash(view: View): Int {
        return extractText(view)?.hashCode() ?: 0
    }

    private fun normalizedTextForMatch(text: String?): String {
        return text
            ?.replace('\u00A0', ' ')
            ?.filterNot { it.isWhitespace() }
            ?.trim()
            ?: ""
    }

    private fun setTopMargin(view: View, topMargin: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin == topMargin) {
            return
        }
        params.topMargin = topMargin
        view.layoutParams = params
    }

    private fun disableAncestorClipping(view: View) {
        var parent = view.parent
        var depth = 0
        while (parent is ViewGroup && depth < 5) {
            parent.clipChildren = false
            parent.clipToPadding = false
            parent = parent.parent
            depth++
        }
    }

    private fun dp(view: View, value: Float): Int {
        return (value * view.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private object AdapterMessageReader {
        private val sourceCache = Collections.synchronizedMap(WeakHashMap<Any, DataSource?>())
        private val metaCache = Collections.synchronizedMap(WeakHashMap<Any, RowMeta?>())
        private val indexCache = Collections.synchronizedMap(WeakHashMap<Any, IndexedMessages>())

        fun readMeta(value: Any): RowMeta? {
            return extractRowMeta(value)
        }

        fun invalidate(adapter: Any) {
            sourceCache.remove(adapter)
            indexCache.remove(adapter)
        }

        fun readWindow(
            adapter: Any,
            startPosition: Int,
            endPosition: Int,
            anchorPosition: Int
        ): MessageWindow? {
            if (anchorPosition < 0 || endPosition < 0) {
                return null
            }
            val source = cachedSource(adapter, anchorPosition) ?: return null
            val safeStart = maxOf(0, startPosition)
            if (endPosition < safeStart) {
                return null
            }
            val entries = mutableListOf<WindowEntry>()
            for (position in safeStart..endPosition) {
                val meta = source.get(adapter, position)?.let { extractRowMeta(it) } ?: continue
                entries += WindowEntry(position, meta)
            }
            if (entries.isEmpty()) {
                return null
            }
            return MessageWindow(
                sourceLabel = source.label,
                entries = entries,
                byPosition = entries.associateBy { it.position },
                byStableKey = entries.associateBy { it.meta.stableKey }
            )
        }

        fun readWindowForBoundStableKeys(
            adapter: Any,
            stableKeys: List<String>,
            padding: Int
        ): MessageWindow? {
            if (stableKeys.isEmpty()) {
                return null
            }
            val indexed = readIndexedMessages(adapter) ?: return null
            buildWindowAroundStableKeys(indexed, stableKeys, padding)?.let { return it }
            indexCache.remove(adapter)
            val refreshed = readIndexedMessages(adapter) ?: return null
            return buildWindowAroundStableKeys(refreshed, stableKeys, padding)
        }

        fun readWindowForVisibleItems(
            adapter: Any,
            visibleItems: List<VisibleTextItem>,
            padding: Int
        ): MessageWindow? {
            val indexed = readIndexedMessages(adapter) ?: return null
            val targetTexts = visibleItems
                .map { normalizedTextForMatch(it.info.visibleText) }
                .filter { it.isNotBlank() }
                .distinct()
            if (targetTexts.isEmpty()) {
                return null
            }
            val candidatePositions = indexed.entries
                .asSequence()
                .filter { entry -> entry.meta.isTextMessage }
                .filter { entry ->
                    targetTexts.any { text ->
                        val content = normalizedTextForMatch(entry.meta.contentText)
                        content.isNotBlank() && (content.contains(text) || text.contains(content))
                    }
                }
                .map { it.position }
                .toList()
            if (candidatePositions.isEmpty()) {
                return null
            }
            val start = maxOf(indexed.firstPosition, (candidatePositions.minOrNull() ?: return null) - padding)
            val end = minOf(indexed.lastPosition, (candidatePositions.maxOrNull() ?: return null) + padding)
            val entries = indexed.entries.filter { it.position in start..end }
            if (entries.isEmpty()) {
                return null
            }
            return MessageWindow(
                sourceLabel = "${indexed.sourceLabel}.indexed",
                entries = entries,
                byPosition = entries.associateBy { it.position },
                byStableKey = entries.associateBy { it.meta.stableKey }
            )
        }

        private fun buildWindowAroundStableKeys(
            indexed: IndexedMessages,
            stableKeys: List<String>,
            padding: Int
        ): MessageWindow? {
            val positions = stableKeys
                .mapNotNull { indexed.byStableKey[it]?.position }
                .distinct()
            if (positions.isEmpty()) {
                return null
            }
            val start = maxOf(indexed.firstPosition, (positions.minOrNull() ?: return null) - padding)
            val end = minOf(indexed.lastPosition, (positions.maxOrNull() ?: return null) + padding)
            val entries = indexed.entries.filter { it.position in start..end }
            if (entries.isEmpty()) {
                return null
            }
            return MessageWindow(
                sourceLabel = "${indexed.sourceLabel}.bound",
                entries = entries,
                byPosition = entries.associateBy { it.position },
                byStableKey = entries.associateBy { it.meta.stableKey }
            )
        }

        fun readNeighbors(adapter: Any, position: Int, visibleText: String? = null): BoundNeighbors? {
            if (position < 0) {
                return null
            }
            val source = cachedSource(adapter, position) ?: return null
            val matchedPosition = findMatchingPosition(source, adapter, position, visibleText)
            val current = source.get(adapter, matchedPosition)?.let { extractRowMeta(it) } ?: return null
            val previous = source.get(adapter, matchedPosition - 1)?.let { extractRowMeta(it) }
            val next = source.get(adapter, matchedPosition + 1)?.let { extractRowMeta(it) }
            val label = if (matchedPosition == position) {
                source.label
            } else {
                "${source.label}[$position->$matchedPosition]"
            }
            return BoundNeighbors(
                current = current,
                previous = previous,
                next = next,
                sourceLabel = label,
                requestPosition = position,
                matchedPosition = matchedPosition
            )
        }

        private fun findMatchingPosition(
            source: DataSource,
            adapter: Any,
            position: Int,
            visibleText: String?
        ): Int {
            val current = source.get(adapter, position)?.let { extractRowMeta(it) }
            if (current == null || matchesVisibleText(current, visibleText)) {
                return position
            }
            val offsets = listOf(-1, 1, -2, 2, -3, 3, -4, 4, -5, 5)
            for (offset in offsets) {
                val candidatePosition = position + offset
                if (candidatePosition < 0) {
                    continue
                }
                val candidate = source.get(adapter, candidatePosition)?.let { extractRowMeta(it) } ?: continue
                if (matchesVisibleText(candidate, visibleText)) {
                    return candidatePosition
                }
            }
            return position
        }

        private fun matchesVisibleText(meta: RowMeta, visibleText: String?): Boolean {
            val visible = normalizeTextForMatch(visibleText)
            if (visible.isBlank()) {
                return true
            }
            val content = normalizeTextForMatch(meta.contentText)
            if (content.isBlank()) {
                return false
            }
            return content.contains(visible) || visible.contains(content)
        }

        private fun cachedSource(adapter: Any, position: Int): DataSource? {
            sourceCache[adapter]?.let {
                return it
            }
            val source = findDataSource(adapter, position)
            if (source != null) {
                sourceCache[adapter] = source
            }
            return source
        }

        private fun cachedSourceAny(adapter: Any): DataSource? {
            sourceCache[adapter]?.let {
                return it
            }
            val count = readItemCount(adapter)
            val probes = buildList {
                add(0)
                if (count != null && count > 0) {
                    add(count - 1)
                    add(count / 2)
                }
                for (position in 1..40) {
                    add(position)
                }
            }.filter { it >= 0 }.distinct()
            for (position in probes) {
                findDataSource(adapter, position)?.let { source ->
                    sourceCache[adapter] = source
                    return source
                }
            }
            return null
        }

        private fun readIndexedMessages(adapter: Any): IndexedMessages? {
            val count = readItemCount(adapter) ?: return null
            val source = cachedSourceAny(adapter) ?: return null
            indexCache[adapter]?.let { cached ->
                if (cached.count == count && cached.sourceLabel == source.label) {
                    return cached
                }
            }
            val maxIndexedMessages = 5000
            val start = if (count > maxIndexedMessages) count - maxIndexedMessages else 0
            val entries = mutableListOf<WindowEntry>()
            for (position in start until count) {
                val meta = source.get(adapter, position)?.let { extractRowMeta(it) } ?: continue
                entries += WindowEntry(position, meta)
            }
            if (entries.isEmpty()) {
                return null
            }
            val indexed = IndexedMessages(
                count = count,
                sourceLabel = source.label,
                firstPosition = entries.first().position,
                lastPosition = entries.last().position,
                entries = entries,
                byStableKey = entries.associateBy { it.meta.stableKey }
            )
            indexCache[adapter] = indexed
            return indexed
        }

        private fun readItemCount(adapter: Any): Int? {
            val methodNames = setOf("getItemCount", "getCount", "size")
            for (method in allMethods(adapter.javaClass)) {
                if (Modifier.isStatic(method.modifiers) ||
                    method.parameterTypes.isNotEmpty() ||
                    method.name !in methodNames
                ) {
                    continue
                }
                val value = try {
                    method.isAccessible = true
                    method.invoke(adapter)
                } catch (_: Throwable) {
                    null
                }
                if (value == null) {
                    continue
                }
                asInt(value)?.let { count ->
                    if (count >= 0) {
                        return count
                    }
                }
            }
            return null
        }

        private fun findDataSource(adapter: Any, position: Int): DataSource? {
            val itemAccessorNames = setOf("X", "getItem", "getItemOrNull", "getDataItem", "getItemAt")
            val methods = allMethods(adapter.javaClass)
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                            method.name in itemAccessorNames &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] in listOf(Int::class.javaPrimitiveType, Integer::class.java) &&
                            method.returnType != Void.TYPE
                }
                .sortedBy { method ->
                    when (method.name) {
                        "X" -> 0
                        "getItem" -> 0
                        "getItemOrNull" -> 1
                        "getDataItem" -> 2
                        "getItemAt" -> 3
                        else -> 10
                    }
                }
            for (method in methods) {
                val source = MethodDataSource(method)
                if (source.hasMessageAt(adapter, position)) {
                    return source
                }
            }

            val collectionAccessorNames = setOf("getCurrentList", "getDataList", "getItems", "getList", "getData")
            val collectionMethods = allMethods(adapter.javaClass)
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                            (method.name in collectionAccessorNames || isCollectionReturn(method.returnType)) &&
                            method.parameterTypes.isEmpty() &&
                            method.returnType != Void.TYPE
                }
                .sortedBy { method ->
                    when (method.name) {
                        "getCurrentList" -> 0
                        "getDataList" -> 1
                        "getItems" -> 2
                        "getList" -> 3
                        "getData" -> 4
                        else -> 10
                    }
                }
            for (method in collectionMethods) {
                val value = invokeNoArg(method, adapter) ?: continue
                createCollectionSource("method:${method.name}", method, null, value)?.let { source ->
                    if (source.hasMessageAt(adapter, position)) {
                        return source
                    }
                }
                if (shouldInspectNestedObject(value)) {
                    for (field in allFields(value.javaClass)) {
                        val nestedValue = readField(field, value) ?: continue
                        createCollectionSource(
                            "method:${method.name}.${field.name}",
                            method,
                            field,
                            nestedValue
                        )?.let { source ->
                            if (source.hasMessageAt(adapter, position)) {
                                return source
                            }
                        }
                    }
                }
            }

            for (field in allFields(adapter.javaClass)) {
                val value = readField(field, adapter) ?: continue
                createCollectionSource("field:${field.name}", field, null, value)?.let { source ->
                    if (source.hasMessageAt(adapter, position)) {
                        return source
                    }
                }
                if (shouldInspectNestedObject(value)) {
                    for (nestedField in allFields(value.javaClass)) {
                        val nestedValue = readField(nestedField, value) ?: continue
                        createCollectionSource(
                            "field:${field.name}.${nestedField.name}",
                            field,
                            nestedField,
                            nestedValue
                        )?.let { source ->
                            if (source.hasMessageAt(adapter, position)) {
                                return source
                            }
                        }
                    }
                }
            }
            return null
        }

        private fun isCollectionReturn(type: Class<*>): Boolean {
            return Collection::class.java.isAssignableFrom(type) ||
                    type.isArray
        }

        private fun createCollectionSource(
            label: String,
            outer: AccessibleMember,
            innerField: Field?,
            value: Any
        ): DataSource? {
            return when (value) {
                is List<*> -> ListDataSource(label, outer, innerField)
                is Collection<*> -> CollectionDataSource(label, outer, innerField)
                is Array<*> -> ArrayDataSource(label, outer, innerField)
                else -> null
            }
        }

        private fun createCollectionSource(
            label: String,
            outerField: Field,
            innerField: Field?,
            value: Any
        ): DataSource? {
            return createCollectionSource(label, FieldMember(outerField), innerField, value)
        }

        private fun createCollectionSource(
            label: String,
            outerMethod: Method,
            innerField: Field?,
            value: Any
        ): DataSource? {
            return createCollectionSource(label, MethodMember(outerMethod), innerField, value)
        }

        private fun DataSource.hasMessageAt(adapter: Any, position: Int): Boolean {
            return get(adapter, position)?.let { extractRowMeta(it) } != null
        }

        private fun extractRowMeta(value: Any): RowMeta? {
            if (metaCache.containsKey(value)) {
                return metaCache[value]
            }
            val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            val meta = extractRowMeta(value, 0, seen)
            metaCache[value] = meta
            return meta
        }

        private fun extractRowMeta(value: Any?, depth: Int, seen: MutableSet<Any>): RowMeta? {
            value ?: return null
            if (!seen.add(value) || value is CharSequence || value is Number || value is Boolean) {
                return null
            }
            readDirectMeta(value)?.let {
                return it
            }
            if (depth >= 3) {
                return null
            }
            for (field in allFields(value.javaClass)) {
                val child = readField(field, value) ?: continue
                if (!shouldInspectNestedObject(child)) {
                    continue
                }
                extractRowMeta(child, depth + 1, seen)?.let {
                    return it
                }
            }
            return null
        }

        private fun readDirectMeta(value: Any): RowMeta? {
            val msgId = readLong(value, "field_msgId", "msgId", "msgID", "msgid", "getMsgId")
            val msgSvrId = readLong(value, "field_msgSvrId", "msgSvrId", "msgServerId", "msgsvrid")
            val createTime = readLong(
                value,
                "field_createTime",
                "createTime",
                "msgCreateTime",
                "timestamp",
                "time",
                "create_time",
                "getCreateTime"
            )
            val type = readInt(value, "field_type", "type", "msgType", "msg_type", "getType")
            val isSend = readBoolean(value, "field_isSend", "isSend", "isSent", "fromSelf", "self", "o0")
            val talker = readString(
                value,
                "field_talker",
                "talker",
                "username",
                "userName",
                "fromUser",
                "sender",
                "senderUsername",
                "A1"
            )
            val content = readString(value, "field_content", "content", "msgContent", "body", "text", "getContent", "y0")
            val signalCount = listOf(msgId, msgSvrId, createTime, type, isSend, talker, content).count { it != null }
            if (signalCount < 3 || (content == null && type == null && msgId == null && msgSvrId == null)) {
                return null
            }

            val side = when (isSend) {
                true -> Side.RIGHT
                false -> Side.LEFT
                null -> null
            }
            val senderKey = ChatBubbleStylePolicy.senderKeyForGrouping(side, talker, content)
            val stableKey = when {
                msgId != null && msgId > 0 -> "msg:$msgId"
                msgSvrId != null && msgSvrId > 0 -> "svr:$msgSvrId"
                else -> "${createTime ?: 0}:${side ?: "unknown"}:${senderKey ?: ""}:${content?.hashCode() ?: 0}"
            }
            val normalizedContent = normalizeMessageContent(content)
            return RowMeta(
                isTextMessage = ChatBubbleStylePolicy.isStylableWechatBubbleMessage(type, content),
                side = side,
                senderKey = senderKey,
                createTimeMs = createTime,
                stableKey = stableKey,
                contentText = normalizedContent,
                groupKey = ChatBubbleStylePolicy.groupKeyForWechatMessage(type, content)
            )
        }

        private fun readLong(target: Any, vararg names: String): Long? {
            return readNamedValue(target, names.toSet())?.let { asLong(it) }
        }

        private fun readInt(target: Any, vararg names: String): Int? {
            return readNamedValue(target, names.toSet())?.let { asInt(it) }
        }

        private fun readBoolean(target: Any, vararg names: String): Boolean? {
            return readNamedValue(target, names.toSet())?.let { asBoolean(it) }
        }

        private fun readString(target: Any, vararg names: String): String? {
            return readNamedValue(target, names.toSet())?.let { value ->
                (value as? CharSequence)?.toString()
            }
        }

        private fun readNamedValue(target: Any, names: Set<String>): Any? {
            for (field in allFields(target.javaClass)) {
                if (field.name in names) {
                    readField(field, target)?.let {
                        return it
                    }
                }
            }
            for (method in allMethods(target.javaClass)) {
                if (method.name !in names || Modifier.isStatic(method.modifiers) || method.parameterTypes.isNotEmpty()) {
                    continue
                }
                try {
                    method.isAccessible = true
                    method.invoke(target)?.let {
                        return it
                    }
                } catch (_: Throwable) {
                }
            }
            return null
        }

        fun describeAdapter(adapter: Any, position: Int, sourceLabel: String?): String {
            val builder = StringBuilder()
            builder.append("ModernChatBubble adapter probe class=")
                .append(adapter.javaClass.name)
                .append(" super=")
                .append(adapter.javaClass.superclass?.name)
                .append(" pos=")
                .append(position)
                .append(" source=")
                .append(sourceLabel ?: "none")

            builder.append(" methods=")
            allMethods(adapter.javaClass)
                .asSequence()
                .filter { !Modifier.isStatic(it.modifiers) }
                .filter { it.parameterTypes.size <= 1 }
                .take(28)
                .forEach { method ->
                    builder.append(method.name)
                        .append("(")
                        .append(method.parameterTypes.joinToString { it.simpleName })
                        .append("):")
                        .append(method.returnType.simpleName)
                        .append(";")
                }

            builder.append(" fields=")
            allFields(adapter.javaClass).take(28).forEach { field ->
                val value = readField(field, adapter)
                builder.append(field.name)
                    .append(":")
                    .append(field.type.simpleName)
                    .append("=")
                    .append(describeValue(value, position))
                    .append(";")
            }
            return builder.toString().take(6000)
        }

        private fun describeValue(value: Any?, position: Int): String {
            value ?: return "null"
            return when (value) {
                is List<*> -> describeIndexedCollection(value, position)
                is Collection<*> -> describeIndexedCollection(value.toList(), position)
                is Array<*> -> describeIndexedCollection(value.asList(), position)
                is CharSequence -> value.toString().take(40)
                is Number, is Boolean -> value.toString()
                else -> {
                    val nestedList = allFields(value.javaClass)
                        .asSequence()
                        .mapNotNull { field ->
                            val child = readField(field, value) ?: return@mapNotNull null
                            when (child) {
                                is List<*> -> "${field.name}=${describeIndexedCollection(child, position)}"
                                is Collection<*> -> "${field.name}=${describeIndexedCollection(child.toList(), position)}"
                                is Array<*> -> "${field.name}=${describeIndexedCollection(child.asList(), position)}"
                                else -> null
                            }
                        }
                        .take(4)
                        .joinToString("|")
                    if (nestedList.isNotBlank()) {
                        "${value.javaClass.name}{$nestedList}"
                    } else {
                        value.javaClass.name
                    }
                }
            }
        }

        private fun describeIndexedCollection(values: List<*>, position: Int): String {
            val indices = listOf(position - 1, position, position + 1)
                .filter { it in values.indices }
                .distinct()
            val items = indices.joinToString("|") { index ->
                val value = values[index]
                "#$index:${describeItem(value)}"
            }
            return "size=${values.size}[$items]"
        }

        private fun describeItem(value: Any?): String {
            value ?: return "null"
            val meta = extractRowMeta(value)
            val fields = allFields(value.javaClass)
                .asSequence()
                .take(12)
                .joinToString(",") { field ->
                    val fieldValue = readField(field, value)
                    "${field.name}=${shortValue(fieldValue)}"
                }
            return "${value.javaClass.name}{meta=$meta fields=$fields}"
        }

        private fun shortValue(value: Any?): String {
            value ?: return "null"
            return when (value) {
                is CharSequence -> value.toString().replace('\n', ' ').take(50)
                is Number, is Boolean -> value.toString()
                else -> value.javaClass.simpleName
            }
        }

        private fun readField(field: Field, target: Any): Any? {
            if (Modifier.isStatic(field.modifiers)) {
                return null
            }
            return try {
                field.isAccessible = true
                field.get(target)
            } catch (_: Throwable) {
                null
            }
        }

        private fun invokeNoArg(method: Method, target: Any): Any? {
            return try {
                method.isAccessible = true
                method.invoke(target)
            } catch (_: Throwable) {
                null
            }
        }

        private fun allFields(type: Class<*>): List<Field> {
            val result = mutableListOf<Field>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                result += current.declaredFields
                current = current.superclass
            }
            return result
        }

        private fun allMethods(type: Class<*>): List<Method> {
            val result = mutableListOf<Method>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                result += current.declaredMethods
                current = current.superclass
            }
            return result
        }

        private fun shouldInspectNestedObject(value: Any): Boolean {
            if (value is View || value is ViewGroup || value is CharSequence || value is Number || value is Boolean) {
                return false
            }
            if (value is List<*> || value is Array<*>) {
                return false
            }
            val name = value.javaClass.name
            return !name.startsWith("android.") &&
                    !name.startsWith("java.") &&
                    !name.startsWith("kotlin.") &&
                    !name.startsWith("androidx.recyclerview.")
        }

        private fun normalizeMessageContent(content: String?): String? {
            val text = content ?: return null
            val unixIndex = text.indexOf(":\n")
            val windowsIndex = text.indexOf(":\r\n")
            val index = when {
                unixIndex > 0 -> unixIndex + 2
                windowsIndex > 0 -> windowsIndex + 3
                else -> -1
            }
            val body = if (index in 3 until text.length) text.substring(index) else text
            val appTitle = extractXmlTag(body, "title")
            return (appTitle ?: body).takeIf { it.isNotBlank() }
        }

        private fun extractXmlTag(text: String, tagName: String): String? {
            val pattern = Regex("<$tagName(?:\\s[^>]*)?>(.*?)</$tagName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val raw = pattern.find(text)?.groupValues?.getOrNull(1)?.trim() ?: return null
            return decodeXmlText(raw).takeIf { it.isNotBlank() }
        }

        private fun decodeXmlText(text: String): String {
            return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
        }

        private fun normalizeTextForMatch(text: String?): String {
            return text
                ?.replace('\u00A0', ' ')
                ?.filterNot { it.isWhitespace() }
                ?.trim()
                ?: ""
        }

        private fun asLong(value: Any): Long? {
            return when (value) {
                is Number -> value.toLong()
                is CharSequence -> value.toString().toLongOrNull()
                else -> null
            }
        }

        private fun asInt(value: Any): Int? {
            return when (value) {
                is Number -> value.toInt()
                is CharSequence -> value.toString().toIntOrNull()
                else -> null
            }
        }

        private fun asBoolean(value: Any): Boolean? {
            return when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is CharSequence -> when (value.toString()) {
                    "1", "true", "TRUE" -> true
                    "0", "false", "FALSE" -> false
                    else -> null
                }
                else -> null
            }
        }

        private interface DataSource {
            val label: String
            fun get(adapter: Any, position: Int): Any?
        }

        private class MethodDataSource(private val method: Method) : DataSource {
            override val label: String = "method:${method.name}"

            override fun get(adapter: Any, position: Int): Any? {
                if (position < 0) {
                    return null
                }
                return try {
                    method.isAccessible = true
                    method.invoke(adapter, position)
                } catch (_: Throwable) {
                    null
                }
            }
        }

        private interface AccessibleMember {
            fun get(target: Any): Any?
        }

        private class FieldMember(private val field: Field) : AccessibleMember {
            override fun get(target: Any): Any? {
                return readField(field, target)
            }
        }

        private class MethodMember(private val method: Method) : AccessibleMember {
            override fun get(target: Any): Any? {
                return invokeNoArg(method, target)
            }
        }

        private class ListDataSource(
            override val label: String,
            private val outer: AccessibleMember,
            private val innerField: Field?
        ) : DataSource {
            override fun get(adapter: Any, position: Int): Any? {
                if (position < 0) {
                    return null
                }
                val value = readNestedValue(adapter) as? List<*> ?: return null
                return value.getOrNull(position)
            }

            private fun readNestedValue(adapter: Any): Any? {
                val outer = outer.get(adapter) ?: return null
                return if (innerField == null) {
                    outer
                } else {
                    readField(innerField, outer)
                }
            }
        }

        private class CollectionDataSource(
            override val label: String,
            private val outer: AccessibleMember,
            private val innerField: Field?
        ) : DataSource {
            override fun get(adapter: Any, position: Int): Any? {
                if (position < 0) {
                    return null
                }
                val value = readNestedValue(adapter) as? Collection<*> ?: return null
                if (position >= value.size) {
                    return null
                }
                return value.elementAt(position)
            }

            private fun readNestedValue(adapter: Any): Any? {
                val outer = outer.get(adapter) ?: return null
                return if (innerField == null) {
                    outer
                } else {
                    readField(innerField, outer)
                }
            }
        }

        private class ArrayDataSource(
            override val label: String,
            private val outer: AccessibleMember,
            private val innerField: Field?
        ) : DataSource {
            override fun get(adapter: Any, position: Int): Any? {
                if (position < 0) {
                    return null
                }
                val value = readNestedValue(adapter) as? Array<*> ?: return null
                return value.getOrNull(position)
            }

            private fun readNestedValue(adapter: Any): Any? {
                val outer = outer.get(adapter) ?: return null
                return if (innerField == null) {
                    outer
                } else {
                    readField(innerField, outer)
                }
            }
        }
    }

    private data class BoundNeighbors(
        val current: RowMeta,
        val previous: RowMeta?,
        val next: RowMeta?,
        val sourceLabel: String,
        val requestPosition: Int = -1,
        val matchedPosition: Int = -1,
        val hasTimeBeforeCurrent: Boolean = false,
        val hasTimeBeforeNext: Boolean = false
    )

    private data class MessageWindow(
        val sourceLabel: String,
        val entries: List<WindowEntry>,
        val byPosition: Map<Int, WindowEntry>,
        val byStableKey: Map<String, WindowEntry>
    )

    private data class WindowEntry(
        val position: Int,
        val meta: RowMeta
    )

    private data class SequenceCandidate(
        val entry: WindowEntry,
        val baseScore: Int
    )

    private data class SequenceState(
        val entry: WindowEntry,
        val score: Int,
        val path: List<WindowEntry>
    )

    private data class IndexedMessages(
        val count: Int,
        val sourceLabel: String,
        val firstPosition: Int,
        val lastPosition: Int,
        val entries: List<WindowEntry>,
        val byStableKey: Map<String, WindowEntry>
    )

    private data class VisibleTextItem(
        val requestPosition: Int,
        val visibleIndex: Int,
        val itemView: View,
        val info: TextItemInfo,
        val boundStableKey: String?
    )

    private data class RememberedBoundState(
        val position: Int,
        val stableKey: String
    )

    private data class VisibleClusterItem(
        val itemView: View,
        val info: TextItemInfo
    )

    private data class RowMeta(
        val isTextMessage: Boolean,
        val side: Side?,
        val senderKey: String?,
        val createTimeMs: Long?,
        val stableKey: String,
        val contentText: String?,
        val groupKey: String?
    ) {
        fun toCandidate(): MessageCandidate {
            return MessageCandidate(
                isTextMessage = isTextMessage,
                side = side,
                senderKey = senderKey,
                groupKey = groupKey
            )
        }

        fun toMessageRow(hasTimeSeparatorBefore: Boolean = false): ChatBubbleStylePolicy.MessageRow {
            return ChatBubbleStylePolicy.MessageRow(
                stableKey = stableKey,
                isTextMessage = isTextMessage,
                side = side,
                senderKey = senderKey,
                createTimeMs = createTimeMs,
                contentText = contentText,
                hasTimeSeparatorBefore = hasTimeSeparatorBefore,
                groupKey = groupKey
            )
        }
    }

    private data class BubbleDecision(
        val side: Side,
        val position: GroupPosition,
        val refreshableFromTop: Boolean
    )

    private data class TextItemInfo(
        val itemView: View,
        val messageView: View,
        val avatarView: View?,
        val nicknameView: View?,
        val side: Side,
        val senderKey: String?,
        val hasTimeSeparator: Boolean,
        val visibleText: String?,
        val shortText: String
    ) {
        fun toCandidate(meta: RowMeta?): MessageCandidate {
            val resolvedSender = when (side) {
                Side.RIGHT -> "self"
                Side.LEFT -> meta?.senderKey?.takeIf { it.isNotBlank() } ?: senderKey
            }
            return MessageCandidate(
                isTextMessage = meta?.isTextMessage ?: true,
                side = side,
                senderKey = resolvedSender,
                groupKey = meta?.groupKey
            )
        }
    }
}
