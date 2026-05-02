package com.blanke.mdwechat.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.LayoutInflater
import com.blanke.mdwechat.Common
import de.robv.android.xposed.XposedHelpers

object ModuleContextCompat {
    @Volatile
    private var moduleApkPath: String? = null

    fun wrap(baseContext: Context): Context {
        return try {
            val resources = createModuleResources(baseContext)
            val theme = resources.newTheme().apply {
                setTo(baseContext.theme)
            }
            object : ContextWrapper(baseContext) {
                override fun getAssets(): AssetManager = resources.assets
                override fun getResources(): Resources = resources
                override fun getTheme(): Resources.Theme = theme
                override fun getPackageName(): String = Common.MY_APPLICATION_PACKAGE
                override fun getSystemService(name: String): Any? {
                    if (Context.LAYOUT_INFLATER_SERVICE == name) {
                        return LayoutInflater.from(baseContext).cloneInContext(this)
                    }
                    return super.getSystemService(name)
                }
            }
        } catch (t: Throwable) {
            LogUtil.log(t)
            baseContext
        }
    }

    private fun createModuleResources(baseContext: Context): Resources {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)
                .invoke(assetManager, resolveModuleApkPath())
        val baseResources = baseContext.resources
        return Resources(assetManager, baseResources.displayMetrics, baseResources.configuration)
    }

    private fun resolveModuleApkPath(): String {
        moduleApkPath?.let { return it }
        val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread")
        val systemContext = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
        val sourceDir = systemContext.packageManager
                .getApplicationInfo(Common.MY_APPLICATION_PACKAGE, 0)
                .sourceDir
        moduleApkPath = sourceDir
        return sourceDir
    }
}
