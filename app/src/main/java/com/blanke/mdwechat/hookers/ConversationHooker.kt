package com.blanke.mdwechat.hookers

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ListView
import com.blanke.mdwechat.CC
import com.blanke.mdwechat.Classes
import com.blanke.mdwechat.Classes.ConversationListView
import com.blanke.mdwechat.Classes.ConversationWithAppBrandListView
import com.blanke.mdwechat.Fields.ConversationFragment_mListView
import com.blanke.mdwechat.Methods.ConversationWithAppBrandListView_isAppBrandHeaderEnable
import com.blanke.mdwechat.Version
import com.blanke.mdwechat.WeChatHelper.createItemRippleDrawable
import com.blanke.mdwechat.WechatGlobal
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.hookers.base.Hooker
import com.blanke.mdwechat.hookers.base.HookerProvider
import com.blanke.mdwechat.hookers.main.BackgroundImageHook
import com.blanke.mdwechat.util.ConversationRipplePolicy
import com.blanke.mdwechat.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.blanke.mdwechat.ViewTreeRepoThisVersion as VTTV

//小程序字体颜色在 ListViewHooker 中
object ConversationHooker : HookerProvider {
    const val keyInit = "key_init"
    private const val keyConversationResetRunnable = "mdwechat_conversation_reset_runnable"

    private fun shouldSuppressNativeConversationFeedback(view: View): Boolean {
        return ConversationRipplePolicy.shouldSuppressNativeConversationFeedback(
                WechatGlobal.wxVersion,
                Build.VERSION.SDK_INT,
                HookConfig.is_hook_ripple,
                view.javaClass.name == VTTV.ConversationListViewItem.item.clazz
        )
    }

    override fun provideStaticHookers(): List<Hooker>? {
        return listOf(
                resumeHook,
                disableAppBrandHook,
                conversationRowRippleHook,
                headViewHook
        )
    }

    private val resumeHook = Hooker {
        XposedHelpers.findAndHookMethod(Classes.Fragment, "performResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
//                LogUtil.log("Fragment.performResume f=${param!!.thisObject::class.java.name}")
                val fragment = param?.thisObject ?: return
                if (fragment.javaClass.name != Classes.ConversationFragment.name) {
                    return
                }
                val currentListView = ConversationFragment_mListView.get(fragment)
                if (HookConfig.is_hook_ripple && currentListView is View) {
                    currentListView.post {
                        ListViewHooker.resetVisibleConversationRows(currentListView)
                    }
                }
//                LogUtil.logSuperClasses(param!!.thisObject::class.java)
                val isInit = XposedHelpers.getAdditionalInstanceField(fragment, keyInit)
                if (isInit != null) {
                    LogUtil.log("ConversationFragment 已经hook过")
                    return
                }
                XposedHelpers.setAdditionalInstanceField(fragment, keyInit, true)
                if (HookConfig.is_hook_tab_bg) {
                    init(fragment)
                }
            }

            private fun init(fragment: Any) {
                val listView = ConversationFragment_mListView.get(fragment)
                if (listView != null && listView is View) {
                    BackgroundImageHook.setConversationBitmap(listView)
                }
            }
        })
        XposedBridge.hookAllMethods(CC.View, "setBackgroundColor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (shouldSuppressNativeConversationFeedback(view)) {
                    param.args[0] = Color.TRANSPARENT
                    return
                }
                val clazz = view::class.java.name
