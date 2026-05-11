package com.blanke.mdwechat.hookers

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.*
import com.blanke.mdwechat.*
import com.blanke.mdwechat.WeChatHelper.createItemRippleDrawable
import com.blanke.mdwechat.WeChatHelper.drawableTransparent
import com.blanke.mdwechat.config.AppCustomConfig
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.hookers.base.Hooker
import com.blanke.mdwechat.hookers.base.HookerProvider
import com.blanke.mdwechat.hookers.main.BackgroundImageHook
import com.blanke.mdwechat.util.ChatBubbleStylePolicy
import com.blanke.mdwechat.util.ContactPageStyleResolver
import com.blanke.mdwechat.util.ColorUtils
import com.blanke.mdwechat.util.ConversationRipplePolicy
import com.blanke.mdwechat.util.ImageHelper
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.MainPageRippleGesturePolicy
import com.blanke.mdwechat.util.MainPageRipplePolicy
import com.blanke.mdwechat.util.NightModeUtils
import com.blanke.mdwechat.util.SettingsHeaderBackgroundPolicy
import com.blanke.mdwechat.util.SettingsHeaderStyleResolver
import com.blanke.mdwechat.util.ViewTreeUtils
import com.blanke.mdwechat.util.ViewUtils
import com.blanke.mdwechat.util.waitInvoke
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.blanke.mdwechat.ViewTreeRepoThisVersion as VTTV

object ListViewHooker : HookerProvider {
    private var wechatId: CharSequence = ""
    private val excludeContext = arrayOf("com.tencent.mm.plugin.mall.ui.MallIndexUI")
    private const val keyBackgroundCloned = "mdwechat_background_cloned"
    private const val keyForegroundCloned = "mdwechat_foreground_cloned"
    private const val keyConversationBaseBackground = "mdwechat_conversation_base_background"
    private const val keyConversationColorBackgroundSnapshot = "mdwechat_conversation_color_background_snapshot"
    private const val keyConversationOverlayRipple = "mdwechat_conversation_overlay_ripple"
    private const val keyMainPageOverlayEnabled = "mdwechat_main_page_overlay_enabled"
    private const val keyMainPageOverlayRipple = "mdwechat_main_page_overlay_ripple"
    private const val keyMainPageResetRunnable = "mdwechat_main_page_reset_runnable"
    private const val keyMainPageActiveTarget = "mdwechat_main_page_active_target"
    private const val keyMainPageListInteraction = "mdwechat_main_page_list_interaction"
    private const val keyMainPageListResetRunnable = "mdwechat_main_page_list_reset_runnable"
    private const val keyContactHeaderIconCircleApplied = "mdwechat_contact_header_icon_circle_applied"
    private const val keyContactRecyclerRow = "mdwechat_contact_recycler_row"
    private const val keyContactContainerViews = "mdwechat_contact_container_views"
    private const val keyContactRowViews = "mdwechat_contact_row_views"
    private const val keyContactPageItemRipple = "mdwechat_contact_page_item_ripple"
    private const val absListViewTouchModeRest = -1
    private const val absListViewInvalidPosition = -1
    private val conversationPressedState = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
    private val conversationEnabledState = intArrayOf(android.R.attr.state_enabled)
    private val contactHeaderIconContainerNames = setOf("ajy", "n8", "g9q")
    private val contactRecyclerRowMarkers = setOf("cg5", "kbo", "kbq")
    private val contactIndexedTextNames = setOf("kbq", "cfx")
    private val emptyDrawableState = intArrayOf()
    private val modernChatBubbleRowSignals = setOf(
        "bkl",
        "brp",
        "bkg",
        "big",
        "bp5",
        "bpv",
        "bjr",
        "bjs",
        "biq"
    )

    private data class ContactRowViews(
            val headerView: View?,
            val innerView: View?,
            val titleView: View?,
            val titleView80: View?,
            val headTextView: TextView?,
            val indexedTitleTextView: TextView?,
            val indexedSummaryTextView: TextView?
    )

    private val titleTextColor: Int
        get() {
            return NightModeUtils.getTitleTextColor()
        }
    private val summaryTextColor: Int
        get() {
            return NightModeUtils.getContentTextColor()
        }

    private val shouldPreserveWeChatItemBackground: Boolean
        get() = MainPageRipplePolicy.shouldPreserveNativeItemBackground(WechatGlobal.wxVersion)

    private val isHookTextColor: Boolean
        get() {
            return HookConfig.is_hook_main_textcolor || NightModeUtils.isNightMode()
        }

    override fun provideStaticHookers(): List<Hooker>? {
        return listOf(listViewHook, mainPageItemRippleHook)
    }

    private fun newTransparentDrawable(): Drawable = ColorDrawable(Color.TRANSPARENT)

    private fun isUsingOverlayConversationRipple(): Boolean {
        return ConversationRipplePolicy.shouldUseOverlayRipple(
                WechatGlobal.wxVersion,
                Build.VERSION.SDK_INT
        )
    }

    private fun isConversationItemView(view: View): Boolean {
        return view.javaClass.name == VTTV.ConversationListViewItem.item.clazz
    }

    private fun isUsingOverlayMainPageRipple(): Boolean {
        return MainPageRipplePolicy.shouldUseOverlayRipple(
                WechatGlobal.wxVersion,
                Build.VERSION.SDK_INT
        )
    }

    private fun isMainPageOverlayRippleTarget(view: View): Boolean {
        return XposedHelpers.getAdditionalInstanceField(view, keyMainPageOverlayEnabled) == true
    }

