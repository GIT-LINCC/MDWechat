package com.blanke.mdwechat.hookers

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import com.blanke.mdwechat.*
import com.blanke.mdwechat.Fields.ContactFragment_mListView
import com.blanke.mdwechat.WeChatHelper.drawableTransparent
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.hookers.base.Hooker
import com.blanke.mdwechat.hookers.base.HookerProvider
import com.blanke.mdwechat.hookers.main.BackgroundImageHook
import com.blanke.mdwechat.util.ContactPageStyleResolver
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.NightModeUtils
import com.blanke.mdwechat.util.ViewTreeUtils
import com.blanke.mdwechat.util.ViewUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.getObjectField
import com.blanke.mdwechat.ViewTreeRepoThisVersion as VTTV

object ContactHooker : HookerProvider {
    const val keyInit = "key_init"
    private const val keyContactRoot = "mdwechat_contact_root"
    private const val keyContactChromeApplied = "mdwechat_contact_chrome_applied"
    private const val keyContactHeaderStyled = "mdwechat_contact_header_styled"

    private fun applyContactPageBackground(view: View) {
        if (HookConfig.is_hook_tab_bg) {
            BackgroundImageHook.setContactBitmap(view)
            return
        }
        view.background = ColorDrawable(
            ContactPageStyleResolver.resolvePageBackgroundColor(NightModeUtils.isWechatNightMode())
        )
    }

    private fun resolveContactRoot(recyclerView: ViewGroup): ViewGroup? {
        (XposedHelpers.getAdditionalInstanceField(recyclerView, keyContactRoot) as? ViewGroup)?.let {
            return it
        }
        val contactView = ViewUtils.getParentViewSafe(recyclerView, 5) as? ViewGroup ?: return null
        if (!ViewTreeUtils.equals(VTTV.ContactLayoutListenerViewItem.item, contactView)) {
            return null
        }
        XposedHelpers.setAdditionalInstanceField(recyclerView, keyContactRoot, contactView)
        return contactView
    }

    private fun applyContactPageChrome(recyclerView: ViewGroup, contactView: ViewGroup) {
        if (XposedHelpers.getAdditionalInstanceField(recyclerView, keyContactChromeApplied) == true) {
            return
        }

        VTTV.ContactLayoutListenerViewItem.treeStacks["backgroundMask"]?.apply {
            ViewUtils.getChildView1(contactView, this)?.setBackgroundColor(Color.TRANSPARENT)
        }
        VTTV.ContactLayoutListenerViewItem.treeStacks["backgroundImage"]?.apply {
            ViewUtils.getChildView1(contactView, this)?.let {
                applyContactPageBackground(it)
            }
        }
        recyclerView.background = drawableTransparent
        ViewUtils.getChildView1(contactView, intArrayOf(0, 1, 0))?.background = drawableTransparent
        XposedHelpers.setAdditionalInstanceField(recyclerView, keyContactChromeApplied, true)
    }

    private fun applyContactHeaderStyle(headerView: View, contactView: ViewGroup?) {
        headerView.background = drawableTransparent
        if (headerView is ViewGroup) {
            ListViewHooker.setContactHeaderItemTop(headerView)
        }
        ListViewHooker.setContactHeaderItem(headerView)
        contactView?.let {
            ViewUtils.getChildView1(it, intArrayOf(0, 1, 0))?.background = drawableTransparent
        }
    }

    private fun ensureContactHeaderStyled(headerView: View, contactView: ViewGroup?) {
        val hasScheduledRetry = XposedHelpers.getAdditionalInstanceField(headerView, keyContactHeaderStyled) == true
        applyContactHeaderStyle(headerView, contactView)
        if (hasScheduledRetry) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(headerView, keyContactHeaderStyled, true)
        headerView.postDelayed({
            applyContactHeaderStyle(headerView, contactView)
        }, 160L)
    }


    override fun provideStaticHookers(): List<Hooker>? {
        return if (WechatGlobal.wxVersion!! < Version("8.0.14")) {
            listOf(resumeHook)
        } else {
            listOf(wxViewPagerHook, backgroundColorResetHook, contactItermsHook)
        }
    }

