package com.blanke.mdwechat.hookers

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.blanke.mdwechat.*
import com.blanke.mdwechat.Classes.LauncherUIBottomTabView
import com.blanke.mdwechat.Classes.MainTabUIPageAdapter
import com.blanke.mdwechat.Classes.WxViewPager
import com.blanke.mdwechat.Fields.HomeUI_mMainTabUI
import com.blanke.mdwechat.Fields.LauncherUI_mHomeUI
import com.blanke.mdwechat.Fields.MainTabUI_mCustomViewPager
import com.blanke.mdwechat.Methods.MainTabUIPageAdapter_onPageScrolled
import com.blanke.mdwechat.Methods.WxViewPager_selectedPage
import com.blanke.mdwechat.Objects.Main.LauncherUI_mTabLayout
import com.blanke.mdwechat.config.AppCustomConfig
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.hookers.base.Hooker
import com.blanke.mdwechat.hookers.base.HookerProvider
import com.blanke.mdwechat.hookers.main.BackgroundImageHook
import com.blanke.mdwechat.hookers.main.FloatMenuHook
import com.blanke.mdwechat.hookers.main.HomeActionBarHook
import com.blanke.mdwechat.hookers.main.TabLayoutHook
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.RuntimeProbe
import com.blanke.mdwechat.util.TabLayoutIndicatorPolicy
import com.blanke.mdwechat.util.ViewUtils
import com.blanke.mdwechat.util.ViewUtils.measureHeight
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.Collections
import java.util.IdentityHashMap


object LauncherUIHooker : HookerProvider {
    const val keyInit = "key_init"

    override fun provideStaticHookers(): List<Hooker>? {
        return listOf(
//                launcherLifeHooker,
                mainTabUIPageAdapterHook, actionMenuHooker)
    }