    private fun findMainPageOverlayRippleAncestor(view: View): View? {
        var current: View? = view
        while (current != null) {
            if (isMainPageOverlayRippleTarget(current)) {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun shouldSuppressNativeMainPageInteraction(view: View): Boolean {
        if (!isUsingOverlayMainPageRipple()) {
            return false
        }
        return findMainPageOverlayRippleAncestor(view) != null
    }

    fun resetRecycledMainPageRippleState(view: View) {
        cancelMainPageItemReset(view)
        removeMainPageOverlayRipple(view)
        unregisterActiveMainPageRippleTarget(view)
        XposedHelpers.removeAdditionalInstanceField(view, keyMainPageOverlayEnabled)
        clearInteractiveState(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                resetRecycledMainPageRippleState(view.getChildAt(i))
            }
        }
    }

    fun prepareReusableItemView(view: View) {
        if (WechatGlobal.wxVersion!! < Version("8.0.49")) {
            return
        }
        if (isMainPageOverlayRippleTarget(view)) {
            cancelMainPageItemReset(view)
            removeMainPageOverlayRipple(view)
        }
        if (isUsingOverlayConversationRipple() && isConversationItemView(view)) {
            clearConversationRowColorBackgroundSnapshots(view)
            removeConversationOverlayRipple(view)
            clearConversationOverlayInteraction(view)
            return
        }
        cloneStatefulBackgroundIfNeeded(view)
        cloneStatefulForegroundIfNeeded(view)
        if (isUsingOverlayConversationRipple()) {
            clearConversationColorBackgroundSnapshot(view)
        } else {
            restoreConversationColorBackground(view)
        }
        removeConversationOverlayRipple(view)
        clearInteractiveState(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                prepareReusableItemView(view.getChildAt(i))
            }
        }
    }

    private fun applyMainPageItemRipple(view: View) {
        if (!HookConfig.is_hook_ripple) {
            XposedHelpers.removeAdditionalInstanceField(view, keyMainPageOverlayEnabled)
            SettingsHooker.refreshSettingsStatusOverlayFromListChild(view)
            return
        }
        if (isUsingOverlayMainPageRipple()) {
            XposedHelpers.setAdditionalInstanceField(view, keyMainPageOverlayEnabled, true)
            SettingsHooker.refreshSettingsStatusOverlayFromListChild(view)
            return
        }
        XposedHelpers.removeAdditionalInstanceField(view, keyMainPageOverlayEnabled)
        view.background = if (MainPageRipplePolicy.shouldWrapRootBackground(WechatGlobal.wxVersion, Build.VERSION.SDK_INT)) {
            WeChatHelper.wrapItemBackgroundWithRipple(copyDrawable(view.background, view))
        } else if (shouldPreserveWeChatItemBackground && view.background != null) {
            WeChatHelper.wrapItemBackgroundWithRipple(copyDrawable(view.background, view))
        } else {
            createItemRippleDrawable()
        }
        SettingsHooker.refreshSettingsStatusOverlayFromListChild(view)
    }

    private fun applyContactPageItemSurface(view: View) {
        if (ContactPageStyleResolver.shouldUseTransparentItemSurface()) {
            view.background = drawableTransparent
        }
    }

    private fun applyContactPageItemRipple(view: View) {
        applyContactPageItemSurface(view)
        val cachedRipple = XposedHelpers.getAdditionalInstanceField(view, keyContactPageItemRipple) as? Drawable
        if (cachedRipple != null && HookConfig.is_hook_ripple) {
            cachedRipple.state = emptyDrawableState
            cachedRipple.jumpToCurrentState()
            view.background = cachedRipple
            SettingsHooker.refreshSettingsStatusOverlayFromListChild(view)
            return
        }
        applyMainPageItemRipple(view)
        if (HookConfig.is_hook_ripple) {
            view.background?.let {
                XposedHelpers.setAdditionalInstanceField(view, keyContactPageItemRipple, it)
            }
        } else {
            XposedHelpers.removeAdditionalInstanceField(view, keyContactPageItemRipple)
        }
    }

    fun isContactRecyclerRowView(view: View): Boolean {
        if (view !is ViewGroup) {
            return false
        }
        (XposedHelpers.getAdditionalInstanceField(view, keyContactRecyclerRow) as? Boolean)?.let {
            return it
        }
        val isRow = ContactPageStyleResolver.shouldStyleIndexedContactRow(
                collectRequiredViewResourceNames(view, contactRecyclerRowMarkers)
        )
        XposedHelpers.setAdditionalInstanceField(view, keyContactRecyclerRow, isRow)
        return isRow
    }

    private fun collectViewResourceNames(root: View, collector: MutableSet<String> = mutableSetOf()): Set<String> {
        getViewResourceName(root)?.let { collector.add(it) }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectViewResourceNames(root.getChildAt(i), collector)
            }
        }
        return collector
    }

    private fun collectRequiredViewResourceNames(
            root: View,
            requiredNames: Set<String>,
            collector: MutableSet<String> = mutableSetOf()
    ): Set<String> {
        getViewResourceName(root)?.let {
            if (it in requiredNames) {
                collector.add(it)
                if (collector.size == requiredNames.size) {
                    return collector
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectRequiredViewResourceNames(root.getChildAt(i), requiredNames, collector)
                if (collector.size == requiredNames.size) {
                    return collector
                }
            }
        }
        return collector
    }

    private fun tryApplyModernChatBubble(adapter: Any?, position: Int, view: View): Boolean {
        if (adapter == null || position < 0 || !isLikelyChatMessageRow(view)) {
            return false
        }
        return try {
            ModernChatBubbleRenderer.applyFromAdapterBind(adapter, position, view)
        } catch (throwable: Throwable) {
            LogUtil.log("ListViewHooker modern chat bubble apply failed: ${throwable.javaClass.simpleName}")
            false
        }
    }

    private fun isLikelyChatMessageRow(view: View): Boolean {
        val names = collectViewResourceNames(view)
        if ("bn1" !in names) {
            return false
        }
        return names.any { it in modernChatBubbleRowSignals }
    }

    private fun applyModernChatLabelColors(view: View) {
        if (!HookConfig.is_hook_chat_label_color) {
            return
        }
        val color = HookConfig.chat_label_color
        listOf("br1", "brc").forEach { name ->
            (findDescendantViewByResourceName(view, name) as? TextView)?.setTextColor(color)
        }
    }

    private fun clearContactPageContainerBackgrounds(root: View) {
        for (container in getContactContainerViews(root)) {
            container.background = drawableTransparent
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getContactContainerViews(root: View): List<ViewGroup> {
        (XposedHelpers.getAdditionalInstanceField(root, keyContactContainerViews) as? List<ViewGroup>)?.let { cached ->
            return cached
        }
        val containers = mutableListOf<ViewGroup>()
        collectContactContainerViews(root, containers)
        XposedHelpers.setAdditionalInstanceField(root, keyContactContainerViews, containers)
        return containers
    }

    private fun collectContactContainerViews(root: View, collector: MutableList<ViewGroup>) {
        if (root !is ViewGroup) {
            return
        }
        collector.add(root)
        for (i in 0 until root.childCount) {
            collectContactContainerViews(root.getChildAt(i), collector)
        }
    }

    private fun findDescendantViewByResourceName(root: View, resourceName: String): View? {
        if (getViewResourceName(root) == resourceName) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val match = findDescendantViewByResourceName(root.getChildAt(i), resourceName)
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }

    private fun findDescendantViewsByResourceName(root: View, resourceNames: Set<String>): Map<String, View> {
        val matches = mutableMapOf<String, View>()
        collectDescendantViewsByResourceName(root, resourceNames, matches)
        return matches
    }

    private fun collectDescendantViewsByResourceName(
            root: View,
            resourceNames: Set<String>,
            matches: MutableMap<String, View>
    ) {
        getViewResourceName(root)?.let {
            if (it in resourceNames && it !in matches) {
                matches[it] = root
                if (matches.size == resourceNames.size) {
                    return
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectDescendantViewsByResourceName(root.getChildAt(i), resourceNames, matches)
                if (matches.size == resourceNames.size) {
                    return
                }
            }
        }
    }

    private fun getContactRowViews(view: View): ContactRowViews {
        (XposedHelpers.getAdditionalInstanceField(view, keyContactRowViews) as? ContactRowViews)?.let {
            return it
        }
        val namedViews = findDescendantViewsByResourceName(view, contactIndexedTextNames)
        val rowViews = ContactRowViews(
                headerView = ViewUtils.getChildView1(view, VTTV.ContactListViewItem.treeStacks["headerView"]),
                innerView = ViewUtils.getChildView1(view, VTTV.ContactListViewItem.treeStacks["innerView"]),
                titleView = ViewUtils.getChildView1(view, VTTV.ContactListViewItem.treeStacks["titleView"]),
                titleView80 = ViewUtils.getChildView1(view, VTTV.ContactListViewItem.treeStacks["titleView_8_0"]),
                headTextView = ViewUtils.getChildView1(view, VTTV.ContactListViewItem.treeStacks["headTextView"]) as? TextView,
                indexedTitleTextView = namedViews["kbq"] as? TextView,
                indexedSummaryTextView = namedViews["cfx"] as? TextView
        )
        XposedHelpers.setAdditionalInstanceField(view, keyContactRowViews, rowViews)
        return rowViews
    }

    private fun getViewResourceName(view: View): String? {
        if (view.id == View.NO_ID) {
            return null
        }
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findParentAbsListView(view: View): AbsListView? {
        var current: ViewParent? = view.parent
        while (current is View) {
            val currentView = current
            if (currentView is AbsListView) {
                return currentView
            }
            current = currentView.getParent()
        }
        return null
    }

    private fun registerActiveMainPageRippleTarget(view: View) {
        findParentAbsListView(view)?.let {
            XposedHelpers.setAdditionalInstanceField(it, keyMainPageActiveTarget, view)
            XposedHelpers.setAdditionalInstanceField(it, keyMainPageListInteraction, true)
        }
    }

    private fun unregisterActiveMainPageRippleTarget(view: View) {
        findParentAbsListView(view)?.let {
            val current = XposedHelpers.getAdditionalInstanceField(it, keyMainPageActiveTarget) as? View
            if (current === view) {
                XposedHelpers.removeAdditionalInstanceField(it, keyMainPageActiveTarget)
            }
        }
    }

    private fun getActiveMainPageRippleTarget(listView: AbsListView): View? {
        return XposedHelpers.getAdditionalInstanceField(listView, keyMainPageActiveTarget) as? View
    }

    private fun clearActiveMainPageRippleTarget(listView: AbsListView) {
        XposedHelpers.removeAdditionalInstanceField(listView, keyMainPageActiveTarget)
    }

    private fun hasMainPageListInteraction(listView: AbsListView): Boolean {
        return XposedHelpers.getAdditionalInstanceField(listView, keyMainPageListInteraction) == true
    }

    private fun clearMainPageListInteraction(listView: AbsListView) {
        XposedHelpers.removeAdditionalInstanceField(listView, keyMainPageListInteraction)
    }

    private fun cloneStatefulBackgroundIfNeeded(view: View) {
        val background = view.background ?: return
        if (!background.isStateful || XposedHelpers.getAdditionalInstanceField(view, keyBackgroundCloned) != null) {
            return
        }
        val newBackground = background.constantState?.newDrawable(view.resources)?.mutate() ?: background.mutate()
        if (newBackground !== background) {
            view.background = newBackground
        }
        XposedHelpers.setAdditionalInstanceField(view, keyBackgroundCloned, true)
    }

    private fun cloneStatefulForegroundIfNeeded(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val foreground = view.foreground ?: return
        if (!foreground.isStateful || XposedHelpers.getAdditionalInstanceField(view, keyForegroundCloned) != null) {
            return
        }
        val newForeground = foreground.constantState?.newDrawable(view.resources)?.mutate() ?: foreground.mutate()
        if (newForeground !== foreground) {
            view.foreground = newForeground
        }
        XposedHelpers.setAdditionalInstanceField(view, keyForegroundCloned, true)
    }

    private fun clearInteractiveState(view: View) {
        view.isPressed = false
        view.isSelected = false
        view.isActivated = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            view.jumpDrawablesToCurrentState()
        }
        view.cancelLongPress()
        view.clearFocus()
        view.background?.state = intArrayOf()
        view.background?.jumpToCurrentState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.foreground?.state = intArrayOf()
            view.foreground?.jumpToCurrentState()
        }
        view.refreshDrawableState()
    }

    private fun copyDrawable(drawable: Drawable?, view: View): Drawable? {
        if (drawable == null) {
            return null
        }
        return drawable.constantState?.newDrawable(view.resources)?.mutate() ?: drawable.mutate()
    }

    private fun cacheConversationColorBackground(view: View) {
        val background = view.background as? ColorDrawable ?: return
        if (XposedHelpers.getAdditionalInstanceField(view, keyConversationColorBackgroundSnapshot) != null) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(
                view,
                keyConversationColorBackgroundSnapshot,
                copyDrawable(background, view)
        )
    }

    private fun restoreConversationColorBackground(view: View) {
        val snapshot = XposedHelpers.getAdditionalInstanceField(view, keyConversationColorBackgroundSnapshot) as? Drawable
                ?: return
        view.background = copyDrawable(snapshot, view)
    }

    private fun clearConversationColorBackgroundSnapshot(view: View) {
        XposedHelpers.removeAdditionalInstanceField(view, keyConversationColorBackgroundSnapshot)
    }

    private fun cacheConversationRowColorBackgrounds(view: View) {
        cacheConversationColorBackground(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                cacheConversationRowColorBackgrounds(view.getChildAt(i))
            }
        }
    }

    private fun restoreConversationRowColorBackgrounds(view: View) {
        restoreConversationColorBackground(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                restoreConversationRowColorBackgrounds(view.getChildAt(i))
            }
        }
    }

    private fun clearConversationRowColorBackgroundSnapshots(view: View) {
        clearConversationColorBackgroundSnapshot(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearConversationRowColorBackgroundSnapshots(view.getChildAt(i))
            }
        }
    }

    private fun clearConversationRowInteraction(view: View) {
        clearInteractiveState(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearConversationRowInteraction(view.getChildAt(i))
            }
        }
    }

    private fun clearConversationOverlayInteraction(view: View) {
        view.isPressed = false
        view.isSelected = false
        view.isActivated = false
        view.cancelLongPress()
        view.clearFocus()
        view.refreshDrawableState()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearConversationOverlayInteraction(view.getChildAt(i))
            }
        }
    }

    private fun buildConversationItemBackground(view: View): Drawable {
        val cachedBaseBackground = XposedHelpers.getAdditionalInstanceField(view, keyConversationBaseBackground) as? Drawable
        val baseBackground = if (cachedBaseBackground != null) {
            copyDrawable(cachedBaseBackground, view)
        } else {
            copyDrawable(view.background, view)?.also {
                XposedHelpers.setAdditionalInstanceField(view, keyConversationBaseBackground, copyDrawable(it, view))
            }
        } ?: ColorDrawable(if (NightModeUtils.isWechatNightMode()) WeChatHelper.wechatDark else WeChatHelper.wechatWhite)

        val backgroundAlpha = HookConfig.get_hook_conversation_background_alpha
        if (backgroundAlpha > 0) {
            baseBackground.alpha = backgroundAlpha
        }
        return baseBackground
    }

    private fun attachConversationOverlayRipple(view: View): Drawable {
        val existing = XposedHelpers.getAdditionalInstanceField(view, keyConversationOverlayRipple) as? Drawable
        if (existing != null) {
            existing.setBounds(0, 0, view.width, view.height)
            return existing
        }
        val overlayRipple = createItemRippleDrawable().mutate()
        overlayRipple.setBounds(0, 0, view.width, view.height)
        view.overlay.add(overlayRipple)
        XposedHelpers.setAdditionalInstanceField(view, keyConversationOverlayRipple, overlayRipple)
        return overlayRipple
    }

    private fun removeConversationOverlayRipple(view: View) {
        val overlayRipple = XposedHelpers.getAdditionalInstanceField(view, keyConversationOverlayRipple) as? Drawable
                ?: return
        try {
            view.overlay.remove(overlayRipple)
        } catch (_: Throwable) {
        }
        XposedHelpers.removeAdditionalInstanceField(view, keyConversationOverlayRipple)
        view.invalidate()
    }

    internal fun showConversationItemRipple(view: View, hotspotX: Float, hotspotY: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        removeConversationOverlayRipple(view)
        val overlayRipple = attachConversationOverlayRipple(view)
        overlayRipple.setBounds(0, 0, view.width, view.height)
        overlayRipple.setHotspot(
                hotspotX.coerceIn(0f, view.width.toFloat()),
                hotspotY.coerceIn(0f, view.height.toFloat())
        )
        overlayRipple.state = conversationPressedState
        view.invalidate()
    }

    internal fun ensureConversationItemRipple(view: View) {
        if (isUsingOverlayConversationRipple()) {
            clearConversationRowColorBackgroundSnapshots(view)
            removeConversationOverlayRipple(view)
            return
        }
        cacheConversationRowColorBackgrounds(view)

        val useRootBackgroundRipple = ConversationRipplePolicy.shouldWrapRootBackground(
                WechatGlobal.wxVersion,
                Build.VERSION.SDK_INT
        )
        if (useRootBackgroundRipple) {
            view.background = WeChatHelper.wrapItemBackgroundWithRipple(
                    buildConversationItemBackground(view)
            )
        } else {
            ViewUtils.getChildView1(view, VTTV.ConversationListViewItem.treeStacks["contentView"])?.apply {
                isDuplicateParentStateEnabled = true
                background = createItemRippleDrawable()
            }
        }
    }

    internal fun releaseConversationItemRipple(view: View) {
        if (isUsingOverlayConversationRipple()) {
            clearConversationOverlayInteraction(view)
        } else {
            restoreConversationRowColorBackgrounds(view)
            clearConversationRowInteraction(view)
        }
        val overlayRipple = XposedHelpers.getAdditionalInstanceField(view, keyConversationOverlayRipple) as? Drawable
                ?: return
        overlayRipple.setBounds(0, 0, view.width, view.height)
        overlayRipple.state = conversationEnabledState
        view.invalidate()
    }

    internal fun resetConversationItemState(view: View) {
        if (isUsingOverlayConversationRipple()) {
            clearConversationRowColorBackgroundSnapshots(view)
            clearConversationOverlayInteraction(view)
        } else {
            restoreConversationRowColorBackgrounds(view)
            clearConversationRowInteraction(view)
        }
        removeConversationOverlayRipple(view)
    }

    internal fun resetVisibleConversationRows(listView: View?) {
        val group = listView as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.javaClass.name == VTTV.ConversationListViewItem.item.clazz) {
                resetConversationItemState(child)
            }
        }
    }

    private fun attachMainPageOverlayRipple(view: View): Drawable {
        val existing = XposedHelpers.getAdditionalInstanceField(view, keyMainPageOverlayRipple) as? Drawable
        if (existing != null) {
            existing.setBounds(0, 0, view.width, view.height)
            return existing
        }
        val overlayRipple = createItemRippleDrawable().mutate()
        overlayRipple.setBounds(0, 0, view.width, view.height)
        view.overlay.add(overlayRipple)
        XposedHelpers.setAdditionalInstanceField(view, keyMainPageOverlayRipple, overlayRipple)
        return overlayRipple
    }

    private fun removeMainPageOverlayRipple(view: View) {
        val overlayRipple = XposedHelpers.getAdditionalInstanceField(view, keyMainPageOverlayRipple) as? Drawable
                ?: return
        try {
            view.overlay.remove(overlayRipple)
        } catch (_: Throwable) {
        }
        XposedHelpers.removeAdditionalInstanceField(view, keyMainPageOverlayRipple)
        view.invalidate()
    }

    private fun showMainPageItemRipple(view: View, hotspotX: Float, hotspotY: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        removeMainPageOverlayRipple(view)
        val overlayRipple = attachMainPageOverlayRipple(view)
        overlayRipple.setBounds(0, 0, view.width, view.height)
        overlayRipple.setHotspot(
                hotspotX.coerceIn(0f, view.width.toFloat()),
                hotspotY.coerceIn(0f, view.height.toFloat())
        )
        overlayRipple.state = conversationPressedState
        view.invalidate()
    }

    private fun releaseMainPageItemRipple(view: View) {
        val overlayRipple = XposedHelpers.getAdditionalInstanceField(view, keyMainPageOverlayRipple) as? Drawable
                ?: return
        overlayRipple.setBounds(0, 0, view.width, view.height)
        overlayRipple.state = conversationEnabledState
        view.invalidate()
    }

    private fun cancelMainPageItemReset(view: View) {
        val pending = XposedHelpers.getAdditionalInstanceField(view, keyMainPageResetRunnable) as? Runnable ?: return
        view.removeCallbacks(pending)
        XposedHelpers.removeAdditionalInstanceField(view, keyMainPageResetRunnable)
    }

    private fun clearMainPageInteractionState(view: View) {
        clearInteractiveState(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearMainPageInteractionState(view.getChildAt(i))
            }
        }
    }

    private fun clearVisibleMainPageInteractionState(listView: AbsListView) {
        for (i in 0 until listView.childCount) {
            clearMainPageInteractionState(listView.getChildAt(i))
        }
    }

    private fun clearMainPageParentInteractionState(view: View, stopAt: View? = null) {
        var current = view.parent as? View
        while (current != null && current !== stopAt) {
            clearInteractiveState(current)
            current = current.parent as? View
        }
    }

    private fun cancelMainPageListReset(listView: AbsListView) {
        val pending = XposedHelpers.getAdditionalInstanceField(listView, keyMainPageListResetRunnable) as? Runnable ?: return
        listView.removeCallbacks(pending)
        XposedHelpers.removeAdditionalInstanceField(listView, keyMainPageListResetRunnable)
    }

    private fun setIntFieldIfPresent(instance: Any, fieldName: String, value: Int) {
        try {
            XposedHelpers.setIntField(instance, fieldName, value)
        } catch (_: Throwable) {
        }
    }

    private fun setLongFieldIfPresent(instance: Any, fieldName: String, value: Long) {
        try {
            XposedHelpers.setLongField(instance, fieldName, value)
        } catch (_: Throwable) {
        }
    }

    private fun removeListViewCallbackIfPresent(listView: AbsListView, fieldName: String) {
        try {
            val callback = XposedHelpers.getObjectField(listView, fieldName) as? Runnable ?: return
            listView.removeCallbacks(callback)
        } catch (_: Throwable) {
        }
    }

    private fun clearAbsListViewPendingCallbacks(listView: AbsListView) {
        listOf(
                "mPendingCheckForTap",
                "mPendingCheckForLongPress",
                "mPendingCheckForKeyLongPress",
                "mTouchModeReset",
                "mPerformClick"
        ).forEach {
            removeListViewCallbackIfPresent(listView, it)
        }
    }

    private fun clearAbsListViewInternalState(listView: AbsListView) {
        clearAbsListViewPendingCallbacks(listView)
        setIntFieldIfPresent(listView, "mTouchMode", absListViewTouchModeRest)
        setIntFieldIfPresent(listView, "mLastTouchMode", absListViewTouchModeRest)
        setIntFieldIfPresent(listView, "mMotionPosition", absListViewInvalidPosition)
        setIntFieldIfPresent(listView, "mSelectorPosition", absListViewInvalidPosition)
        setIntFieldIfPresent(listView, "mResurrectToPosition", absListViewInvalidPosition)
        setIntFieldIfPresent(listView, "mSelectedPosition", absListViewInvalidPosition)
        setIntFieldIfPresent(listView, "mNextSelectedPosition", absListViewInvalidPosition)
        setIntFieldIfPresent(listView, "mOldSelectedPosition", absListViewInvalidPosition)
        setLongFieldIfPresent(listView, "mSelectedRowId", Long.MIN_VALUE)
        setLongFieldIfPresent(listView, "mNextSelectedRowId", Long.MIN_VALUE)
        setLongFieldIfPresent(listView, "mOldSelectedRowId", Long.MIN_VALUE)
        try {
            listView.clearChoices()
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(listView, "hideSelector")
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(listView, "setSelectedPositionInt", absListViewInvalidPosition)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(listView, "setNextSelectedPositionInt", absListViewInvalidPosition)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(listView, "dispatchSetPressed", false)
        } catch (_: Throwable) {
        }
    }

    private fun forceResetMainPageList(listView: AbsListView) {
        clearVisibleMainPageInteractionState(listView)
        clearAbsListViewInternalState(listView)
        clearInteractiveState(listView)
        clearMainPageParentInteractionState(listView)
        listView.selector?.state = intArrayOf()
        listView.selector?.jumpToCurrentState()
        try {
            XposedHelpers.callMethod(listView, "clearPressedItem")
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(listView, "setPressed", false)
        } catch (_: Throwable) {
        }
        listView.invalidateViews()
        listView.invalidate()
    }

    private fun scheduleMainPageListReset(listView: AbsListView, delayMillis: Long) {
        cancelMainPageListReset(listView)
        val runnable = Runnable {
            forceResetMainPageList(listView)
            XposedHelpers.removeAdditionalInstanceField(listView, keyMainPageListResetRunnable)
        }
        XposedHelpers.setAdditionalInstanceField(listView, keyMainPageListResetRunnable, runnable)
        if (delayMillis > 0) {
            listView.postDelayed(runnable, delayMillis)
        } else {
            listView.post(runnable)
        }
    }

    private fun resetMainPageItemRipple(view: View) {
        cancelMainPageItemReset(view)
        removeMainPageOverlayRipple(view)
        unregisterActiveMainPageRippleTarget(view)
        clearMainPageInteractionState(view)
        findParentAbsListView(view)?.let {
            forceResetMainPageList(it)
        }
    }

    private fun scheduleMainPageItemReset(view: View, delayMillis: Long) {
        cancelMainPageItemReset(view)
        val runnable = Runnable {
            resetMainPageItemRipple(view)
            XposedHelpers.removeAdditionalInstanceField(view, keyMainPageResetRunnable)
        }
        XposedHelpers.setAdditionalInstanceField(view, keyMainPageResetRunnable, runnable)
        if (delayMillis > 0) {
            view.postDelayed(runnable, delayMillis)
        } else {
            view.post(runnable)
        }
    }

    private fun shouldResetMainPageRippleFromRawPoint(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return MainPageRippleGesturePolicy.shouldResetFromRawPoint(
                rawX,
                rawY,
                location[0],
                location[1],
                view.width,
                view.height
        )
    }

    private val listViewHook = Hooker {
        XposedHelpers.findAndHookMethod(AbsListView::class.java, "setSelector", Drawable::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                param?.args!![0] = newTransparentDrawable()
            }
        })
        XposedHelpers.findAndHookMethod(AbsListView::class.java, "obtainView", CC.Int, BooleanArray::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                try {
                    val view = param?.result as View
                    val listView = param?.thisObject as? AbsListView
                    val adapter = listView?.adapter
                    val position = param?.args?.getOrNull(0) as? Int ?: -1
                    val context = view.context
                    val tmp = excludeContext.find { context::class.java.name.contains(it) }
                    if (tmp != null) {
                        return
                    }
                    resetRecycledMainPageRippleState(view)


                    // 按照使用频率重排序
                    val hookBubbles: Boolean = ((!NightModeUtils.isNightMode()) || HookConfig.is_hook_bubble_in_night_mode) && HookConfig.is_hook_chat_settings
                    if (hookBubbles && tryApplyModernChatBubble(adapter, position, view)) {
                        applyModernChatLabelColors(view)
                        prepareReusableItemView(view)
                        return
                    }
                    //气泡
                    // 聊天消息 item
                    if (ViewTreeUtils.equals(VTTV.ChatRightMessageItem.item, view)) {
                        LogUtil.logOnlyOnce("ListViewHooker.ChatRightMessageItem")
                        if (hookBubbles) {

                            //chat_label
                            if (HookConfig.is_hook_chat_label_color)
                                VTTV.ChatRightMessageItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(HookConfig.chat_label_color)
                                }

                            val chatMsgRightTextColor = HookConfig.get_hook_chat_text_color_right
                            VTTV.ChatRightMessageItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as View
//                    log("msgView=$msgView")
                                XposedHelpers.callMethod(msgView, "setTextColor", chatMsgRightTextColor)
                                XposedHelpers.callMethod(msgView, "setLinkTextColor", chatMsgRightTextColor)
                                XposedHelpers.callMethod(msgView, "setHintTextColor", chatMsgRightTextColor)
//                    val mText = XposedHelpers.getObjectField(msgView, "mText")
//                    log("msg right text=$mText")
                                ModernChatBubbleStyler.applyLegacyTextMessageFromAdapter(
                                        adapter,
                                        position,
                                        view,
                                        msgView,
                                        ChatBubbleStylePolicy.Side.RIGHT,
                                        scheduleAppendRefresh = true
                                )
                            }
                        }
                    } else if (ViewTreeUtils.equals(VTTV.ChatLeftMessageItem.item, view)) {
                        LogUtil.logOnlyOnce("ListViewHooker.ChatLeftMessageItem")
                        if (hookBubbles) {

                            if (HookConfig.is_hook_chat_label_color) {
                                //chat_label
                                VTTV.ChatLeftMessageItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(HookConfig.chat_label_color)
                                }
                                //nickNameView
                                VTTV.ChatLeftMessageItem.treeStacks["nickNameView"]?.apply {
                                    val nickNameView = ViewUtils.getChildView1(view, this) as TextView
                                    nickNameView.setTextColor(HookConfig.chat_label_color)
                                }
                            }
                            val chatMsgLeftTextColor = HookConfig.get_hook_chat_text_color_left
                            VTTV.ChatLeftMessageItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as View
//                                LogUtil.log("=======start=========")
//                                LogUtil.log("msgView=$msgView")
//                                val mText = XposedHelpers.getObjectField(msgView, "mText")
//                                LogUtil.log("msg left text=$mText")
//                                LogUtil.logView(view)
//                                LogUtil.logViewStackTraces(ViewUtils.getParentViewSafe(view, 2))
//                                LogUtil.log("=======end=========")
                                XposedHelpers.callMethod(msgView, "setTextColor", chatMsgLeftTextColor)
                                XposedHelpers.callMethod(msgView, "setLinkTextColor", chatMsgLeftTextColor)
                                XposedHelpers.callMethod(msgView, "setHintTextColor", chatMsgLeftTextColor)
                                ModernChatBubbleStyler.applyLegacyTextMessageFromAdapter(
                                        adapter,
                                        position,
                                        view,
                                        msgView,
                                        ChatBubbleStylePolicy.Side.LEFT,
                                        scheduleAppendRefresh = false
                                )
                            }
                        }
                    }

                    // 聊天消息 audio
                    else if (ViewTreeUtils.equals(VTTV.ChatRightAudioMessageItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatRightAudioMessageItem")
                            val chatMsgTextColor = HookConfig.get_hook_chat_text_color_right

                            //chat_label
                            if (HookConfig.is_hook_chat_label_color)
                                VTTV.ChatRightAudioMessageItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(HookConfig.chat_label_color)
                                }
                            //audioLengthView
                            VTTV.ChatRightAudioMessageItem.treeStacks["audioLengthView"]?.apply {
                                val audioLengthView = ViewUtils.getChildView1(view, this) as TextView
                                audioLengthView.setTextColor(chatMsgTextColor)
                            }

                            VTTV.ChatRightAudioMessageItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                //播放语音时的view
                                VTTV.ChatRightAudioMessageItem.treeStacks["msgAnimView"]?.apply {
                                    val msgAnimView = ViewUtils.getChildView1(view, this) as View
                                    val bubble = WeChatHelper.getRightBubble(msgView.resources)
                                    msgView.background = null
                                    ViewUtils.getParentViewSafe(msgView, 1).background = bubble
                                    msgAnimView.background = null
                                    if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                        msgView.setPadding(30, 25, 45, 25)
//                                msgAnimView.setPadding(30, 25, 45, 25)
                                    }
                                }

