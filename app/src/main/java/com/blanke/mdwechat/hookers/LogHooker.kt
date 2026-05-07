package com.blanke.mdwechat.hookers

import android.util.Log
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.hookers.base.Hooker
import com.blanke.mdwechat.hookers.base.HookerProvider
import com.blanke.mdwechat.util.LogUtil.exportLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object LogHooker : HookerProvider {
    private val inLogHook = ThreadLocal<Boolean>()

    override fun provideStaticHookers(): List<Hooker>? {
        return listOf(LogEHooker)
//        return listOf(LogIHooker, LogEHooker)
    }

    private val LogIHooker = Hooker {
        XposedBridge.hookAllMethods(Log::class.java, "i", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (inLogHook.get() == true) return
                if (!HookConfig.is_hook_log) {
                    return
                }
                inLogHook.set(true)
                try {
                    val msg = param.args.getOrNull(1)?.toString() ?: return
                    if (msg.contains("mdwechat", true)) {
                        exportLog(msg)
                    }
                } finally {
                    inLogHook.set(false)
                }
            }
        })
    }

    private val LogEHooker = Hooker {
        XposedBridge.hookAllMethods(Log::class.java, "e", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (inLogHook.get() == true) return
                if (!HookConfig.is_hook_log) {
                    return
                }
                inLogHook.set(true)
                try {
                    val msg = param.args.getOrNull(1)?.toString().orEmpty()
                    if (msg.contains("mdwechat", true)) {
                        exportLog(msg)
                    }
                    val tr = param.args.lastOrNull()
                    if (tr is Throwable) {
                        val msg1 = Log.getStackTraceString(tr)
                        if (msg1.contains("mdwechat", true)) {
                            exportLog(msg1)
                        }
                    }
                } finally {
                    inLogHook.set(false)
                }
            }
        })
    }

}