    val launcherLifeHooker = Hooker {
//        XposedHelpers.findAndHookMethod(CC.Activity, "onDestroy", object : XC_MethodHook() {
//            override fun afterHookedMethod(param: MethodHookParam) {
//                val activity = param.thisObject as? Activity ?: return
//                if (activity::class.java != Classes.LauncherUI) {
//                    return
//                }
////                if (!LogUtil.logStackTraceXp("onRecreate")) {
////                    LogUtil.logXp("\n\n\n\nLauncherUI onDestroy()")
//                Objects.clear()
////                }
//            }
//        })

        XposedHelpers.findAndHookMethod(CC.Activity, "onPostResume",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return

                        //判断是否为朋友圈, 若是则不修改actionbar
                        if (activity::class.java == Classes.LauncherUI) {
                            Objects.Main.activityNow = Classes.LauncherUI
                            Objects.Main.statusView?.elevation = 1f
                        } else if (activity::class.java == Classes.SnsTimeLineUI) {
                            Objects.Main.activityNow = Classes.SnsTimeLineUI
                            Objects.Main.actionBar?.background = ColorDrawable(Color.TRANSPARENT)
                            Objects.Main.statusView?.elevation = 0f
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        handleLauncherResumed(activity, "Activity.onPostResume")
                    }
                })
        try {
            XposedBridge.hookAllMethods(Classes.LauncherUI, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    handleLauncherResumed(activity, "LauncherUI.onResumeDirect")
                }
            })
        } catch (t: Throwable) {
            LogUtil.log(t)
        }
    }

    private fun handleLauncherResumed(activity: Activity, source: String) {
        LogUtil.log("$source activity = $activity")
        RuntimeProbe.append(activity, "$source activity=${activity::class.java.name}")

        try {
            val activityClassLoader = activity::class.java.classLoader
            if (activityClassLoader != null && activityClassLoader != WechatGlobal.wxLoader) {
                RuntimeProbe.append(activity, "$source classLoaderMismatch old=${WechatGlobal.wxLoader} new=$activityClassLoader")
                LogUtil.toast("加载错误，请打开 MDWechat 主界面的 [play 开关] (详情请查看日志)。", true)
                LogUtil.log("微信使用的classloader = $activityClassLoader")
                LogUtil.log("MDWechat使用的classloader = ${WechatGlobal.wxLoader}")
                LogUtil.log("\n\n==============================================================================")
                LogUtil.log("\n  ClassLoader加载错误，请打开 MDWechat 主界面的 [play 开关] (如已打开请忽略)。\n")
                LogUtil.log("==============================================================================\n\n")
                WechatGlobal.wxLoader = activityClassLoader
                Classes.setLauncherUI()
            }
            WechatGlobal.preloaded = true

            if (activity::class.java != Classes.LauncherUI) {
                RuntimeProbe.append(activity, "$source skipped class=${activity::class.java.name}")
                return
            }
            WeChatHelper.reloadPrefs()
            val isInit = XposedHelpers.getAdditionalInstanceField(activity, keyInit)
            if (isInit != null) {
                LogUtil.log("LauncherUI 已经hook过")
                RuntimeProbe.append(activity, "$source alreadyInit")
                return
            }
            LogUtil.log("LauncherUI onResume(), start hook")
            RuntimeProbe.clear(activity)
            RuntimeProbe.append(activity, "$source initStart")
            initHookLauncherUI(activity, source)
        } catch (t: Throwable) {
            RuntimeProbe.append(activity, "$source failed ${t.javaClass.name}:${t.message}")
            throw t
        }
    }

    private fun initHookLauncherUI(activity: Activity, source: String) {
        try {
            val density = activity.resources.displayMetrics.density
            AppCustomConfig.bitmapScale = density / 3F

            Objects.Main.LauncherUI = activity
            RuntimeProbe.append(activity, "$source bitmapScale=${AppCustomConfig.bitmapScale}")
            val homeUI = LauncherUI_mHomeUI.get(activity)
            RuntimeProbe.append(activity, "$source homeUIField=${LauncherUI_mHomeUI.name} value=${homeUI?.javaClass?.name}")
            val mainTabUI = HomeUI_mMainTabUI.get(homeUI)
            RuntimeProbe.append(activity, "$source mainTabField=${HomeUI_mMainTabUI.name} value=${mainTabUI?.javaClass?.name}")
            val viewPager = MainTabUI_mCustomViewPager.get(mainTabUI)
            RuntimeProbe.append(activity, "$source viewPagerField=${MainTabUI_mCustomViewPager.name} value=${viewPager?.javaClass?.name}")
            if (viewPager == null || viewPager !is View) {
                LogUtil.log("MainTabUI_mCustomViewPager == null return;")
                RuntimeProbe.append(activity, "$source viewPagerInvalid")
                return
            }

            val linearViewGroup = viewPager.parent as ViewGroup
            BackgroundImageHook.contactPageParent = linearViewGroup
            val contentViewGroup = linearViewGroup.parent as ViewGroup
            Objects.Main.LauncherUI_mContentLayout = contentViewGroup

            val mActionBar = Fields.HomeUI_mActionBar.get(homeUI)
            RuntimeProbe.append(activity, "$source actionBarField=${Fields.HomeUI_mActionBar.name} value=${mActionBar?.javaClass?.name}")
            val actionBarContainer = findNestedInstanceByType(mActionBar, Classes.ActionBarContainer)
                    ?: throw NoSuchElementException("ActionBarContainer not found in ${mActionBar::class.java.name}; fields=${describeFields(mActionBar::class.java)}")
            Objects.Main.HomeUI_mActionBar = actionBarContainer
            LogUtil.log("HomeUI_mActionBar = ${Objects.Main.HomeUI_mActionBar}")
            RuntimeProbe.append(activity, "$source actionBarContainerValue=${actionBarContainer.javaClass.name}")

            Objects.Main.LauncherUI_mViewPager = viewPager

            val is_hook_tab = !HookConfig.is_key_hide_tab && HookConfig.is_hook_tab
            val isTabLayoutOnBottom = is_hook_tab && !HookConfig.is_tab_layout_on_top
            val isTabLayoutOnTop = is_hook_tab && HookConfig.is_tab_layout_on_top
            val isKeyHideTab = isTabLayoutOnTop || (!is_hook_tab && HookConfig.is_key_hide_tab)
            val shouldFix = isTabLayoutOnTop || HookConfig.is_hook_hide_actionbar
            val floatButtonMarginBottom = if (isTabLayoutOnBottom || (!isKeyHideTab)) 1 else 0

            val tabView = linearViewGroup.getChildAt(1) as ViewGroup
            val tabViewUnderneathHeight = measureHeight(tabView)
            if (BackgroundImageHook._tabLayoutHeightOnBottom < 0)
                BackgroundImageHook._tabLayoutHeightOnBottom = tabViewUnderneathHeight

            if (isKeyHideTab) {
                if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                    val bottomLine = tabView.getChildAt(0)
                    bottomLine.visibility = View.GONE
                    bottomLine.layoutParams.height = 0
                } else {
                    linearViewGroup.removeView(tabView)
                }
                LogUtil.log("移除 tabView $tabView")
            }

            when {
                isTabLayoutOnTop -> {
                    try {
                        LogUtil.log("添加 TabLayout")
                        TabLayoutHook.addTabLayout(linearViewGroup)
                        RuntimeProbe.append(activity, "$source topTabAdded")
                    } catch (e: Throwable) {
                        LogUtil.log("添加 TabLayout 报错")
                        LogUtil.log(e)
                        RuntimeProbe.append(activity, "$source topTabFailed ${e.javaClass.name}:${e.message}")
                    }
                }
                isTabLayoutOnBottom -> {
                    try {
                        LogUtil.log("添加底栏")
                        TabLayoutHook.addTabLayoutAtBottom(tabView, tabViewUnderneathHeight)
                        LogUtil.log("添加底栏成功")
                        RuntimeProbe.append(activity, "$source bottomTabAdded")
                    } catch (e: Throwable) {
                        LogUtil.log("添加底栏 报错")
                        LogUtil.log(e)
                        RuntimeProbe.append(activity, "$source bottomTabFailed ${e.javaClass.name}:${e.message}")
                    }
                }
                else -> {
                    LogUtil.log("不用添加 TabLayout")
                    BackgroundImageHook._tabLayoutLocation[1] = -1
                    RuntimeProbe.append(activity, "$source tabLayoutSkipped")
                }
            }
            if (shouldFix) {
                HomeActionBarHook.fix(linearViewGroup)
            }
            LogUtil.log("fix completed")

            if (HookConfig.is_hook_float_button) {
                try {
                    LogUtil.log("添加 FloatMenu")
                    FloatMenuHook.addFloatMenu(contentViewGroup, floatButtonMarginBottom * tabViewUnderneathHeight)
                    RuntimeProbe.append(activity, "$source floatMenuAdded")
                } catch (e: Throwable) {
                    LogUtil.log("添加 FloatMenu 报错")
                    LogUtil.log(e)
                    RuntimeProbe.append(activity, "$source floatMenuFailed ${e.javaClass.name}:${e.message}")
                }
            }
            XposedHelpers.setAdditionalInstanceField(activity, keyInit, true)
            LogUtil.log("LaunchUI Hook Completed.")
            RuntimeProbe.append(activity, "$source initDone tab=${HookConfig.is_hook_tab} tabBg=${HookConfig.is_hook_tab_bg} actionBarColor=${HookConfig.is_hook_actionbar_color} floatButton=${HookConfig.is_hook_float_button}")
        } catch (e: Exception) {
            RuntimeProbe.append(activity, "$source initFailed ${e.javaClass.name}:${e.message}")
            LogUtil.log(e)
        }
    }

    private fun allFields(clazz: Class<*>): Sequence<Field> {
        return generateSequence(clazz) { it.superclass }
                .takeWhile { it != Any::class.java }
                .flatMap { it.declaredFields.asSequence() }
    }

    private fun findNestedInstanceByType(root: Any, targetType: Class<*>, maxDepth: Int = 5): Any? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return findNestedInstanceByType(root, targetType, maxDepth, visited)
    }

    private fun findNestedInstanceByType(root: Any?, targetType: Class<*>, depth: Int, visited: MutableSet<Any>): Any? {
        root ?: return null
        if (!visited.add(root)) {
            return null
        }
        if (targetType.isAssignableFrom(root::class.java)) {
            return root
        }
        if (depth <= 0) {
            return null
        }
        allFields(root::class.java).forEach { field ->
            if (!shouldTraverse(field.type)) {
                return@forEach
            }
            field.isAccessible = true
            val value = runCatching { field.get(root) }.getOrNull() ?: return@forEach
            if (targetType.isAssignableFrom(value::class.java)) {
                return value
            }
            findNestedInstanceByType(value, targetType, depth - 1, visited)?.let {
                return it
            }
        }
        return null
    }

    private fun shouldTraverse(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isEnum || type.isArray) {
            return false
        }
        val typeName = type.name
        return !typeName.startsWith("java.lang.") &&
                !typeName.startsWith("kotlin.") &&
                !typeName.startsWith("android.util.")
    }

    private fun describeFields(clazz: Class<*>): String {
        return allFields(clazz)
                .take(20)
                .joinToString(",") { "${it.name}:${it.type.name}" }
    }

    private val mainTabUIPageAdapterHook = Hooker {
        XposedHelpers.findAndHookMethod(WxViewPager, WxViewPager_selectedPage.name, CC.Int, CC.Boolean, CC.Boolean, CC.Int, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val vp = param?.thisObject
                if (Objects.Main.LauncherUI_mViewPager == vp) {
                    val position = param?.args!![0] as Int
//                    log("WxViewPager_selectedPage position = $position , arg[1] =${param?.args!![1]}")
                    LauncherUI_mTabLayout?.currentTab = position
                    Objects.Main.pagePosition = position
                    BackgroundImageHook.setGuideBarBitmaps(position)
                }
            }
        })
        XposedHelpers.findAndHookMethod(MainTabUIPageAdapter, MainTabUIPageAdapter_onPageScrolled.name, CC.Int, Float::class.java, CC.Int, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                val positionOffset = param?.args!![1] as Float
                val position = param.args[0]
//                log("MainTabUIPageAdapter_onPageScrolled ,positionOffset=$positionOffset,startScrollPosition=$position")
                val normalizedOffset = TabLayoutIndicatorPolicy.normalizePositionOffset(positionOffset)
                LauncherUI_mTabLayout?.apply {
                    startScrollPosition = position as Int
                    indicatorOffset = normalizedOffset
                    Objects.Main.pagePosition = startScrollPosition
                    BackgroundImageHook.setGuideBarBitmaps(startScrollPosition)
                }
            }
        })

        XposedHelpers.findAndHookMethod(TextView::class.java, "setText", CharSequence::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val tv = param?.thisObject as View
                val text = param.args!![0]
                if (text == null || text !is String) {
                    return
                }
                val tabView = ViewUtils.getParentView(tv, 5)
                if (tabView != null && tabView.javaClass.name == LauncherUIBottomTabView.name) {
                    ViewUtils.getParentView(tv, 3)?.apply {
                        val tabViewItemParent = this.parent as ViewGroup
                        val position = tabViewItemParent.indexOfChild(this)
//                        log("unread position= $position,count = $text")
                        val number = if (text.length == 0) 0 else text.toIntOrNull()
                        LauncherUI_mTabLayout?.apply {
                            number?.apply {
                                showMsg(position, number)
                            }
                        }
                    }
                }
            }
        })
        XposedHelpers.findAndHookMethod(View::class.java, "setVisibility", CC.Int, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !is ImageView) {
                    return
                }
                val visible = param.args!![0] as Int
                val view = param.thisObject as ImageView
                val tabView = ViewUtils.getParentView(view, 5)
                if (tabView != null && tabView.javaClass.name == LauncherUIBottomTabView.name) {
                    ViewUtils.getParentView(view, 3)?.apply {
                        val tabViewItemParent = this.parent as ViewGroup
                        val position = tabViewItemParent.indexOfChild(this)
//                        log("unread position= $position,visible = ${visible == View.VISIBLE}")
                        LauncherUI_mTabLayout?.apply {
                            if (visible == View.VISIBLE) {
                                showMsg(position, -1)
                            } else if (!hasMsg(position)) {
                                showMsg(position, 0)
                            }
                        }
                    }
                }
            }
        })
    }

    private
    val actionMenuHooker = Hooker {
        //hide menu item in actionBar
        XposedHelpers.findAndHookMethod(Classes.LauncherUI, "onCreateOptionsMenu", CC.Menu, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!HookConfig.is_hook_float_button) {
                    return
                }
                val menu = param.args[0] as Menu
                menu.removeItem(2)
            }
        })
        XposedBridge.hookAllMethods(Classes.ActionMenuView, "add", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.size != 4 || param.result !is MenuItem) {
                    return
                }
                val str = param.args[3]
                val menuItem = param.result as MenuItem
                if (str == "微X模块") {
                    LogUtil.log("检测到 微X模块")
                    menuItem.isVisible = false
                    Objects.Main.LauncherUI_mWechatXMenuItem = menuItem
                }
            }
        })
    }
}
