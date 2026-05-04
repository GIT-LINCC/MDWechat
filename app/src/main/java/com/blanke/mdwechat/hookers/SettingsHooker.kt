package com.blanke.mdwechat.hookers

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.graphics.drawable.NinePatchDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.TextView
import com.blanke.mdwechat.CC
import com.blanke.mdwechat.Classes
import com.blanke.mdwechat.Fields.PreferenceFragment_mListView
import com.blanke.mdwechat.WeChatHelper
import com.blanke.mdwechat.Version
import com.blanke.mdwechat.WechatGlobal
import com.blanke.mdwechat.config.AppCustomConfig
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.hookers.base.Hooker
import com.blanke.mdwechat.hookers.base.HookerProvider
import com.blanke.mdwechat.hookers.main.BackgroundImageHook
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.NightModeUtils
import com.blanke.mdwechat.util.SettingsHeaderBackgroundPolicy
import com.blanke.mdwechat.util.SettingsHeaderStyleResolver
import com.blanke.mdwechat.util.ViewUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SettingsHooker : HookerProvider {
    const val keyInit = "key_init"
    private const val keyOverlayRefreshRunnable = "key_settings_overlay_refresh_runnable"
    private const val keyApplyingHeaderTargetBackground = "key_settings_header_target_background"
    private val settingsHeaderBitmapTargetNames = setOf("gxv", "ovl", "hn7")
    private val settingsHeaderTransparentTargetNames = setOf("hyd", "o4v", "o4w")
    private val settingsHeaderTitleTargetNames = setOf("kbb")
    private val settingsHeaderContentTargetNames = setOf("ouv", "opx")

    override fun provideStaticHookers(): List<Hooker>? {
        return listOf(resumeHook)
    }

    private val resumeHook = Hooker {
        XposedHelpers.findAndHookMethod(Classes.Fragment, "performResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                val fragment = param?.thisObject ?: return
                if (fragment.javaClass.name != Classes.SettingsFragment.name) {
                    return
                }
                val isInit = XposedHelpers.getAdditionalInstanceField(fragment, keyInit)
                if (isInit != null) {
                    LogUtil.log("SettingsFragment 重新应用背景")
                } else {
                    XposedHelpers.setAdditionalInstanceField(fragment, keyInit, true)
                }
                if (shouldApplySettingsDecor()) {
                    init(fragment)
                }
            }

            private fun shouldApplySettingsDecor(): Boolean {
                return HookConfig.is_hook_tab_bg || SettingsHeaderBackgroundPolicy.shouldStyleStatusOverlay(
                    WechatGlobal.wxVersion,
                    HookConfig.is_settings_page_transparent
                )
            }

            private fun init(fragment: Any) {
                val listView = PreferenceFragment_mListView.get(fragment)
                if (listView != null && listView is View) {
                    if (HookConfig.is_hook_tab_bg) {
                        BackgroundImageHook.setSettingsBitmap(listView)
                    }
                    if (SettingsHeaderBackgroundPolicy.shouldStyleStatusOverlay(
                            WechatGlobal.wxVersion,
                            HookConfig.is_settings_page_transparent
                        )
                    ) {
                        scheduleSettingsStatusOverlayRefresh(listView)
                    }
                }
            }
        })
        if (WechatGlobal.wxVersion!! >= Version("7.0.0")) {
            XposedBridge.hookAllMethods(CC.View, "setBackground", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (view::class.java.name.contains("PullDownListView")) {
                        val drawable = param.args[0]
                        if (drawable is NinePatchDrawable) {//  设置页默认的drawable
                            param.result = null
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (enforceUnifiedHeaderSegmentStyle(view)) {
                        return
                    }
                    if (!SettingsHeaderBackgroundPolicy.shouldStyleStatusOverlay(
                            WechatGlobal.wxVersion,
                            HookConfig.is_settings_page_transparent
                        )
                    ) {
                        return
                    }
                    if (XposedHelpers.getAdditionalInstanceField(view, keyApplyingHeaderTargetBackground) == true) {
                        return
                    }
                    val targetName = getSettingsHeaderTargetName(view) ?: return
                    scheduleSettingsHeaderTargetRefresh(view, targetName)
                }
            })
            XposedBridge.hookAllMethods(CC.View, "setBackgroundColor", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (enforceUnifiedHeaderSegmentStyle(view)) {
                        return
                    }
                    val targetName = getSettingsRefreshTargetName(view) ?: return
                    scheduleSettingsHeaderTargetRefresh(view, targetName)
                }
            })
            XposedBridge.hookAllMethods(CC.View, "setBackgroundResource", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (enforceUnifiedHeaderSegmentStyle(view)) {
                        return
                    }
                    val targetName = getSettingsRefreshTargetName(view) ?: return
                    scheduleSettingsHeaderTargetRefresh(view, targetName)
                }
            })
            XposedBridge.hookAllMethods(ImageView::class.java, "setImageDrawable", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? ImageView ?: return
                    if (enforceUnifiedHeaderSegmentStyle(view)) {
                        return
                    }
                    val targetName = getSettingsRefreshTargetName(view) ?: return
                    scheduleSettingsHeaderTargetRefresh(view, targetName)
                }
            })
            XposedBridge.hookAllMethods(ImageView::class.java, "setImageBitmap", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? ImageView ?: return
                    if (enforceUnifiedHeaderSegmentStyle(view)) {
                        return
                    }
                    val targetName = getSettingsRefreshTargetName(view) ?: return
                    scheduleSettingsHeaderTargetRefresh(view, targetName)
                }
            })
            XposedBridge.hookAllMethods(ImageView::class.java, "setImageResource", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? ImageView ?: return
                    if (enforceUnifiedHeaderSegmentStyle(view)) {
                        return
                    }
                    val targetName = getSettingsRefreshTargetName(view) ?: return
                    scheduleSettingsHeaderTargetRefresh(view, targetName)
                }
            })
        }
    }

    private fun scheduleSettingsStatusOverlayRefresh(listView: View, attempt: Int = 0) {
        val pending = XposedHelpers.getAdditionalInstanceField(listView, keyOverlayRefreshRunnable) as? Runnable
        if (attempt == 0 && pending != null) {
            listView.removeCallbacks(pending)
            XposedHelpers.removeAdditionalInstanceField(listView, keyOverlayRefreshRunnable)
        }
        val runnable = Runnable {
            val overlay = findSettingsStatusOverlay(listView)
            if (overlay != null && overlay.width > 0 && overlay.height > 0) {
                applySettingsStatusOverlayBackground(overlay)
                XposedHelpers.removeAdditionalInstanceField(listView, keyOverlayRefreshRunnable)
                return@Runnable
            }
            if (attempt >= 20) {
                XposedHelpers.removeAdditionalInstanceField(listView, keyOverlayRefreshRunnable)
                return@Runnable
            }
            scheduleSettingsStatusOverlayRefresh(listView, attempt + 1)
        }
        XposedHelpers.setAdditionalInstanceField(listView, keyOverlayRefreshRunnable, runnable)
        listView.postDelayed(runnable, if (attempt == 0) 0L else 80L)
    }

    fun refreshSettingsStatusOverlayFromListChild(listChild: View) {
        if (!SettingsHeaderBackgroundPolicy.shouldStyleStatusOverlay(
                WechatGlobal.wxVersion,
                HookConfig.is_settings_page_transparent
            )
        ) {
            return
        }
        val listView = findSettingsListView(listChild) ?: return
        scheduleSettingsStatusOverlayRefresh(listView)
    }

    private fun findSettingsListView(view: View): AbsListView? {
        var branch: View = view
        repeat(8) {
            val parent = branch.parent as? View ?: return null
            if (parent is AbsListView && parent.id == android.R.id.list) {
                return parent
            }
            branch = parent
        }
        return null
    }

    private fun findSettingsStatusOverlay(listView: View): ViewGroup? {
        var branch: View = listView
        repeat(4) {
            val parent = branch.parent as? ViewGroup ?: return@repeat
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChildAt(i)
                if (sibling === branch) {
                    continue
                }
                val candidate = sibling as? ViewGroup ?: continue
                if (SettingsHeaderBackgroundPolicy.isStatusOverlayContainerCandidate(
                        candidate.getChildAt(0)?.javaClass?.name,
                        candidate.getChildAt(1)?.javaClass?.name,
                        candidate.childCount,
                        candidate.width,
                        candidate.height,
                        listView.width,
                        listView.height
                    )
                ) {
                    LogUtil.log("SettingsHeader overlay found")
                    return candidate
                }
            }
            branch = parent
        }
        return null
    }

    private fun applySettingsStatusOverlayBackground(overlay: ViewGroup) {
        overlay.background = WeChatHelper.drawableTransparent
        overlay.getChildAt(1)?.background = WeChatHelper.drawableTransparent
        val carrier = overlay.getChildAt(0) as? ImageView ?: return
        prepareSettingsStatusOverlayCarrier(carrier)
        carrier.background = WeChatHelper.drawableTransparent
        carrier.alpha = 0f
    }

    private fun scheduleSettingsHeaderTargetRefresh(view: View, targetName: String, attempt: Int = 0) {
        view.postDelayed({
            if (view.width <= 0 || view.height <= 0) {
                if (attempt < 10) {
                    scheduleSettingsHeaderTargetRefresh(view, targetName, attempt + 1)
                }
                return@postDelayed
            }
            applySettingsHeaderTargetBackground(view, targetName)
        }, if (attempt == 0) 0L else 80L)
    }

    private fun applySettingsHeaderTargetBackground(view: View, targetName: String) {
        LogUtil.log("SettingsHeader target apply: $targetName size=${view.width}x${view.height}")
        XposedHelpers.setAdditionalInstanceField(view, keyApplyingHeaderTargetBackground, true)
        try {
            if (view is ImageView) {
                view.setImageDrawable(null)
                view.clearColorFilter()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    view.imageTintList = null
                }
            }
            if (targetName in settingsHeaderTransparentTargetNames || HookConfig.is_settings_page_transparent) {
                view.background = WeChatHelper.drawableTransparent
                applySettingsHeaderTextColors(view)
                scheduleSettingsHeaderTextColorRefresh(view)
                return
            }
            if (!HookConfig.is_hook_bg_immersion) {
                view.background = if (NightModeUtils.isWechatNightMode()) {
                    ColorDrawable(WeChatHelper.wechatDark)
                } else {
                    ColorDrawable(WeChatHelper.wechatWhite)
                }
                applySettingsHeaderTextColors(view)
                scheduleSettingsHeaderTextColorRefresh(view)
                return
            }
            BackgroundImageHook.setBackgroundBitmap("设置页头部[$targetName]", view, AppCustomConfig.getTabBg(3), null)
            applySettingsHeaderTextColors(view)
            scheduleSettingsHeaderTextColorRefresh(view)
        } finally {
            view.post {
                XposedHelpers.removeAdditionalInstanceField(view, keyApplyingHeaderTargetBackground)
            }
        }
    }

    private fun prepareSettingsStatusOverlayCarrier(carrier: ImageView) {
        if (!SettingsHeaderBackgroundPolicy.shouldReplaceStatusOverlayCarrier(
                WechatGlobal.wxVersion,
                carrier.width,
                carrier.height
            )
        ) {
            return
        }
        carrier.setImageDrawable(null)
        carrier.clearColorFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            carrier.imageTintList = null
        }
    }

    private fun enforceUnifiedHeaderSegmentStyle(view: View): Boolean {
        if (!SettingsHeaderBackgroundPolicy.shouldUseUnifiedHeaderBackground(
                WechatGlobal.wxVersion,
                HookConfig.is_settings_page_transparent
            )
        ) {
            return false
        }
        if (XposedHelpers.getAdditionalInstanceField(view, keyApplyingHeaderTargetBackground) == true) {
            return true
        }
        val resourceName = getResourceEntryName(view) ?: return false
        if (!SettingsHeaderStyleResolver.shouldKeepSegmentTransparent(resourceName)) {
            return false
        }
        XposedHelpers.setAdditionalInstanceField(view, keyApplyingHeaderTargetBackground, true)
        try {
            if (view is ImageView) {
                view.setImageDrawable(null)
                view.clearColorFilter()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    view.imageTintList = null
                }
            }
            view.background = WeChatHelper.drawableTransparent
            view.alpha = 1f
        } finally {
            view.post {
                XposedHelpers.removeAdditionalInstanceField(view, keyApplyingHeaderTargetBackground)
            }
        }
        return true
    }

    private fun getSettingsHeaderTargetName(view: View): String? {
        val targetName = getResourceEntryName(view)
        return when {
            targetName in settingsHeaderBitmapTargetNames -> targetName
            targetName in settingsHeaderTransparentTargetNames -> targetName
            else -> null
        }
    }

    private fun getSettingsRefreshTargetName(view: View): String? {
        if (!SettingsHeaderBackgroundPolicy.shouldStyleStatusOverlay(
                WechatGlobal.wxVersion,
                HookConfig.is_settings_page_transparent
            )
        ) {
            return null
        }
        if (XposedHelpers.getAdditionalInstanceField(view, keyApplyingHeaderTargetBackground) == true) {
            return null
        }
        return getSettingsHeaderTargetName(view)
    }

    private fun applySettingsHeaderTextColors(root: View) {
        val resourceName = getResourceEntryName(root)
        when {
            resourceName in settingsHeaderTitleTargetNames -> {
                try {
                    XposedHelpers.callMethod(root, "setTextColor", HookConfig.get_main_text_color_title)
                } catch (_: Throwable) {
                    if (root is TextView) {
                        root.setTextColor(HookConfig.get_main_text_color_title)
                    }
                }
            }
            resourceName in settingsHeaderContentTargetNames && root is TextView -> {
                root.setTextColor(HookConfig.get_main_text_color_content)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applySettingsHeaderTextColors(root.getChildAt(i))
            }
        }
    }

    private fun scheduleSettingsHeaderTextColorRefresh(root: View, attempt: Int = 0) {
        root.postDelayed({
            applySettingsHeaderTextColors(root)
            if (attempt < 4) {
                scheduleSettingsHeaderTextColorRefresh(root, attempt + 1)
            }
        }, 120L)
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
}
