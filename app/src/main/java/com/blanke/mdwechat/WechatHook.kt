package com.blanke.mdwechat

import com.blanke.mdwechat.Common.isVXPEnv
import com.blanke.mdwechat.config.AppCustomConfig
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.config.ViewTreeConfig
import com.blanke.mdwechat.config.WxVersionConfig
import com.blanke.mdwechat.hookers.*
import com.blanke.mdwechat.hookers.base.Hooker
import com.blanke.mdwechat.hookers.base.HookerProvider
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.LogUtil.log
import com.blanke.mdwechat.util.RuntimeProbe
import com.blanke.mdwechat.util.FileUtils
import com.blanke.mdwechat.util.ModuleContextCompat
import com.blanke.mdwechat.util.waitInvoke
import com.joshcai.mdwechat.BuildConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class WechatHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private fun debugLine(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss").format(Date())
        FileUtils.write(AppCustomConfig.getLogFile("hook_debug"), "$stamp $msg\n", true)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        ModuleContextCompat.setModuleApkPath(startupParam?.modulePath)
        XposedBridge.log("MDWechatModule: initZygote modulePath=${startupParam?.modulePath}")
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            debugLine("handleLoadPackage package=${lpparam.packageName} process=${lpparam.processName}")
            log(lpparam.packageName)
            if (!(lpparam.packageName.contains("com.tencent") && lpparam.packageName.contains("mm")))
                return
            // 暂时不 hook 小程序
            if (lpparam.processName.contains(":")) {
                return
            }
            debugLine("before initPrefs")
            WeChatHelper.initPrefs()
            debugLine("after initPrefs")
            if (!HookConfig.is_hook_switch) {
                log("模块总开关已关闭")
                return
            }
            log("模块加载中...")
            val preloadHooker = LauncherUIHooker.launcherLifeHooker
            val hookers = mutableListOf(
                    StatusBarHooker,
                    ActionBarHooker,
                    LauncherUIHooker,
                    AvatarHooker,
                    ListViewHooker,
                    ModernChatBubbleHooker,
                    ConversationHooker,
                    ContactHooker,
                    DiscoverHooker,
                    SettingsHooker,
                    SchemeHooker,
                    LogHooker,
                    NightModeHooker
            )
//            region test
//            log("Hookers 总数: ${hookers.count()}")
//            val asd = HookConfig.debug_config_text.split(" ")
//            for (i in asd[3].toInt() downTo asd[2].toInt()) {
//                hookers.removeAt(i)
//            }
//            for (i in asd[1].toInt() downTo asd[0].toInt()) {
//                hookers.removeAt(i)
//            }
//            log("激活的 Hookers 数量: ${hookers.count()}，分别为：")
//            hookers.forEach {
//                log(it::class.java.name)
//            }
            LogUtil.logStackTraces()
//            //endregion

            if ((!isVXPEnv) && (HookConfig.is_hook_debug || HookConfig.is_hook_debug2)) {
                hookers.add(0, DebugHooker)
            }
            hookMain(lpparam, preloadHooker, hookers)
        } catch (e: Throwable) {
            debugLine("handleLoadPackage failed: ${e.javaClass.name}: ${e.message}")
            log(e)
        }
    }

    private fun hookMain(lpparam: XC_LoadPackage.LoadPackageParam, preloadHooker: Hooker, plugins: List<HookerProvider>) {
        enableHookers(listOf(ContextHooker))
        WechatGlobal.init(lpparam)
        val configPath = AppCustomConfig.getWxConfigFile("${WechatGlobal.wxVersion}.config")
        debugLine("configPath=$configPath exists=${File(configPath).exists()} canRead=${File(configPath).canRead()}")
        log("configPath=$configPath exists=${File(configPath).exists()} canRead=${File(configPath).canRead()}")
        try {
            WechatGlobal.wxVersionConfig = WxVersionConfig.loadConfig(WechatGlobal.wxVersion!!.toString())
        } catch (e: Exception) {
            val detail = buildString {
                append(e.javaClass.simpleName)
                e.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it.take(80))
                }
                e.suppressed.firstOrNull()?.let {
                    append(" | ")
                    append(it.javaClass.simpleName)
                    it.message?.takeIf { msg -> msg.isNotBlank() }?.let { msg ->
                        append(": ")
                        append(msg.take(80))
                    }
                }
            }
            debugLine("loadConfig failed: ${e.javaClass.name}: ${e.message}")
            LogUtil.exportLog("loadConfig failed: ${e.javaClass.name}: ${e.message}")
            log("loadConfig failed: ${e.javaClass.name}: ${e.message}")
            log(e)
            waitInvoke(100, true,
                    { Objects.Main.context != null },
                    {
                        LogUtil.toast("无法读取配置文件[$detail]，请打开 mdwechat 重新生成本机配置。", true)
                    })
            log("${WechatGlobal.wxVersion} 配置文件不存在或解析失败")
            return
        }
        try {
            preloadHooker.hook()
        } catch (e: Exception) {
            debugLine("preloadHooker failed: ${e.javaClass.name}: ${e.message}")
            LogUtil.exportLog("preloadHooker failed: ${e.javaClass.name}: ${e.message}")
            log("preloadHooker failed: ${e.javaClass.name}: ${e.message}")
            log(e)
            return
        }
        try {
            ViewTreeConfig.set(WechatGlobal.wxVersion!!)
        } catch (e: Exception) {
            debugLine("ViewTreeConfig.set failed: ${e.javaClass.name}: ${e.message}")
            LogUtil.exportLog("ViewTreeConfig.set failed: ${e.javaClass.name}: ${e.message}")
            log("ViewTreeConfig.set failed: ${e.javaClass.name}: ${e.message}")
            log(e)
            return
        }
        log("wechat version=" + WechatGlobal.wxVersion
                + ",processName=" + lpparam.processName
                + ",isVXPEnv = " + isVXPEnv
                + ",MDWechat version=" + BuildConfig.VERSION_NAME)

        if (HookConfig.is_fix_play) {
            //todo 等待其他hookers加载
            waitInvoke(1, true, { WechatGlobal.preloaded }, { enableHookers(plugins) })
        } else {
            enableHookers(plugins)
        }
    }

    fun enableHookers(plugins: List<HookerProvider>) {
        val failures = mutableListOf<String>()
        plugins.forEach { provider ->
            try {
                provider.provideStaticHookers()?.forEachIndexed { index, hooker ->
                    if (!hooker.hasHooked) {
                        try {
                            hooker.hook()
                            hooker.hasHooked = true
                        } catch (e: Throwable) {
                            failures.add("${provider.javaClass.simpleName}[$index]:${e.javaClass.simpleName}")
                            log("${provider.javaClass.simpleName} hooker[$index] failed: ${e.javaClass.name}: ${e.message}")
                            log(e)
                        }
                    }
                }
            } catch (e: Throwable) {
                failures.add("${provider.javaClass.simpleName}:${e.javaClass.simpleName}")
                log("${provider.javaClass.simpleName} provider failed: ${e.javaClass.name}: ${e.message}")
                log(e)
            }
        }
        if (failures.isNotEmpty()) {
            val detail = failures.take(3).joinToString(" | ")
            waitInvoke(200, true, { Objects.Main.LauncherUI != null }, {
                RuntimeProbe.append(Objects.Main.LauncherUI as? android.content.Context, "enableHookers failures=$detail")
                LogUtil.toast("Hook失败: $detail", true)
            })
        }
        log("模块加载成功")
    }
}