    private val wxViewPagerHook = Hooker {
        try {
            XposedHelpers.findAndHookMethod(Classes.findClass("com.tencent.mm.view.recyclerview.WxRecyclerView"),
                    "onLayout", CC.Boolean, CC.Int, CC.Int, CC.Int, CC.Int, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val WxRecyclerView = param?.thisObject ?: return
                    if (WxRecyclerView !is ViewGroup) {
                        return
                    }

                    val contactView = resolveContactRoot(WxRecyclerView)
                    contactView ?: return
                    LogUtil.logOnlyOnce("ContactHooker.ContactRecyclerLayout")
                    applyContactPageChrome(WxRecyclerView, contactView)
                    LogUtil.logOnlyOnce("ContactFragment Done")

                    VTTV.ContactLayoutListenerViewItem.treeStacks["WxRecyclerView_ContactHeaderItem"]?.apply {
                        ViewUtils.getChildView1(WxRecyclerView, this)?.let { contactHeaderItem ->
                            ensureContactHeaderStyled(contactHeaderItem, contactView)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            LogUtil.log(e)
        }
    }

    // 8.0.14 起微信修改联系人渲染样式之后经常会在滑动时重置背景
    private val backgroundColorResetHook = Hooker {
        try {
            Methods.findMethodsByName(
                    Classes.findClass("com.tencent.mm.ui.widget.pulldown.WeUIBounceViewV2"),
                    listOf("setStart2EndBgColorByActionBar", "setEnd2StartBgColorByNavigationBar",
                            "setStart2EndBgColor", "setEnd2StartBgColor", "setBgColor")
                    , CC.Int
            )
                    .forEach {
                        try {
                            XposedHelpers.findAndHookMethod(
                                    Classes.findClass("com.tencent.mm.ui.widget.pulldown.WeUIBounceViewV2"),
                                    it.name
                                    , CC.Int
                                    , object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam?) {
                                    param?.args?.apply {
                                        this[0] = Color.TRANSPARENT
                                    }
                                }
                            })
                        } catch (e: Exception) {
                            LogUtil.log(e)
                        }
                    }
        } catch (e: NoSuchMethodException) {
            LogUtil.log("find method error")
            LogUtil.log(e)
        } catch (e: Exception) {
            LogUtil.log("WHAT THE FUCK?")
            LogUtil.log(e)
        }
    }

    private val contactItermsHook = Hooker {
        try {
            XposedHelpers.findAndHookMethod(Classes.findClass("com.tencent.mm.view.recyclerview.WxRecyclerView"),
                    "onViewAdded", CC.View, object : XC_MethodHook() {
                //                        "onLayout", CC.Boolean,CC.Int,CC.Int,CC.Int, CC.Int, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val view = param?.args?.get(0) ?: return
                    if (view !is ViewGroup) {
                        return
                    }
                    val recyclerView = param.thisObject as? ViewGroup ?: return
                    val contactView = resolveContactRoot(recyclerView)
                    contactView ?: return
                    val isHeader = ViewTreeUtils.equals(VTTV.ContactHeaderItem.item, view)
                    if (isHeader) {
                        ensureContactHeaderStyled(view, contactView)
                        return
                    }
                    // 联系人列表
                    val isContactRow = ViewTreeUtils.equals(VTTV.ContactListViewItem.item, view)
                            || ListViewHooker.isContactRecyclerRowView(view)
                    if (isContactRow) {
                        ListViewHooker.setContactListViewItem(view)
                    }
                }
            })
        } catch (e: NoSuchMethodException) {
            LogUtil.log("find method error")
            LogUtil.log(e)
        } catch (e: Exception) {
            LogUtil.log("WHAT THE FUCK?")
            LogUtil.log(e)
        }
    }

    private val resumeHook = Hooker {
        Methods.HomeFragment_lifecycles.forEach {
            XposedHelpers.findAndHookMethod(Classes.ContactFragment, it.name, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val fragment = param?.thisObject ?: return
//                    LogUtil.log("ContactFragment fragment=$fragment,${Classes.ContactFragment.name}")
                    val isInit = XposedHelpers.getAdditionalInstanceField(fragment, keyInit)
                    if (isInit != null) {
                        LogUtil.log("ContactFragment 已经hook过")
                        return
                    }
                    init(fragment)
                }

                private fun init(fragment: Any) {
                    val listView = ContactFragment_mListView.get(fragment)
                    if (listView != null && listView is ListView) {
                        LogUtil.logOnlyOnce("ContactFragment Done")
                        XposedHelpers.setAdditionalInstanceField(fragment, keyInit, true)
                        applyContactPageBackground(listView)
//                        LogUtil.log("ContactFragment listview= $listView, ${listView.javaClass.name}")
                        if (listView.headerViewsCount > 0) {
                            val mHeaderViewInfos = getObjectField(listView, "mHeaderViewInfos") as ArrayList<*>
                            for (j in 0 until mHeaderViewInfos.size) {
                                val header = (mHeaderViewInfos[j] as ListView.FixedViewInfo).view
                                if (header != null) {
//                                        printViewTree(header, 0)
                                    if (header is ViewGroup) {
                                        ListViewHooker.setContactHeaderItemTop(header)
                                    }
                                }
                            }
                        }
                        LogUtil.logOnlyOnce("ContactFragment")
                    }
                }
            })
        }
    }
}