//                    LogUtil.logXp("=====================")
//                    LogUtil.logViewStackTracesXp(ViewUtils.getParentViewSafe(view, 15))
//                    LogUtil.logStackTraceXp()
//                    LogUtil.logXp("=====================")
                when (clazz) {
                    ConversationListView.name -> if (WechatGlobal.wxVersion!! >= Version("7.0.3") && HookConfig.is_hook_tab_bg) {
                        param.result = 0
                    }
                }
            }
        })
        if (WechatGlobal.wxVersion!! >= Version("7.0.4")) {
            XposedBridge.hookAllMethods(CC.View, "setBackground", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (shouldSuppressNativeConversationFeedback(view)) {
                        param.args[0] = ColorDrawable(Color.TRANSPARENT)
                        return
                    }
                    if (WechatGlobal.wxVersion!! >= Version("8.0.49")) {
                        return
                    }
                    val pView = view.parent
                    if ((pView is View) && (pView::class.java.name == ConversationListView.name)) {
                        param.result = null
                    }
                }
            })
        }
    }

    //7.0.3以上无法使用
    private val disableAppBrandHook = Hooker {
        if (WechatGlobal.wxVersion!! <= Version("7.0.3")) {
            XposedHelpers.findAndHookMethod(ConversationWithAppBrandListView,
                    ConversationWithAppBrandListView_isAppBrandHeaderEnable.name, CC.Boolean, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (HookConfig.is_hook_remove_appbrand) {
                        try {
                            if (WechatGlobal.wxVersion!! >= Version("6.7.2")) {
                                val listView = param.thisObject as ListView
                                val mHeaderViewInfos = XposedHelpers.getObjectField(listView, "mHeaderViewInfos") as List<*>
                                if (mHeaderViewInfos.isNotEmpty()) {
                                    val firstHeadView = mHeaderViewInfos[0] as ListView.FixedViewInfo
                                    val mAppBrandDesktopHalfContainer = firstHeadView.view as ViewGroup
//                                LogUtil.log("firstHeadView=${firstHeadView.view}")
                                    if (mAppBrandDesktopHalfContainer::class.java.name.contains("AppBrandDesktopHalfContainer")) {
                                        mAppBrandDesktopHalfContainer.getChildAt(1)?.visibility = View.GONE
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            LogUtil.log(t)
                        }
                        param.result = false
                    }
                }
            })
        }
    }

    private val conversationRowRippleHook = Hooker {
        XposedBridge.hookAllMethods(CC.View, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val row = param?.thisObject as? View ?: return
                if (row.javaClass.name != VTTV.ConversationListViewItem.item.clazz) {
                    return
                }
                val event = param.args[0] as? MotionEvent ?: return
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelConversationRowReset(row)
                        ListViewHooker.ensureConversationItemRipple(row)
                        ListViewHooker.showConversationItemRipple(row, event.x, event.y)
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam?) {
                val row = param?.thisObject as? View ?: return
                if (row.javaClass.name != VTTV.ConversationListViewItem.item.clazz) {
                    return
                }
                val event = param.args[0] as? MotionEvent ?: return
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        ListViewHooker.releaseConversationItemRipple(row)
                        scheduleConversationRowReset(row, 220L)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelConversationRowReset(row)
                        ListViewHooker.resetConversationItemState(row)
                    }
                }
            }
        })
    }

    private fun cancelConversationRowReset(row: View) {
        val pending = XposedHelpers.getAdditionalInstanceField(row, keyConversationResetRunnable) as? Runnable ?: return
        row.removeCallbacks(pending)
        XposedHelpers.removeAdditionalInstanceField(row, keyConversationResetRunnable)
    }

    private fun scheduleConversationRowReset(row: View, delayMillis: Long) {
        cancelConversationRowReset(row)
        val runnable = Runnable {
            ListViewHooker.resetConversationItemState(row)
            XposedHelpers.removeAdditionalInstanceField(row, keyConversationResetRunnable)
        }
        XposedHelpers.setAdditionalInstanceField(row, keyConversationResetRunnable, runnable)
        if (delayMillis > 0) {
            row.postDelayed(runnable, delayMillis)
        } else {
            row.post(runnable)
        }
    }

    private val headViewHook = Hooker {
        XposedHelpers.findAndHookMethod(CC.ListView, "addHeaderView", CC.View, CC.Object, CC.Boolean,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        val listView = param?.thisObject
                        if (WechatGlobal.wxVersion!! < Version("7.0.3")) {
                            if (listView?.javaClass?.name != ConversationWithAppBrandListView.name) {
                                return
                            }
                        } else {
                            if (listView?.javaClass?.name != ConversationListView.name) {
                                return
                            }
                        }
                        val view = param?.args!![0] as View
//                        LogUtil.log("ConversationWithAppBrandListView addHeadView = ${view}")
                        if (view is ViewGroup && view.getChildAt(0) != null) {
                            LogUtil.logOnlyOnce("addHeaderView")
                            view.getChildAt(0).viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                                override fun onGlobalLayout() {
                                    val child = view.getChildAt(0)
                                    if (WechatGlobal.wxVersion!! < Version("8.0.49")) {
                                        child.background = createItemRippleDrawable()
                                    }
                                    child.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                }
                            })
                            LogUtil.logOnlyOnce("addHeaderView Done")
                        }
                    }
                })
    }

}
