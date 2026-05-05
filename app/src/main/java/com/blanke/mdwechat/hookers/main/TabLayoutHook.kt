package com.blanke.mdwechat.hookers.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.blanke.mdwechat.Methods
import com.blanke.mdwechat.Objects
import com.blanke.mdwechat.Version
import com.blanke.mdwechat.WechatGlobal
import com.blanke.mdwechat.config.AppCustomConfig
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.hookers.StatusBarHooker
import com.blanke.mdwechat.util.ConvertUtils
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.MaterialTabCustomIconPolicy
import com.blanke.mdwechat.util.MaterialTabIconTintPolicy
import com.blanke.mdwechat.util.ModuleContextCompat
import com.blanke.mdwechat.util.NightModeUtils
import com.blanke.mdwechat.util.RippleColorResolver
import com.blanke.mdwechat.util.RuntimeProbe
import com.blanke.mdwechat.util.TabLayoutIndicatorPolicy
import com.blanke.mdwechat.util.ViewUtils
import com.blanke.mdwechat.util.mainThread
import com.blanke.mdwechat.widget.MaterialTabItem
import com.blanke.mdwechat.widget.MdMaterialTabLayout
import com.joshcai.mdwechat.R
import com.google.android.material.tabs.TabLayout
import de.robv.android.xposed.XposedHelpers

object TabLayoutHook {
    @Volatile
    var lastTopAttachStage: String = "idle"

    private val topBarContainerNames = listOf("wk", "ei", "cg7", "gp", "coz", "actionbar_up_indicator")

    private fun collapseView(view: View?) {
        view ?: return
        view.visibility = View.GONE
        view.layoutParams?.height = 0
        view.requestLayout()
    }

    private fun markTopAttachStage(stage: String) {
        lastTopAttachStage = stage
    }

    private fun describeThemeAttr(context: Context, attr: Int): String {
        val typedValue = TypedValue()
        val resolved = context.theme?.resolveAttribute(attr, typedValue, true) == true
        if (!resolved) return "missing"
        return buildString {
            append("type=")
            append(typedValue.type)
            append(",data=0x")
            append(Integer.toHexString(typedValue.data))
            append(",resId=")
            append(typedValue.resourceId)
        }
    }

    private fun logMaterialContextState(stage: String, context: Context) {
        val summary = buildString {
            append("topTabContext:")
            append(stage)
            append(" ctx=")
            append(context.javaClass.name)
            append(" pkg=")
            append(context.packageName)
            append(" theme=")
            append(context.theme?.javaClass?.name ?: "null")
            append(" colorPrimary=")
            append(describeThemeAttr(context, com.google.android.material.R.attr.colorPrimary))
            append(" colorSurface=")
            append(describeThemeAttr(context, com.google.android.material.R.attr.colorSurface))
            append(" colorOnSurface=")
            append(describeThemeAttr(context, com.google.android.material.R.attr.colorOnSurface))
        }
        LogUtil.exportLog(summary)
        LogUtil.log(summary)
        RuntimeProbe.append(context, summary)
    }

    private val tabTitles = listOf("微信", "通讯录", "发现", "我")
    private val fallbackTabIcons = listOf(
        R.drawable.ic_md_tab_chat,
        R.drawable.ic_md_tab_contacts,
        R.drawable.ic_md_tab_discover,
        R.drawable.ic_md_tab_me
    )

    private fun tabItems(
        customIconBitmaps: List<Bitmap?>
    ) = tabTitles.mapIndexed { index, title ->
        MaterialTabItem(
            iconRes = fallbackTabIcons[index],
            text = title,
            iconBitmap = customIconBitmaps.getOrNull(index)
        )
    }

    private fun customTabIconBitmaps(): List<Bitmap?> {
        return tabTitles.indices.map { index -> AppCustomConfig.getMaterialTabIcon(index) }
    }