//                            //喇叭图标
                                val speakerIcon = msgView.compoundDrawables[2]
                                speakerIcon.setColorFilter(chatMsgTextColor, PorterDuff.Mode.SRC_ATOP)
                                msgView.setCompoundDrawables(null, null, speakerIcon, null)
                            }
                        }
                    } else if (ViewTreeUtils.equals(VTTV.ChatLeftAudioMessageItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatLeftAudioMessageItem")
                            val chatMsgTextColor = HookConfig.get_hook_chat_text_color_left

                            if (HookConfig.is_hook_chat_label_color) {
                                //chat_label
                                VTTV.ChatLeftAudioMessageItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(HookConfig.chat_label_color)
                                }
                                //nickNameView
                                VTTV.ChatLeftAudioMessageItem.treeStacks["nickNameView"]?.apply {
                                    val nickNameView = ViewUtils.getChildView1(view, this) as TextView
                                    nickNameView.setTextColor(HookConfig.chat_label_color)
                                }
                            }
                            //audioLengthView
                            VTTV.ChatLeftAudioMessageItem.treeStacks["audioLengthView"]?.apply {
                                val audioLengthView = ViewUtils.getChildView1(view, this) as TextView
                                audioLengthView.setTextColor(chatMsgTextColor)
                            }

                            VTTV.ChatLeftAudioMessageItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                VTTV.ChatLeftAudioMessageItem.treeStacks["msgAnimView"]?.apply {
                                    val msgAnimView = ViewUtils.getChildView1(view, this) as View
                                    val bubble = WeChatHelper.getLeftBubble(msgView.resources)
                                    msgView.background = null
                                    ViewUtils.getParentViewSafe(msgView, 1).background = bubble
                                    msgAnimView.background = null
                                    if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                        msgView.setPadding(45, 25, 30, 25)
//                                msgAnimView.setPadding(45, 25, 30, 25)
                                    }
//                            //喇叭图标
                                    val speakerIcon = msgView.compoundDrawables[0]
                                    speakerIcon.setColorFilter(chatMsgTextColor, PorterDuff.Mode.SRC_ATOP)
                                    msgView.setCompoundDrawables(speakerIcon, null, null, null)
                                }
                            }
                        }
                    }

                    // 通话消息
                    else if (ViewTreeUtils.equals(VTTV.ChatRightCallMessageItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatRightCallMessageItem")
                            val chatMsgTextColor = HookConfig.get_hook_chat_text_color_right
                            //chat_label
                            if (HookConfig.is_hook_chat_label_color) {
                                VTTV.ChatRightCallMessageItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(HookConfig.chat_label_color)
                                }
                            }
                            VTTV.ChatRightCallMessageItem.treeStacks["bgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as View
                                val bubble = WeChatHelper.getRightBubble(msgView.resources)
                                msgView.background = bubble
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    msgView.setPadding(30, 25, 45, 25)
                                }
                            }
                            //icon
                            VTTV.ChatRightCallMessageItem.treeStacks["icon"]?.apply {
                                val icon = ViewUtils.getChildView1(view, this) as LinearLayout
                                val speakerIcon = icon.background
                                speakerIcon.setColorFilter(chatMsgTextColor, PorterDuff.Mode.SRC_ATOP)
                                icon.background = speakerIcon
                            }
                            //msgView
                            VTTV.ChatRightCallMessageItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                msgView.setTextColor(chatMsgTextColor)
                            }
                        }
                    } else if (ViewTreeUtils.equals(VTTV.ChatLeftCallMessageItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatLeftCallMessageItem")
                            val chatMsgTextColor = HookConfig.get_hook_chat_text_color_left

                            //chat_label
                            if (HookConfig.is_hook_chat_label_color)
                                VTTV.ChatLeftCallMessageItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(HookConfig.chat_label_color)
                                }

                            //msgView
                            VTTV.ChatLeftCallMessageItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                msgView.setTextColor(chatMsgTextColor)
                            }
                            //icon
                            VTTV.ChatLeftCallMessageItem.treeStacks["icon"]?.apply {
                                val icon = ViewUtils.getChildView1(view, this) as LinearLayout
                                val speakerIcon = icon.background
                                speakerIcon.setColorFilter(chatMsgTextColor, PorterDuff.Mode.SRC_ATOP)
                                icon.background = speakerIcon
                            }
                            VTTV.ChatLeftCallMessageItem.treeStacks["bgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as View
                                val bubble = WeChatHelper.getLeftBubble(msgView.resources)
                                msgView.background = bubble
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    msgView.setPadding(30, 25, 45, 25)
                                }
                            }
                        }
                    }

                    // 引用消息 item
                    else if (ViewTreeUtils.equals(VTTV.RefRightMessageItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.RefRightMessageItem")

                            //chat_label
                            if (HookConfig.is_hook_chat_label_color)
                                VTTV.RefRightMessageItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(HookConfig.chat_label_color)
                                }

                            val chatMsgRightTextColor = HookConfig.get_hook_chat_text_color_right
                            VTTV.RefRightMessageItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as View
                                XposedHelpers.callMethod(msgView, "setTextColor", chatMsgRightTextColor)
                                XposedHelpers.callMethod(msgView, "setLinkTextColor", chatMsgRightTextColor)
                                XposedHelpers.callMethod(msgView, "setHintTextColor", chatMsgRightTextColor)
                                val bubble = WeChatHelper.getRightBubble(msgView.resources)
                                msgView.background = bubble
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    msgView.setPadding(30, 25, 45, 25)
                                }
                            }
                        }
                    } else if (ViewTreeUtils.equals(VTTV.RefLeftMessageItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatLeftMessageItem")

                            if (HookConfig.is_hook_chat_label_color) {
                                //chat_label
                                VTTV.RefLeftMessageItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(HookConfig.chat_label_color)
                                }
                                //nickNameView
                                VTTV.RefLeftMessageItem.treeStacks["nickNameView"]?.apply {
                                    val nickNameView = ViewUtils.getChildView1(view, this) as TextView
                                    nickNameView.setTextColor(HookConfig.chat_label_color)
                                }
                            }

                            val chatMsgLeftTextColor = HookConfig.get_hook_chat_text_color_left
                            VTTV.RefLeftMessageItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as View
                                XposedHelpers.callMethod(msgView, "setTextColor", chatMsgLeftTextColor)
                                XposedHelpers.callMethod(msgView, "setLinkTextColor", chatMsgLeftTextColor)
                                XposedHelpers.callMethod(msgView, "setHintTextColor", chatMsgLeftTextColor)
                                // 聊天气泡
                                val bubble = WeChatHelper.getLeftBubble(msgView.resources)
                                msgView.background = bubble
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    msgView.setPadding(45, 25, 30, 25)
                                }
                            }
                        }
                    }

                    //提示信息
                    else if (ViewTreeUtils.equals(VTTV.ChatHinterItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatHinterItem")

                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color
                                //chat_label
                                VTTV.ChatHinterItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                                VTTV.ChatHinterItem.treeStacks["msgView"]?.apply {
                                    val msgView = ViewUtils.getChildView1(view, this) as View
                                    XposedHelpers.callMethod(msgView, "setTextColor", chatLabelColor)
                                }
                            }
                        }
                    }
                    //图片
                    else if (ViewTreeUtils.equals(VTTV.ChatLeftPictureItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatLeftPictureItem")
                            if (HookConfig.is_hook_chat_label_color) {

                                val chatLabelColor = HookConfig.chat_label_color

                                //timeView
                                VTTV.ChatLeftPictureItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                                //nickNameView
                                VTTV.ChatLeftPictureItem.treeStacks["nickNameView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                        }
                    } else if (ViewTreeUtils.equals(VTTV.ChatRightPictureItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatRightPictureItem")
                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color

                                //timeView
                                VTTV.ChatLeftPictureItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                        }
                    }
                    //名片
                    else if (ViewTreeUtils.equals(VTTV.ChatLeftContactCardItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatLeftContactCardItem")
                            val chatLabelColor = HookConfig.chat_label_color
                            val chatTextColorLeft = HookConfig.get_hook_chat_text_color_left

                            if (HookConfig.is_hook_chat_label_color) {
                                //timeView
                                VTTV.ChatLeftContactCardItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                                //nickNameView
                                VTTV.ChatLeftContactCardItem.treeStacks["nickNameView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                            VTTV.ChatLeftContactCardItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView, "setTextColor", chatTextColorLeft)
                            }
                            VTTV.ChatLeftContactCardItem.treeStacks["titleView"]?.apply {
                                val titleView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(titleView, "setTextColor", chatTextColorLeft)
                            }
                            // 聊天气泡
                            VTTV.ChatLeftContactCardItem.treeStacks["bgView"]?.apply {
                                val bgView = ViewUtils.getChildView1(view, this) as View
                                bgView.background = WeChatHelper.getLeftBubble(bgView.resources)
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    bgView.setPadding(10, 0, 20, 25)
                                }
                            }
                        }
                    } else if (ViewTreeUtils.equals(VTTV.ChatRightContactCardItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatRightContactCardItem")
                            val chatTextColorRight = HookConfig.get_hook_chat_text_color_right

                            //timeView
                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color
                                VTTV.ChatRightContactCardItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                            VTTV.ChatRightContactCardItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView, "setTextColor", chatTextColorRight)
                            }
                            VTTV.ChatRightContactCardItem.treeStacks["msgView1"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView, "setTextColor", chatTextColorRight)
                            }
                            VTTV.ChatRightContactCardItem.treeStacks["titleView"]?.apply {
                                val titleView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(titleView, "setTextColor", chatTextColorRight)
                            }
                            // 聊天气泡
                            VTTV.ChatRightContactCardItem.treeStacks["bgView"]?.apply {
                                val bgView = ViewUtils.getChildView1(view, this) as View
                                bgView.background = WeChatHelper.getRightBubble(bgView.resources)
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    bgView.setPadding(20, 20, 10, 25)
                                }
                            }
                        }
                    }
                    //位置
                    else if (ViewTreeUtils.equals(VTTV.ChatLeftPositionItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatLeftPositionItem")
                            val chatTextColorLeft = HookConfig.get_hook_chat_text_color_left

                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color
                                //timeView
                                VTTV.ChatLeftPositionItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                                //nickNameView
                                VTTV.ChatLeftPositionItem.treeStacks["nickNameView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                            VTTV.ChatLeftPositionItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView, "setTextColor", chatTextColorLeft)
                            }
                            VTTV.ChatLeftPositionItem.treeStacks["msgView1"]?.apply {
                                val msgView1 = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView1, "setTextColor", chatTextColorLeft)
                            }
//                            VTTV.ChatLeftPositionItem.treeStacks.get("titleView")?.apply {
//                                val titleView = ViewUtils.getChildView1(view, this) as TextView
//                                XposedHelpers.callMethod(titleView, "setTextColor", chat_text_color_left)
//                            }
                            // 聊天气泡
                            VTTV.ChatLeftPositionItem.treeStacks["bgView"]?.apply {
                                val bgView = ViewUtils.getChildView1(view, this) as View
                                bgView.background = WeChatHelper.getLeftBubble(bgView.resources)
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    bgView.setPadding(20, 25, 20, 45)
                                }
                            }
                        }
                    } else if (ViewTreeUtils.equals(VTTV.ChatRightPositionItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatRightPositionItem")
                            val chatTextColor = HookConfig.get_hook_chat_text_color_right

                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color
                                //timeView
                                VTTV.ChatRightPositionItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                            VTTV.ChatRightPositionItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView, "setTextColor", chatTextColor)
                            }
                            VTTV.ChatRightPositionItem.treeStacks["msgView1"]?.apply {
                                val msgView1 = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView1, "setTextColor", chatTextColor)
                            }