    private fun getResourceEntryName(view: View): String? {
        return try {
            if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hideViewsByResourceName(root: View, targetNames: Set<String>) {
        val resourceName = getResourceEntryName(root)
        if (resourceName in targetNames) {
            root.visibility = View.GONE
            root.layoutParams?.height = 0
            root.requestLayout()
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                hideViewsByResourceName(root.getChildAt(index), targetNames)
            }
        }
    }

    private fun hideNamedDescendants(root: View, names: List<String>) {
        names.forEach { name ->
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            if (id != 0) {
                root.findViewById<View>(id)?.let {
                    it.visibility = View.GONE
                    it.layoutParams?.height = 0
                    it.requestLayout()
                }
            }
        }
    }

    private fun forceHideLegacyTopBar(root: View, actionBarView: View?, stage: String) {
        collapseView(actionBarView)
        collapseView(Objects.Main.HomeUI_mActionBar as? View)
        hideViewsByResourceName(root, topBarContainerNames.toSet())
        hideNamedDescendants(root, topBarContainerNames)
        RuntimeProbe.append(root.context, "topTabHideLegacy stage=$stage")
    }

    private fun resolveTopInset(root: View): Int {
        val statusBarBackgroundHeight = root.findViewById<View>(android.R.id.statusBarBackground)?.height ?: 0
        val windowInsetTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            root.rootWindowInsets?.stableInsetTop ?: root.rootWindowInsets?.systemWindowInsetTop ?: 0
        } else {
            0
        }
        return maxOf(
            HookConfig.statusBarHeight,
            Objects.Main.statusView?.height ?: 0,
            statusBarBackgroundHeight,
            windowInsetTop
        )
    }

    private fun ensureAttachedLater(parent: ViewGroup, child: View, index: Int, params: ViewGroup.LayoutParams, delayMs: Long) {
        parent.postDelayed({
            try {
                markTopAttachStage("reattach${delayMs}")
                val currentParent = child.parent
                if (currentParent == null) {
                    val safeIndex = index.coerceAtMost(parent.childCount)
                    parent.addView(child, safeIndex, params)
                } else if (currentParent !== parent && currentParent is ViewGroup) {
                    currentParent.removeView(child)
                    val safeIndex = index.coerceAtMost(parent.childCount)
                    parent.addView(child, safeIndex, params)
                }
                child.bringToFront()
                parent.requestLayout()
                parent.invalidate()
            } catch (t: Throwable) {
                markTopAttachStage("reattach${delayMs}Failed")
                LogUtil.log(t)
                LogUtil.toast("topTabAsyncFail:reattach${delayMs}:${t.javaClass.simpleName}")
            }
        }, delayMs)
    }

    private fun newTabLayout(viewGroup: ViewGroup, indicatorGravity: Int = Gravity.BOTTOM, tabElevation: Float): MdMaterialTabLayout {
        val selectedColor = NightModeUtils.colorSecondary
        val unselectedColor = NightModeUtils.getTitleTextColor()
        val tintSelectedIcon = NightModeUtils.is_tab_layout_main_page_filtered
        val tintUnselectedIcon = NightModeUtils.is_tab_layout_filtered
        val customIconBitmaps = customTabIconBitmaps()
        val hasCustomIcons = customIconBitmaps.any { it != null }
        val iconTintColors = MaterialTabIconTintPolicy.resolve(
            selectedTextColor = selectedColor,
            unselectedTextColor = unselectedColor,
            tertiaryColor = NightModeUtils.colorTeritary,
            tintSelectedIcon = tintSelectedIcon,
            tintUnselectedIcon = tintUnselectedIcon
        )
        val iconTintEnabled = MaterialTabCustomIconPolicy.shouldTintIcons(
            hasCustomIcons = hasCustomIcons,
            tintSelectedIcon = tintSelectedIcon,
            tintUnselectedIcon = tintUnselectedIcon
        )
        val tipColor = HookConfig.get_color_tip_in_guide
        val rippleColor = if (HookConfig.is_hook_ripple) {
            RippleColorResolver.resolvePressedColor(HookConfig.get_color_ripple)
        } else {
            Color.TRANSPARENT
        }
        val context = ModuleContextCompat.wrap(
            viewGroup.context,
            R.style.Theme_MDWechat_MaterialTabs
        )
        val isSmallIndicator = HookConfig.is_small_tab_bar_size
        val indicatorHeight = maxOf(
            1,
            ConvertUtils.dp2px(
                viewGroup.context,
                TabLayoutIndicatorPolicy.indicatorHeightDp(isSmallIndicator)
            )
        )
        logMaterialContextState("beforeCreate", context)
        try {
            return MdMaterialTabLayout(context).apply {
                contentDescription = "MDWECHAT_TAB_OK"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                elevation = tabElevation
                setTabItems(
                    tabItems(customIconBitmaps)
                )
                configureAppearance(
                    selectedColor = selectedColor,
                    unselectedColor = unselectedColor,
                    selectedIconColor = iconTintColors.selectedIconColor,
                    unselectedIconColor = iconTintColors.unselectedIconColor,
                    indicatorColor = if (NightModeUtils.is_hook_tab_bar) selectedColor else Color.TRANSPARENT,
                    indicatorHeightPx = indicatorHeight,
                    rippleColor = rippleColor,
                    badgeBackgroundColor = tipColor,
                    badgeTextColor = HookConfig.get_color_tip_num_in_guide,
                    indicatorOnContent = TabLayoutIndicatorPolicy.indicatorOnContent(isSmallIndicator),
                    iconTintEnabled = iconTintEnabled
                )
                setOnTabSelected { position ->
                    LogUtil.log("tab click position=$position")
                    Objects.Main.LauncherUI_mViewPager?.apply {
                        try {
                            Methods.WxViewPager_selectedPage.invoke(this, position, false, false, 0)
                        } catch (e: Exception) {
                            LogUtil.log(e)
                        }
                    }
                }
                setCurrentTab(Objects.Main.pagePosition)
                getMaterialTabLayout().setSelectedTabIndicatorGravity(
                    when (indicatorGravity) {
                        Gravity.TOP -> TabLayout.INDICATOR_GRAVITY_TOP
                        Gravity.CENTER_VERTICAL -> TabLayout.INDICATOR_GRAVITY_CENTER
                        else -> TabLayout.INDICATOR_GRAVITY_BOTTOM
                    }
                )
            }
        } catch (t: Throwable) {
            RuntimeProbe.append(
                context,
                "topTabCreateFailed stage=$lastTopAttachStage type=${t.javaClass.name} message=${t.message}"
            )
            LogUtil.exportLog("topTabCreateFailed stage=$lastTopAttachStage type=${t.javaClass.name} message=${t.message}")
            LogUtil.exportLog(android.util.Log.getStackTraceString(t))
            throw t
        }
    }

    fun addTabLayoutAtBottom(tabView: ViewGroup, height: Int) {
        RuntimeProbe.append(tabView.context, "TabLayout bottom start height=$height")
        val tabLayout = newTabLayout(tabView, Gravity.TOP, 5f)

        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val viewChild = tabView.getChildAt(0) as ViewGroup
        params.height = height
        mainThread {
            Objects.Main.tabLayout = tabLayout
            tabLayout.background = NightModeUtils.getForegroundDrawable(
                tabLayout.resources,
                BackgroundImageHook.getTabLayoutBitmapAtBottom(params.height, 0)
            )
        }
        viewChild.addView(tabLayout, 4, params)
        tabLayout.bringToFront()
        viewChild.requestLayout()
        viewChild.invalidate()
        try {
            Objects.Main.LauncherUI_mTabLayout = tabLayout
            LogUtil.log("add table layout success")
            for (index in 0..3) {
                viewChild.getChildAt(index).visibility = View.GONE
            }
            RuntimeProbe.append(tabView.context, "TabLayout bottom addViewDone")
        } catch (e: Exception) {
            LogUtil.log(e)
            RuntimeProbe.append(tabView.context, "TabLayout bottom failed ${e.javaClass.name}:${e.message}")
        }
    }