//                            VTTV.ChatRightPositionItem.treeStacks.get("titleView")?.apply {
//                                val titleView = ViewUtils.getChildView1(view, this) as TextView
//                                XposedHelpers.callMethod(titleView, "setTextColor", chat_text_color_left)
//                            }
                            // 聊天气泡
                            VTTV.ChatRightPositionItem.treeStacks["bgView"]?.apply {
                                val bgView = ViewUtils.getChildView1(view, this) as View
                                bgView.background = WeChatHelper.getRightBubble(bgView.resources)
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    bgView.setPadding(20, 25, 20, 45)
                                }
                            }
                        }
                    }
                    //分享 / 小程序
                    else if (ViewTreeUtils.equals(VTTV.ChatLeftSharingItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatLeftSharingItem")
                            val chatTextColorLeft = HookConfig.get_hook_chat_text_color_left

                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color
                                //timeView
                                VTTV.ChatLeftSharingItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                                //nickNameView
                                VTTV.ChatLeftSharingItem.treeStacks["nickNameView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                            // 聊天气泡 miniProgramBgView
                            VTTV.ChatLeftSharingItem.treeStacks["miniProgramBgView"]?.apply {
                                val miniProgramBgView = ViewUtils.getChildView1(view, this) as View
                                miniProgramBgView.background = WeChatHelper.getLeftBubble(miniProgramBgView.resources)
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    miniProgramBgView.setPadding(20, 25, 20, 25)
                                }

                                VTTV.ChatLeftSharingItem.treeStacks["miniProgramBgView_miniProgramNameView"]?.apply {
                                    val miniProgramNameView = ViewUtils.getChildView1(miniProgramBgView, this) as TextView
                                    XposedHelpers.callMethod(miniProgramNameView, "setTextColor", chatTextColorLeft)
                                }
                                VTTV.ChatLeftSharingItem.treeStacks["miniProgramBgView_miniProgramTitleView"]?.apply {
                                    val miniProgramTitleView = ViewUtils.getChildView1(miniProgramBgView, this) as TextView
                                    XposedHelpers.callMethod(miniProgramTitleView, "setTextColor", chatTextColorLeft)
                                }

                                // 聊天气泡 null
                                VTTV.ChatLeftSharingItem.treeStacks["miniProgramBgView_bgView"]?.apply {
                                    val bgView = ViewUtils.getChildView1(miniProgramBgView, this) as View
                                    bgView.background = null
                                    VTTV.ChatLeftSharingItem.treeStacks["bgView_titleView"]?.apply {
                                        val titleView = ViewUtils.getChildView1(bgView, this) as TextView
                                        XposedHelpers.callMethod(titleView, "setTextColor", chatTextColorLeft)
                                    }
                                    VTTV.ChatLeftSharingItem.treeStacks["bgView_fileNameView"]?.apply {
                                        val fileNameView = ViewUtils.getChildView1(bgView, this) as View
                                        XposedHelpers.callMethod(fileNameView, "setTextColor", chatTextColorLeft)
                                    }
                                    VTTV.ChatLeftSharingItem.treeStacks["bgView_msgView"]?.apply {
                                        val msgView = ViewUtils.getChildView1(bgView, this) as TextView
                                        XposedHelpers.callMethod(msgView, "setTextColor", chatTextColorLeft)
                                    }
                                    VTTV.ChatLeftSharingItem.treeStacks["bgView_msgView1"]?.apply {
                                        val msgView1 = ViewUtils.getChildView1(bgView, this) as TextView
                                        XposedHelpers.callMethod(msgView1, "setTextColor", chatTextColorLeft)
                                    }
                                }
                            }
                        }
                    } else if (ViewTreeUtils.equals(VTTV.ChatRightSharingItem.item, view)) {
                        if (hookBubbles) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatRightSharingItem")
                            val chatTextColor = HookConfig.get_hook_chat_text_color_right

                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color
                                //timeView
                                VTTV.ChatRightSharingItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                                //nickNameView
                                VTTV.ChatRightSharingItem.treeStacks["nickNameView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                            // 聊天气泡 miniProgramBgView
                            VTTV.ChatRightSharingItem.treeStacks["miniProgramBgView"]?.apply {
                                val miniProgramBgView = ViewUtils.getChildView1(view, this) as View
                                miniProgramBgView.background = WeChatHelper.getRightBubble(miniProgramBgView.resources)
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    miniProgramBgView.setPadding(20, 25, 20, 25)
                                }

                                VTTV.ChatRightSharingItem.treeStacks["miniProgramBgView_miniProgramNameView"]?.apply {
                                    val miniProgramNameView = ViewUtils.getChildView1(miniProgramBgView, this) as TextView
                                    XposedHelpers.callMethod(miniProgramNameView, "setTextColor", chatTextColor)
                                }
                                VTTV.ChatRightSharingItem.treeStacks["miniProgramBgView_miniProgramTitleView"]?.apply {
                                    val miniProgramTitleView = ViewUtils.getChildView1(miniProgramBgView, this) as TextView
                                    XposedHelpers.callMethod(miniProgramTitleView, "setTextColor", chatTextColor)
                                }

                                // 聊天气泡 null
                                VTTV.ChatRightSharingItem.treeStacks["miniProgramBgView_bgView"]?.apply {
                                    val bgView = ViewUtils.getChildView1(miniProgramBgView, this) as View
                                    bgView.background = null
                                    VTTV.ChatRightSharingItem.treeStacks["bgView_titleView"]?.apply {
                                        val titleView = ViewUtils.getChildView1(bgView, this) as TextView
                                        XposedHelpers.callMethod(titleView, "setTextColor", chatTextColor)
                                    }
                                    VTTV.ChatRightSharingItem.treeStacks["bgView_fileNameView"]?.apply {
                                        val fileNameView = ViewUtils.getChildView1(bgView, this) as View
                                        XposedHelpers.callMethod(fileNameView, "setTextColor", chatTextColor)
                                    }
                                    VTTV.ChatRightSharingItem.treeStacks["bgView_msgView"]?.apply {
                                        val msgView = ViewUtils.getChildView1(bgView, this) as TextView
                                        XposedHelpers.callMethod(msgView, "setTextColor", chatTextColor)
                                    }
                                    VTTV.ChatRightSharingItem.treeStacks["bgView_msgView1"]?.apply {
                                        val msgView1 = ViewUtils.getChildView1(bgView, this) as TextView
                                        XposedHelpers.callMethod(msgView1, "setTextColor", chatTextColor)
                                    }
                                }
                            }
                        }
                    }

                    //红包 放最后
                    else if (ViewTreeUtils.equals(VTTV.ChatLeftRedPacketItem.item, view)) {
                        if (hookBubbles && HookConfig.is_hook_red_packet) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatLeftRedPacketItem")
                            val chatTextColor = HookConfig.get_hook_red_packet_text_color

                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color
                                //timeView
                                VTTV.ChatLeftRedPacketItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                                //nickNameView
                                VTTV.ChatLeftRedPacketItem.treeStacks["nickNameView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                            VTTV.ChatLeftRedPacketItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView, "setTextColor", chatTextColor)
                            }
                            var unopened = true
                            // "已被领完"
                            VTTV.ChatLeftRedPacketItem.treeStacks["msgView1"]?.apply {
                                val msgView1 = ViewUtils.getChildView1(view, this) as TextView
                                if (msgView1.text.length > 0) {
                                    unopened = false
                                }
                                XposedHelpers.callMethod(msgView1, "setTextColor", chatTextColor)
                            }
                            VTTV.ChatLeftRedPacketItem.treeStacks["titleView"]?.apply {
                                val titleView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(titleView, "setTextColor", chatTextColor)
                            }
                            // 聊天气泡
                            VTTV.ChatLeftRedPacketItem.treeStacks["bgView"]?.apply {
                                val bgView = ViewUtils.getChildView1(view, this) as View
                                if (unopened) {
                                    bgView.background = WeChatHelper.getUnopenedLeftRedPacketBubble(bgView.resources)
                                } else {
                                    bgView.background = WeChatHelper.getLeftRedPacketBubble(bgView.resources)
                                }
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    bgView.setPadding(10, 0, 10, 25)
                                }
                            }
                            VTTV.ChatLeftRedPacketItem.treeStacks["adsView"]?.apply {
                                val adsView = ViewUtils.getChildView1(view, this) as FrameLayout
                                adsView.visibility = View.GONE
                            }
                        }
                    }
                    // 右红包
                    else if (ViewTreeUtils.equals(VTTV.ChatRightRedPacketItem.item, view)) {
                        if (hookBubbles && HookConfig.is_hook_red_packet) {
                            LogUtil.logOnlyOnce("ListViewHooker.ChatRightRedPacketItem")
                            val chatTextColor = HookConfig.get_hook_red_packet_text_color

                            if (HookConfig.is_hook_chat_label_color) {
                                val chatLabelColor = HookConfig.chat_label_color
                                //timeView
                                VTTV.ChatRightRedPacketItem.treeStacks["timeView"]?.apply {
                                    val timeView = ViewUtils.getChildView1(view, this) as TextView
                                    timeView.setTextColor(chatLabelColor)
                                }
                            }
                            VTTV.ChatRightRedPacketItem.treeStacks["msgView"]?.apply {
                                val msgView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(msgView, "setTextColor", chatTextColor)
                            }
                            var unopened = true
                            // "已被领完"
                            VTTV.ChatRightRedPacketItem.treeStacks["msgView1"]?.apply {
                                val msgView1 = ViewUtils.getChildView1(view, this) as TextView
                                if (msgView1.text.length > 0) {
                                    unopened = false
                                }
                                XposedHelpers.callMethod(msgView1, "setTextColor", chatTextColor)
                            }
                            VTTV.ChatRightRedPacketItem.treeStacks["titleView"]?.apply {
                                val titleView = ViewUtils.getChildView1(view, this) as TextView
                                XposedHelpers.callMethod(titleView, "setTextColor", chatTextColor)
                            }
                            // 聊天气泡
                            VTTV.ChatRightRedPacketItem.treeStacks["bgView"]?.apply {
                                val bgView = ViewUtils.getChildView1(view, this) as View
                                if (unopened) {
                                    bgView.background = WeChatHelper.getUnopenedRightRedPacketBubble(bgView.resources)
                                } else {
                                    bgView.background = WeChatHelper.getRightRedPacketBubble(bgView.resources)
                                }
                                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                    bgView.setPadding(10, 0, 10, 25)
                                }
                            }
                            VTTV.ChatRightRedPacketItem.treeStacks["adsView"]?.apply {
                                val adsView = ViewUtils.getChildView1(view, this) as FrameLayout
                                adsView.visibility = View.GONE
                            }
                        }
                    }

                    // ConversationFragment 聊天列表 item
                    else if (ViewTreeUtils.equals(VTTV.ConversationListViewItem.item, view)) {
                        LogUtil.logOnlyOnce("ListViewHooker.ConversationListViewItem")
                        val chatNameView = ViewUtils.getChildView1(view, VTTV.ConversationListViewItem.treeStacks["chatNameView"])
                        val chatTimeView = ViewUtils.getChildView1(view, VTTV.ConversationListViewItem.treeStacks["chatTimeView"])
                        val recentMsgView = ViewUtils.getChildView1(view, VTTV.ConversationListViewItem.treeStacks["recentMsgView"])
                        val unreadCountView = ViewUtils.getChildView1(view, VTTV.ConversationListViewItem.treeStacks["unreadCountView"]) as TextView
                        val unreadView = ViewUtils.getChildView1(view, VTTV.ConversationListViewItem.treeStacks["unreadView"]) as ImageView
//                    LogUtil.logXp("chatNameView=$chatNameView,chatTimeView=$chatTimeView,recentMsgView=$recentMsgView")
                        if (isHookTextColor) {
                            XposedHelpers.callMethod(chatNameView, "setTextColor", titleTextColor)
                            XposedHelpers.callMethod(chatTimeView, "setTextColor", summaryTextColor)
                            XposedHelpers.callMethod(recentMsgView, "setTextColor", summaryTextColor)
                        }
                        unreadCountView.backgroundTintList = ColorStateList.valueOf(NightModeUtils.colorTip)
                        unreadCountView.setTextColor(HookConfig.get_color_tip_num)
                        unreadView.backgroundTintList = ColorStateList.valueOf(NightModeUtils.colorTip)
                        ensureConversationItemRipple(view)
                    }
                    //其他项, 背景置透明
                    // 联系人列表
                    else if (ViewTreeUtils.equals(VTTV.ContactListViewItem.item, view)) {
                        setContactListViewItem(view)
                    }
                    // 联系人列表头部
                    else if (ViewTreeUtils.equals(VTTV.ContactHeaderItem.item, view)) {
                        view.background = drawableTransparent
                        setContactHeaderItem(view)
                    }
                    // 发现 设置 item
                    else if (ViewTreeUtils.equals(VTTV.DiscoverViewItem.item, view)) {
                        view.background = drawableTransparent
//                        LogUtil.logViewStackTraces(view)
                        LogUtil.logOnlyOnce("ListViewHooker.DiscoverViewItem")
                        val iconImageView = ViewUtils.getChildView1(view, VTTV.DiscoverViewItem.treeStacks["iconImageView"]) as View
                        if (iconImageView.visibility == View.VISIBLE) {
                            val titleView = ViewUtils.getChildView1(view, VTTV.DiscoverViewItem.treeStacks["titleView"]) as TextView
                            if (isHookTextColor) {
                                titleView.setTextColor(titleTextColor)
                            }
                        }
//                        LogUtil.logViewStackTraces(view)
                        //group顶部横线
                        ViewUtils.getChildView1(view, VTTV.DiscoverViewItem.treeStacks["groupBorderTop"])
                                ?.background = drawableTransparent
                        //内容分割线
                        ViewUtils.getChildView1(view, VTTV.DiscoverViewItem.treeStacks["contentBorder"])
                                ?.background = drawableTransparent

                        //group底部横线
                        ViewUtils.getChildView1(view, VTTV.DiscoverViewItem.treeStacks["groupBorderBottom"])
                                ?.background = drawableTransparent

                        ViewUtils.getChildView1(view, VTTV.DiscoverViewItem.treeStacks["borderRight"])
                                ?.background = drawableTransparent



                        ViewUtils.getChildView1(view, VTTV.DiscoverViewItem.treeStacks["unreadPointView"])
                                ?.backgroundTintList = ColorStateList.valueOf(NightModeUtils.colorTip)
                        ViewUtils.getChildView1(view, VTTV.DiscoverViewItem.treeStacks["unreadCountView"])
                                ?.apply {
                                    this.backgroundTintList = ColorStateList.valueOf(NightModeUtils.colorTip)
                                    if (this is TextView) this.setTextColor(HookConfig.get_color_tip_num)
                                }
                        applyMainPageItemRipple(view)
                    }
                    // 设置 头像
                    else if (ViewTreeUtils.equals(VTTV.SettingAvatarView.item, view) || isSettingsHeaderRowView(view)) {
                        view.background = drawableTransparent
                        LogUtil.logOnlyOnce("ListViewHooker.SettingAvatarView")

//                        微信号
                        (findSettingsHeaderViewByResourceName(view, "ouv") as? TextView)?.apply {
                            if (this.text.contains(": ") || this.text.contains("：")) {

                                //隐藏微信号
                                if (HookConfig.is_hide_wechatId) {
                                    if (wechatId.isEmpty()) wechatId = this.text
                                    this.text = "点击显示微信号"
                                    try {
                                        this.setOnClickListener {
                                            this.text = wechatId
                                            LogUtil.log("已显示微信号")
                                        }
                                    } catch (e: Exception) {
                                        LogUtil.log("显示微信号错误")
                                        LogUtil.log(e)
                                    }
                                }

                                //微信号颜色
                                if (isHookTextColor) {
                                    this.setTextColor(titleTextColor)
                                    findSettingsHeaderViewByResourceName(view, "kbb")?.apply {
                                        try {
                                            XposedHelpers.callMethod(this, "setTextColor", titleTextColor)
                                        } catch (_: Throwable) {
                                            if (this is TextView) {
                                                this.setTextColor(titleTextColor)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (applyUnifiedSettingsHeaderBackground(view)) {
                            // Unified background already applies the row ripple target.
                        } else if (WechatGlobal.wxVersion!! >= Version("8.0.0")) {
                            if (!HookConfig.is_settings_page_transparent) {
                                VTTV.SettingAvatarView.treeStacks["headView"]?.apply {
                                    ViewUtils.getChildView1(view, this)?.apply {
                                        //生成背景
                                        if (HookConfig.is_hook_bg_immersion) {
                                            if (BackgroundImageHook._backgroundBitmap[4] != null) {
                                                this.background = NightModeUtils.getBackgroundDrawable(this.resources, BackgroundImageHook._backgroundBitmap[4])
                                                return
                                            } else {
                                                //2s之后如果没生成背景就放弃
                                                BackgroundImageHook.setMainPageBitmap("设置页头像栏", this, AppCustomConfig.getTabBg(3), 4, 4)
                                            }
                                        } else {
                                            this.background = if (NightModeUtils.isWechatNightMode()) ColorDrawable(WeChatHelper.wechatDark) else ColorDrawable(WeChatHelper.wechatWhite)
                                        }
                                    }
                                }
                                VTTV.SettingAvatarView.treeStacks["q1"]?.apply {
                                    ViewUtils.getChildView1(view, this)?.apply {
                                        //生成背景
                                        if (HookConfig.is_hook_bg_immersion) {
                                            if (BackgroundImageHook._backgroundBitmap[5] != null) {
                                                this.background = NightModeUtils.getBackgroundDrawable(this.resources, BackgroundImageHook._backgroundBitmap[5])

                                            } else {
                                                //2s之后如果没生成背景就放弃
                                                BackgroundImageHook.setMainPageBitmap("设置页头像栏 (状态) ", this, AppCustomConfig.getTabBg(3), 5, 4)
                                            }
                                        } else {
                                            this.background = if (NightModeUtils.isWechatNightMode()) ColorDrawable(WeChatHelper.wechatDark) else ColorDrawable(WeChatHelper.wechatWhite)
                                        }
                                    }
                                }
                            }
                            applyMainPageItemRipple(view)
                        } else {
                            VTTV.SettingAvatarView.treeStacks["headView"]?.apply {
                                ViewUtils.getChildView1(view, this)?.background = drawableTransparent
                            }
                            applyMainPageItemRipple(view)
                        }
                    }
                    // (7.0.7 以上) 下拉小程序框
                    else if (ViewTreeUtils.equals(VTTV.ActionBarItem.item, view)) {
                        view.background = drawableTransparent
                        if (HookConfig.is_hook_tab_bg) {
                            LogUtil.logOnlyOnce("ListViewHooker.ActionBarItem")
                            try {
                                ViewUtils.getChildView1(view, VTTV.ActionBarItem.treeStacks["miniProgramPage"])?.apply {
                                    val miniProgramPage = this as RelativeLayout

                                    // old action bar
                                    ViewUtils.getChildView1(miniProgramPage, VTTV.ActionBarItem.treeStacks["miniProgramPage_actionBarPage"])?.apply {
                                        val actionBarPage = this as LinearLayout
                                        ViewUtils.getChildView1(actionBarPage, VTTV.ActionBarItem.treeStacks["actionBarPage_addIcon"])?.apply {
                                            actionBarPage.removeView(this)
                                        }
                                        ViewUtils.getChildView1(actionBarPage, VTTV.ActionBarItem.treeStacks["actionBarPage_searchIcon"])?.apply {
                                            actionBarPage.removeView(this)
                                        }
                                    }

                                    //小程序界面
                                    ViewUtils.getChildView1(miniProgramPage, VTTV.ActionBarItem.treeStacks["miniProgramPage_appBrandDesktopView"])?.apply {
                                        val appBrandDesktopView = this as ViewGroup
                                        if (HookConfig.is_hook_appbrand_bg_color) {
                                            appBrandDesktopView.setBackgroundColor(HookConfig.appbrand_bg_color)
                                        }
                                        // 小程序搜索框
                                        ViewUtils.getChildView1(appBrandDesktopView, VTTV.ActionBarItem.treeStacks["appBrandDesktopView_searchText"])?.apply {
                                            this.setBackgroundColor(Color.parseColor("#15000000"))
                                        }
//                                        //  小程序字体
//                                        if (!miniProgramTextItems.contains(appBrandDesktopView)) {
//                                            miniProgramTextItems.add(appBrandDesktopView)
//                                        }
//                                        ViewUtils.getChildView1(appBrandDesktopView, VTTV.ActionBarItem.treeStacks["appBrandDesktopView_miniProgramTitle"])?.apply {
//                                            if (this is ViewGroup && !miniProgramTextItems.contains(this)) {
//                                                LogUtil.log("############222")
//                                                miniProgramTextItems.add(this)
//                                            }
//                                        }
//                                        setMiniProgramTitleColor()
                                    }
                                }
                            } catch (e: ClassCastException) {
//                            LogUtil.log(e)
//                            LogUtil.logViewStackTraces(view)
                                return
                            }
                        }
                    }
                    // 其他情况, 记录一下
                    else {
                        view.background = drawableTransparent
                        if ((!Common.isVXPEnv) && (HookConfig.is_hook_debug || HookConfig.is_hook_debug2)) {
                            LogUtil.log("----------未识别的listview----------")
                            LogUtil.log(WechatGlobal.wxVersion.toString())
                            LogUtil.log("context=" + view.context)
                            LogUtil.logViewStackTraces(view)
//                            LogUtil.logParentView(view, 100)
                            LogUtil.log("--------------------")
                        }
                    }
                    prepareReusableItemView(view)
                } catch (e: Exception) {
                    LogUtil.log(e)
                }
            }
        })
    }

    private val mainPageItemRippleHook = Hooker {
        XposedBridge.hookAllMethods(AbsListView::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                if (!isUsingOverlayMainPageRipple()) {
                    return
                }
                val listView = param?.thisObject as? AbsListView ?: return
                val event = param.args[0] as? MotionEvent ?: return
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    cancelMainPageListReset(listView)
                }
                val target = getActiveMainPageRippleTarget(listView)
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (target != null && shouldResetMainPageRippleFromRawPoint(target, event.rawX, event.rawY)) {
                            resetMainPageItemRipple(target)
                            clearActiveMainPageRippleTarget(listView)
                            scheduleMainPageListReset(listView, 120L)
                        }
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        forceResetMainPageList(listView)
                        clearActiveMainPageRippleTarget(listView)
                        clearMainPageListInteraction(listView)
                        scheduleMainPageListReset(listView, 180L)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    return
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE && getActiveMainPageRippleTarget(listView) == null && hasMainPageListInteraction(listView)) {
                    forceResetMainPageList(listView)
                    scheduleMainPageListReset(listView, 120L)
                }
            }
        })
        XposedBridge.hookAllMethods(AbsListView::class.java, "onTouchEvent", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                if (!isUsingOverlayMainPageRipple()) {
                    return
                }
                val listView = param?.thisObject as? AbsListView ?: return
                if (!hasMainPageListInteraction(listView)) {
                    return
                }
                val event = param.args[0] as? MotionEvent ?: return
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        forceResetMainPageList(listView)
                        clearActiveMainPageRippleTarget(listView)
                        clearMainPageListInteraction(listView)
                        scheduleMainPageListReset(listView, 180L)
                    }
                }
            }
        })
        XposedBridge.hookAllMethods(CC.View, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (!isUsingOverlayMainPageRipple()) {
                    return
                }
                val view = param?.thisObject as? View ?: return
                if (!isMainPageOverlayRippleTarget(view)) {
                    return
                }
                val event = param.args[0] as? MotionEvent ?: return
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelMainPageItemReset(view)
                        registerActiveMainPageRippleTarget(view)
                        showMainPageItemRipple(view, event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (MainPageRippleGesturePolicy.shouldResetFromLocalPoint(
                                        event.x,
                                        event.y,
                                        view.width,
                                        view.height
                                )) {
                            resetMainPageItemRipple(view)
                        }
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam?) {
                if (!isUsingOverlayMainPageRipple()) {
                    return
                }
                val view = param?.thisObject as? View ?: return
                if (!isMainPageOverlayRippleTarget(view)) {
                    return
                }
                val event = param.args[0] as? MotionEvent ?: return
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        releaseMainPageItemRipple(view)
                        scheduleMainPageItemReset(view, 220L)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        resetMainPageItemRipple(view)
                    }
                }
            }
        })
        XposedHelpers.findAndHookMethod(CC.View, "setPressed", CC.Boolean, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val view = param?.thisObject as? View ?: return
                val pressed = param.args[0] as? Boolean ?: return
                if (!pressed || !shouldSuppressNativeMainPageInteraction(view)) {
                    return
                }
                param.args[0] = false
            }
        })
        XposedHelpers.findAndHookMethod(CC.View, "setSelected", CC.Boolean, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val view = param?.thisObject as? View ?: return
                val selected = param.args[0] as? Boolean ?: return
                if (!selected || !shouldSuppressNativeMainPageInteraction(view)) {
                    return
                }
                param.args[0] = false
            }
        })
    }

    private fun applyUnifiedSettingsHeaderBackground(view: View): Boolean {
        if (!SettingsHeaderBackgroundPolicy.shouldUseUnifiedHeaderBackground(
                        WechatGlobal.wxVersion,
                        HookConfig.is_settings_page_transparent
                )) {
            return false
        }
        LogUtil.log("SettingsHeader unified row matched")
        view.background = ColorDrawable(getSettingsHeaderBaseColor())
        applySettingsHeaderSegmentByResourceName(view, "gxn", Color.TRANSPARENT, clearImage = true)
        applySettingsHeaderSegmentByResourceName(view, "gxv", Color.TRANSPARENT)
        applySettingsHeaderSegmentByResourceName(view, "gxp", Color.TRANSPARENT, clearImage = true)
        applySettingsHeaderSegmentByResourceName(view, "o4w", Color.TRANSPARENT)
        applySettingsHeaderSegmentByResourceName(view, "ovl", Color.TRANSPARENT, clearImage = true)
        applySettingsHeaderSegmentByResourceName(view, "hxi", Color.TRANSPARENT)
        VTTV.SettingAvatarView.treeStacks["headView"]?.apply {
            ViewUtils.getChildView1(view, this)?.let {
                applySettingsHeaderSegmentBackground(it, Color.TRANSPARENT, clearImage = true)
            }
        }
        VTTV.SettingAvatarView.treeStacks["statusSpacerView"]?.apply {
            ViewUtils.getChildView1(view, this)?.let {
                applySettingsHeaderSegmentBackground(it, Color.TRANSPARENT)
            }
        }
        VTTV.SettingAvatarView.treeStacks["q1"]?.apply {
            ViewUtils.getChildView1(view, this)?.let {
                applySettingsHeaderSegmentBackground(it, Color.TRANSPARENT, clearImage = true)
            }
        }
        applySettingsHeaderForegroundStyle(view)
        scheduleSettingsHeaderForegroundRefresh(view)
        applyMainPageItemRipple(view)
        return true
    }

    private fun getSettingsHeaderBaseColor(): Int {
        return SettingsHeaderStyleResolver.resolveBaseColor(NightModeUtils.isWechatNightMode())
    }

    private fun isSettingsHeaderRowView(view: View): Boolean {
        if (view !is ViewGroup) {
            return false
        }
        return findSettingsHeaderViewByResourceName(view, "gxv") != null &&
                findSettingsHeaderViewByResourceName(view, "kbb") != null &&
                findSettingsHeaderViewByResourceName(view, "ouv") != null
    }

    private fun applySettingsHeaderSegmentByResourceName(
        root: View,
        resourceName: String,
        color: Int,
        clearImage: Boolean = false
    ) {
        findSettingsHeaderViewByResourceName(root, resourceName)?.let {
            applySettingsHeaderSegmentBackground(it, color, clearImage)
        }
    }

    private fun applySettingsHeaderSegmentBackground(view: View, color: Int, clearImage: Boolean = false) {
        if (view is ImageView) {
            if (clearImage || SettingsHeaderBackgroundPolicy.shouldReplaceWideImageCarrier(
                            WechatGlobal.wxVersion,
                            view.width,
                            view.height
                    )) {
                view.setImageDrawable(null)
                view.clearColorFilter()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    view.imageTintList = null
                }
            }
        }
        view.background = ColorDrawable(color)
    }

    private fun applySettingsHeaderForegroundStyle(root: View) {
        val resourceName = getSettingsHeaderResourceName(root)
        when {
            root is ImageView -> applySettingsHeaderImageStyle(root, resourceName)
            root is TextView || resourceName == "kbb" -> applySettingsHeaderTextStyle(root, resourceName)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applySettingsHeaderForegroundStyle(root.getChildAt(i))
            }
        }
    }

    private fun scheduleSettingsHeaderForegroundRefresh(root: View, attempt: Int = 0) {
        root.postDelayed({
            applySettingsHeaderForegroundStyle(root)
            if (attempt < 5) {
                scheduleSettingsHeaderForegroundRefresh(root, attempt + 1)
            }
        }, 120L)
    }

    private fun applySettingsHeaderImageStyle(view: ImageView, resourceName: String?) {
        if (resourceName == "a_4" || resourceName == "ovl") {
            return
        }
        if (resourceName != null && SettingsHeaderStyleResolver.shouldKeepSegmentTransparent(resourceName)) {
            view.clearColorFilter()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.imageTintList = null
            }
            return
        }
        val iconColor = SettingsHeaderStyleResolver.resolveIconTintColor(
            if (NightModeUtils.isWechatNightMode()) WeChatHelper.colorDarkWhite else summaryTextColor
        )
        view.clearColorFilter()
        view.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    private fun applySettingsHeaderTextStyle(view: View, resourceName: String?) {
        val textColor = SettingsHeaderStyleResolver.resolveTextColor(
            resourceName,
            if (NightModeUtils.isWechatNightMode()) WeChatHelper.colorDarkWhite else titleTextColor,
            if (NightModeUtils.isWechatNightMode()) WeChatHelper.colorDarkWhite else summaryTextColor
        )
        try {
            XposedHelpers.callMethod(view, "setTextColor", textColor)
        } catch (_: Throwable) {
            if (view is TextView) {
                view.setTextColor(textColor)
            }
        }
    }

    private fun findSettingsHeaderViewByResourceName(root: View, resourceName: String): View? {
        if (getSettingsHeaderResourceName(root) == resourceName) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val childMatch = findSettingsHeaderViewByResourceName(root.getChildAt(i), resourceName)
                if (childMatch != null) {
                    return childMatch
                }
            }
        }
        return null
    }

    private fun getSettingsHeaderResourceName(view: View): String? {
        if (view.id == View.NO_ID) {
            return null
        }
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            null
        }
    }

    // 8.0.14 之后联系人列表从 LinearLayout 变为 NoDrawingCacheLinearLayout, 8.0.24 之后又变回去了 (?)
    fun setContactListViewItem(view: View) {
        LogUtil.logOnlyOnce("ListViewHooker.ContactListViewItem")
        val rowViews = getContactRowViews(view)
        applyContactPageItemSurface(view)
        clearContactPageContainerBackgrounds(view)
        // 标题下面的线
        rowViews.headerView?.background = drawableTransparent
        //内容下面的线 innerView
        rowViews.innerView?.background = drawableTransparent

        rowViews.titleView?.background = drawableTransparent
        rowViews.titleView80?.background = drawableTransparent
//        titleView80?.apply{
//            LogUtil.logView(this)}
        if (isHookTextColor) {
            rowViews.headTextView?.setTextColor(summaryTextColor)
            rowViews.titleView?.apply { XposedHelpers.callMethod(this, "setNickNameTextColor", ColorStateList.valueOf(titleTextColor)) }
            rowViews.titleView80?.apply {
                XposedHelpers.callMethod(this, "setTextColor", titleTextColor)
            }
            rowViews.indexedTitleTextView?.setTextColor(titleTextColor)
            rowViews.indexedSummaryTextView?.setTextColor(summaryTextColor)
        }
        applyContactPageItemRipple(view)
    }

    // 联系人列表头部 - 写的比较混乱:有些地方在contactHooker中
    fun setContactHeaderItem(view: View) {
        LogUtil.logOnlyOnce("ListViewHooker.ContactHeaderItem")

        //公众号的下边界 —— ContactHeaderItem.ContactWorkItemBorderTop
        ViewUtils.getChildView1(view, VTTV.ContactHeaderItem.treeStacks["ContactWorkItemBorderTop"])?.apply {
            this.background = drawableTransparent
        }

        //企业联系人分组
        ViewUtils.getChildView1(view, VTTV.ContactHeaderItem.treeStacks["ContactWorkItem"])?.apply {
            if (ViewTreeUtils.equals(VTTV.ContactWorkItem.item, this)) {
                LogUtil.logOnlyOnce("ListViewHooker.ContactWorkItem")

                //region borderLineTop
                ViewUtils.getChildView1(this, VTTV.ContactWorkItem.treeStacks["borderLineTop"])?.apply {
                    this.background = drawableTransparent
                }
                //endregion

                var contactContentsItem = ViewUtils.getChildView1(this, VTTV.ContactWorkItem.treeStacks["ContactContentsItem"])
                if (contactContentsItem != null) {
                    applyContactPageItemSurface(contactContentsItem)
                    //region 企业联系人
                    if (ViewTreeUtils.equals(VTTV.ContactWorkContactsItem.item, contactContentsItem)) {
                        LogUtil.logOnlyOnce("ListViewHooker.ContactWorkContactsItem")
                        applyContactHeaderEntryIconClip(findContactHeaderEntryIconContainer(contactContentsItem))
                        if (isHookTextColor) {
                            val headTextView = ViewUtils.getChildView1(contactContentsItem, VTTV.ContactWorkContactsItem.treeStacks["headTextView"]) as TextView
                            headTextView.setTextColor(titleTextColor)
                        }
                        ViewUtils.getChildView1(contactContentsItem, VTTV.ContactWorkContactsItem.treeStacks["titleView"])
                                ?.apply { applyContactPageItemRipple(this) }
                        ViewUtils.getChildView1(contactContentsItem, VTTV.ContactWorkContactsItem.treeStacks["borderLineBottom"])
                                ?.background = drawableTransparent
                        //endregion


                        val tmpView = ViewUtils.getChildView1(this, VTTV.ContactWorkItem.treeStacks["ContactContentsItem1"])
                        tmpView?.apply {
                            contactContentsItem = tmpView
                        }
                    }
                    // 我的企业
                    if (ViewTreeUtils.equals(VTTV.ContactMyWorkItem.item, contactContentsItem!!)) {
                        LogUtil.logOnlyOnce("ListViewHooker.ContactMyWorkItem")
                        applyContactPageItemSurface(contactContentsItem!!)
                        ViewUtils.getChildView1(contactContentsItem!!, VTTV.ContactMyWorkItem.treeStacks["titleView"])
                                ?.apply { applyContactPageItemRipple(this) }
                        ViewUtils.getChildView1(contactContentsItem!!, VTTV.ContactMyWorkItem.treeStacks["borderLineBottom"])
                                ?.background = drawableTransparent
                        if (isHookTextColor) {
                            val headTextView = ViewUtils.getChildView1(contactContentsItem!!, VTTV.ContactMyWorkItem.treeStacks["headTextView"]) as TextView
                            headTextView.setTextColor(titleTextColor)
                        }
                    }
                }
            }
        }
    }

    fun setContactHeaderItemTop(headLayout: ViewGroup) {
        for (i in 0 until headLayout.childCount) {
            val item = headLayout.getChildAt(i)
            if (item !is ViewGroup || item.childCount == 0) {
                continue
            }
            val itemContent = item.getChildAt(0)
            var titleTextView: View?
            var headTextView: View?
            if (itemContent != null) {
                // 新的朋友 等几个 item
                applyContactPageItemRipple(itemContent)
//                                                LogUtil.log("-------------")
//                                                LogUtil.logViewStackTraces(itemContent)
//                                                LogUtil.log("-------------")
                if (itemContent is ViewGroup) {
                    val childView = itemContent.getChildAt(0)
                    childView.background = drawableTransparent
                    if (childView is TextView) {// 企业号
                        headTextView = childView // 我的企业 textView
                        itemContent.background = drawableTransparent
                        val lll = (itemContent.getChildAt(1) as ViewGroup)
                        for (m in 0 until lll.childCount) {
                            val comItem = (lll.getChildAt(m) as ViewGroup)
                            val ll = comItem.getChildAt(0) as ViewGroup
                            applyContactPageItemRipple(ll)
                            // 去掉分割线
                            ll.getChildAt(0).background = drawableTransparent
                            titleTextView = ViewUtils.getChildView(ll, 0, 1)
                            titleTextView?.apply {
                                this.background = drawableTransparent
                                if (this is TextView && isHookTextColor) {
                                    this.setTextColor(titleTextColor)
                                }
                            }
                        }
                        if (isHookTextColor) {
                            headTextView.setTextColor(summaryTextColor)
                        }
                    } else if (childView is ViewGroup) {// 新的朋友 群聊 公众号
                        var maskLayout = childView.getChildAt(0)
                        titleTextView = childView.getChildAt(1) // 公众号 textView
//                                                        LogUtil.log("-------------")
//                                                        LogUtil.logViewStackTraces(childView)
//                                                        LogUtil.log("-------------")
                        if (titleTextView == null) {// 企业微信联系人
                            maskLayout = ViewUtils.getChildView(childView, 0, 0, 0, 0)
                            titleTextView = ViewUtils.getChildView(childView, 0, 0, 0, 1)
                            ViewUtils.getChildView(childView, 0, 0)?.background = drawableTransparent
                        }
                        applyContactHeaderEntryIconClip(maskLayout)
                        if (titleTextView != null) {
                            titleTextView.background = drawableTransparent
                            if (isHookTextColor) {
                                titleTextView.apply {
                                    if (this is TextView) {
                                        this.setTextColor(titleTextColor)
                                    } else if (this is ViewGroup) {
                                        val tv = this.getChildAt(0)
                                        if (tv is TextView) {
                                            tv.setTextColor(titleTextColor)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyContactHeaderEntryIconClip(maskLayout: View?) {
        if (
                !HookConfig.is_hook_avatar
                || !ContactPageStyleResolver.shouldWrapHeaderEntryIcon(WechatGlobal.wxVersion)
                || maskLayout == null
        ) {
            return
        }
        if (maskLayout !is ViewGroup) {
            return
        }
        applyContactHeaderIconCircle(maskLayout)
        maskLayout.post {
            applyContactHeaderIconCircle(maskLayout)
        }
        maskLayout.postDelayed({
            applyContactHeaderIconCircle(maskLayout)
        }, 120L)
    }

    private fun applyContactHeaderIconCircle(maskLayout: ViewGroup): Boolean {
        if (XposedHelpers.getAdditionalInstanceField(maskLayout, keyContactHeaderIconCircleApplied) == true) {
            return true
        }
        val bitmap = createContactHeaderCircleBitmap(maskLayout) ?: return false
        maskLayout.background = BitmapDrawable(maskLayout.resources, bitmap)
        hideContactHeaderIconChildren(maskLayout)
        maskLayout.setWillNotDraw(false)
        maskLayout.invalidate()
        XposedHelpers.setAdditionalInstanceField(maskLayout, keyContactHeaderIconCircleApplied, true)
        return true
    }

    private fun createContactHeaderCircleBitmap(maskLayout: ViewGroup): Bitmap? {
        val width = maskLayout.width
        val height = maskLayout.height
        if (width <= 0 || height <= 0) {
            return null
        }
        return try {
            val contentView = findDescendantViewByResourceName(maskLayout, "cgi")
                    ?: if (maskLayout.childCount > 0) maskLayout.getChildAt(0) else null
                    ?: return null
            if (contentView.width <= 0 || contentView.height <= 0) {
                return null
            }

            val contentBitmap = Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888)
            Canvas(contentBitmap).apply {
                contentView.draw(this)
            }

            val circleBitmap = Bitmap.createBitmap(contentBitmap.width, contentBitmap.height, Bitmap.Config.ARGB_8888)
            val circleCanvas = Canvas(circleBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            paint.color = resolveContactHeaderIconFillColor(contentBitmap)
            circleCanvas.drawOval(RectF(0f, 0f, contentBitmap.width.toFloat(), contentBitmap.height.toFloat()), paint)
            circleCanvas.drawBitmap(contentBitmap, 0f, 0f, null)

            val clippedCircle = ImageHelper.getRoundedCornerBitmap(circleBitmap, minOf(circleBitmap.width, circleBitmap.height) / 2)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(clippedCircle, contentView.left.toFloat(), contentView.top.toFloat(), null)
            output
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveContactHeaderIconFillColor(bitmap: Bitmap): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val alpha = Color.alpha(color)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                if (alpha <= 64 || (r > 210 && g > 210 && b > 210)) {
                    continue
                }
                red += r
                green += g
                blue += b
                count++
            }
        }
        if (count == 0L) {
            return Color.TRANSPARENT
        }
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun hideContactHeaderIconChildren(maskLayout: ViewGroup) {
        for (i in 0 until maskLayout.childCount) {
            maskLayout.getChildAt(i).visibility = View.INVISIBLE
        }
    }

    private fun findContactHeaderEntryIconContainer(root: View?): View? {
        root ?: return null
        if (getViewResourceName(root) in contactHeaderIconContainerNames) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val match = findContactHeaderEntryIconContainer(root.getChildAt(i))
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }
//    private val settingMiniProgramTitleColor = false
//
//    //设置小程序的字体颜色，需要重复设置
//    fun setMiniProgramTitleColor() {
//        if (settingMiniProgramTitleColor) {
//            return
//        }
//        LogUtil.log("循环修改小程序字体颜色...")
//        thread {
//            while (true) {
//                for (items in 0..miniProgramTextItems.size - 1) {
//                    try {
//                        val fatherView = miniProgramTextItems[items]
//                        LogUtil.logViewStackTraces(fatherView)
//                        for (childAt in 0 until fatherView.childCount) {
//                            val child = fatherView.getChildAt(childAt)
//                            if (child is ViewGroup) {
//                                val textView = findLastChildView(child, CC.TextView.name)
//                                if (textView is TextView) {
//                                        LogUtil.log("setting text color: ${textView.text}")
//                                    if (textView.currentTextColor != titleTextColor) {
//                                        textView.setTextColor(titleTextColor)
//                                    }
//                                }
//                            }
//                        }
//                    } catch (e: Exception) {
//                        LogUtil.log(e)
//                        miniProgramTextItems.removeAt(items)
//                    }
//                }
//                if (true||!HookConfig.is_hook_appbrand_text_color) {
//                    return@thread
//                }
//                Thread.sleep(2000)
//            }
//        }
//    }
}