    fun addTabLayout(viewPagerLinearLayout: ViewGroup) {
        markTopAttachStage("start")
        val context = ModuleContextCompat.wrap(viewPagerLinearLayout.context)
        val resContext = viewPagerLinearLayout.context
        val tabElevation = if (!HookConfig.is_hook_bg_immersion && HookConfig.is_hook_tab_elevation) 5F else 0F
        val topTabBaseHeight = ConvertUtils.dp2px(resContext, 60f)

        markTopAttachStage("newTabLayout")
        val tabLayout = newTabLayout(viewPagerLinearLayout, Gravity.BOTTOM, tabElevation)

        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.height = topTabBaseHeight
        BackgroundImageHook._tabLayoutOnTopOffset = params.height
        Objects.Main.tabLayout = tabLayout
        BackgroundImageHook._tabLayoutOnTop = true
        if (!HookConfig.is_hook_bg_immersion) {
            markTopAttachStage("setTabLayoutBitmap")
            BackgroundImageHook.setTabLayoutBitmap(0)
        }
        when {
            WechatGlobal.wxVersion!! < Version("6.7.2") -> {
                markTopAttachStage("legacyAddPre672")
                viewPagerLinearLayout.addView(tabLayout, 0, params)
            }

            WechatGlobal.wxVersion!! < Version("7.0.0") -> {
                markTopAttachStage("legacyAddPre700")
                val mockLayout = FrameLayout(context)
                val paddingTop = if (HookConfig.is_hook_hide_actionbar) 0 else topTabBaseHeight
                mockLayout.setPadding(0, paddingTop, 0, 0)
                val viewpager = viewPagerLinearLayout.getChildAt(0)
                viewPagerLinearLayout.removeViewAt(0)
                mockLayout.addView(tabLayout, params)
                mockLayout.addView(viewpager)
                viewPagerLinearLayout.addView(mockLayout, 0)
            }

            else -> {
                markTopAttachStage("prepareAsyncAttach")
                val cb = { actionHeight: Int ->
                    try {
                        markTopAttachStage("asyncCallback")
                        val statusBarOffset = resolveTopInset(viewPagerLinearLayout.rootView)
                        params.topMargin = actionHeight + statusBarOffset
                        if (WechatGlobal.wxVersion!! == Version("7.0.0")) {
                            val statusView = View(context)
                            statusView.background = ColorDrawable(StatusBarHooker.getStatusBarColor())
                            statusView.elevation = 1F
                            val statusParam = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            statusParam.topMargin = 0
                            statusParam.height = HookConfig.statusBarHeight
                            viewPagerLinearLayout.addView(statusView, 0, statusParam)
                        }
                        viewPagerLinearLayout.setPadding(0, 0, 0, 0)
                        viewPagerLinearLayout.requestLayout()
                        if ((WechatGlobal.wxVersion!! >= Version("7.0.7")) && (!HookConfig.is_hook_hide_actionbar)) {
                            val paramsAddedOnTop = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            paramsAddedOnTop.height = topTabBaseHeight
                            paramsAddedOnTop.topMargin = actionHeight + statusBarOffset - topTabBaseHeight
                            val view = FrameLayout(context)
                            view.background = NightModeUtils.getForegroundDrawable(
                                view.resources,
                                BackgroundImageHook.getActionBarBitmap(view.measuredHeight, Objects.Main.pagePosition)
                            )
                            viewPagerLinearLayout.addView(view, 1, paramsAddedOnTop)
                            Objects.Main.actionBarAppbrandFix = view
                        }
                        markTopAttachStage("overlayAddView")
                        val attachIndex = 2.coerceAtMost(viewPagerLinearLayout.childCount)
                        RuntimeProbe.append(
                            viewPagerLinearLayout.context,
                            "topTabAttach parent=${viewPagerLinearLayout.javaClass.name} index=$attachIndex topMargin=${params.topMargin} height=${params.height} statusBarOffset=$statusBarOffset"
                        )
                        viewPagerLinearLayout.addView(tabLayout, attachIndex, params)
                        markTopAttachStage("overlayAttached")
                        tabLayout.bringToFront()
                        viewPagerLinearLayout.requestLayout()
                        viewPagerLinearLayout.invalidate()
                        ensureAttachedLater(viewPagerLinearLayout, tabLayout, attachIndex, params, 300)
                        ensureAttachedLater(viewPagerLinearLayout, tabLayout, attachIndex, params, 900)
                        viewPagerLinearLayout.postDelayed({
                            if (tabLayout.parent == null) {
                                markTopAttachStage("postCheckParentNull")
                                LogUtil.toast("topTabStage:postCheckParentNull", true)
                            }
                            RuntimeProbe.append(
                                viewPagerLinearLayout.context,
                                "topTabVisible parent=${(tabLayout.parent as? ViewGroup)?.javaClass?.name} " +
                                    "size=${tabLayout.width}x${tabLayout.height} y=${tabLayout.y} alpha=${tabLayout.alpha} vis=${tabLayout.visibility}"
                            )
                        }, 1500)
                    } catch (t: Throwable) {
                        markTopAttachStage("asyncCallbackFailed")
                        LogUtil.log(t)
                        LogUtil.toast("topTabAsyncFail:async:${t.javaClass.simpleName}", true)
                    }
                }
                markTopAttachStage("waitActionBarHeight")
                val actionBarLayout = ViewUtils.getParentView(viewPagerLinearLayout, 3) as? ViewGroup
                val actionBarView = actionBarLayout?.getChildAt(1)
                fun waitForActionBarHeight(attempt: Int = 0) {
                    val actionHeight = if (HookConfig.is_hook_hide_actionbar) {
                        0
                    } else {
                        val containerHeight = actionBarView?.height ?: 0
                        val measuredHeight = actionBarView?.measuredHeight ?: 0
                        val hookedHeight = try {
                            (XposedHelpers.callMethod(Objects.Main.HomeUI_mActionBar, "getHeight") as? Int) ?: 0
                        } catch (_: Throwable) {
                            0
                        }
                        maxOf(containerHeight, measuredHeight, hookedHeight)
                    }
                    if (HookConfig.is_hook_hide_actionbar || actionHeight > 0 || attempt >= 20) {
                        RuntimeProbe.append(
                            viewPagerLinearLayout.context,
                            "topTabActionBarHeight attempt=$attempt resolved=$actionHeight " +
                                "container=${actionBarView?.height ?: -1} measured=${actionBarView?.measuredHeight ?: -1}"
                        )
                        cb(actionHeight)
                    } else {
                        mainThread(100) {
                            waitForActionBarHeight(attempt + 1)
                        }
                    }
                }
                mainThread {
                    waitForActionBarHeight()
                }
            }
        }
        LogUtil.log("add table layout success")
        Objects.Main.LauncherUI_mTabLayout = tabLayout
        markTopAttachStage("postAddActionBar")
        val actionBarLayout = ViewUtils.getParentView(viewPagerLinearLayout, 3) as ViewGroup
        val actionBar = actionBarLayout.getChildAt(1)
        actionBar.elevation = 0F
        if (HookConfig.is_tab_layout_on_top) {
            markTopAttachStage("hideActionBar")
            val rootView = viewPagerLinearLayout.rootView
            forceHideLegacyTopBar(rootView, actionBar, "initial")
            rootView.postDelayed({ forceHideLegacyTopBar(rootView, actionBar, "delay200") }, 200)
            rootView.postDelayed({ forceHideLegacyTopBar(rootView, actionBar, "delay600") }, 600)
            rootView.postDelayed({
                forceHideLegacyTopBar(rootView, actionBar, "delay1200")
                tabLayout.bringToFront()
                viewPagerLinearLayout.bringChildToFront(tabLayout)
                viewPagerLinearLayout.requestLayout()
                viewPagerLinearLayout.invalidate()
            }, 1200)
        }
        markTopAttachStage("done")
    }
}
