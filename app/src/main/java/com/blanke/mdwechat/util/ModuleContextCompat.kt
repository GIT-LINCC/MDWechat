package com.blanke.mdwechat.util

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.LayoutInflater
import androidx.annotation.StyleRes
import androidx.appcompat.view.ContextThemeWrapper
import com.blanke.mdwechat.Common
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.net.URLDecoder

object ModuleContextCompat {
    @Volatile
    private var moduleApkPath: String? = null
    @Volatile
    private var lastModulePathProbe: String = "uninitialized"

    fun setModuleApkPath(path: String?) {
        path
            ?.takeIf { it.isNotBlank() }
            ?.let {
                moduleApkPath = it
                lastModulePathProbe = "zygoteModulePath=$it"
            }
    }

    fun wrap(baseContext: Context): Context {
        return try {
            val resources = createModuleResources(baseContext)
            ModuleContextWrapper(baseContext, resources)
        } catch (t: Throwable) {
            LogUtil.log(t)
            RuntimeProbe.append(baseContext, "ModuleContextCompat.wrap failed ${t.javaClass.name}:${t.message} probe=$lastModulePathProbe")
            baseContext
        }
    }

    fun wrap(baseContext: Context, @StyleRes themeResId: Int?): Context {
        return try {
            val resources = createModuleResources(baseContext)
            if (themeResId != null) {
                ModuleThemedContextWrapper(baseContext, resources, themeResId)
            } else {
                ModuleContextWrapper(baseContext, resources)
            }
        } catch (t: Throwable) {
            LogUtil.log(t)
            RuntimeProbe.append(
                baseContext,
                "ModuleContextCompat.wrap themed=${themeResId != null} failed ${t.javaClass.name}:${t.message} probe=$lastModulePathProbe"
            )
            baseContext
        }
    }

    fun wrapModuleTheme(baseContext: Context, @StyleRes themeResId: Int): Context {
        return try {
            val resources = createModuleResources(baseContext)
            ModuleOnlyThemedContextWrapper(baseContext, resources, themeResId)
        } catch (t: Throwable) {
            LogUtil.log(t)
            RuntimeProbe.append(
                baseContext,
                "ModuleContextCompat.wrapModuleTheme failed ${t.javaClass.name}:${t.message} probe=$lastModulePathProbe"
            )
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
        resolveModuleApkPathFromClassLoader()?.let {
            moduleApkPath = it
            return it
        }
        resolveModuleApkPathFromPackageManagerBinder()?.let {
            moduleApkPath = it
            lastModulePathProbe = "binderPm=$it"
            return it
        }
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

    private fun resolveModuleApkPathFromClassLoader(): String? {
        val commonUrl = Common::class.java.getResource("Common.class")?.toString()
        val buildConfigUrl = Common::class.java.getResource("/com/lincc/mdwechat/BuildConfig.class")?.toString()
        lastModulePathProbe = "commonUrl=$commonUrl | buildConfigUrl=$buildConfigUrl"
        extractApkPathFromResourceUrl(Common::class.java.getResource("Common.class")?.toString())?.let {
            lastModulePathProbe = "resourceCommon=$it"
            return it
        }
        extractApkPathFromResourceUrl(Common::class.java.getResource("/com/lincc/mdwechat/BuildConfig.class")?.toString())?.let {
            lastModulePathProbe = "resourceBuildConfig=$it"
            return it
        }
        try {
            val codeSourcePath = Common::class.java.protectionDomain
                ?.codeSource
                ?.location
                ?.path
                ?.takeIf { it.isNotBlank() && File(it).exists() }
            if (codeSourcePath != null) {
                lastModulePathProbe = "codeSource=$codeSourcePath"
                return codeSourcePath
            }
        } catch (_: Throwable) {
        }
        return try {
            val pathList = XposedHelpers.getObjectField(Common::class.java.classLoader, "pathList")
            val dexElements = XposedHelpers.getObjectField(pathList, "dexElements") as? Array<*>
            val sampledPaths = dexElements
                ?.take(4)
                ?.mapNotNull { element ->
                    try {
                        val dexFile = XposedHelpers.getObjectField(element, "dexFile")
                        XposedHelpers.callMethod(dexFile, "getName") as? String
                    } catch (_: Throwable) {
                        null
                    }
                }
                ?.joinToString(";")
            dexElements
                ?.asSequence()
                ?.mapNotNull { element ->
                    val rawPath = try {
                        val dexFile = XposedHelpers.getObjectField(element, "dexFile")
                        XposedHelpers.callMethod(dexFile, "getName") as? String
                    } catch (_: Throwable) {
                        val pathField = XposedHelpers.getObjectField(element, "path")
                        when (pathField) {
                            is File -> pathField.absolutePath
                            is String -> pathField
                            else -> null
                        }
                    }
                    rawPath?.takeIf { it.contains("mdwechat", ignoreCase = true) && File(it).exists() }
                }
                ?.firstOrNull()
                ?.also { lastModulePathProbe = "dexPath=$it sampled=$sampledPaths" }
                ?: run {
                    lastModulePathProbe = "dexPathMissing sampled=$sampledPaths"
                    null
                }
        } catch (_: Throwable) {
            lastModulePathProbe = "classLoaderProbeFailed"
            null
        }
    }

    private fun resolveModuleApkPathFromPackageManagerBinder(): String? {
        return try {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val iPackageManager = XposedHelpers.callStaticMethod(activityThreadClass, "getPackageManager")
            val userHandleClass = XposedHelpers.findClass("android.os.UserHandle", null)
            val userId = XposedHelpers.callStaticMethod(userHandleClass, "myUserId") as Int
            val appInfo = try {
                XposedHelpers.callMethod(
                    iPackageManager,
                    "getApplicationInfo",
                    Common.MY_APPLICATION_PACKAGE,
                    0L,
                    userId
                ) as? ApplicationInfo
            } catch (_: Throwable) {
                XposedHelpers.callMethod(
                    iPackageManager,
                    "getApplicationInfo",
                    Common.MY_APPLICATION_PACKAGE,
                    0,
                    userId
                ) as? ApplicationInfo
            }
            appInfo?.sourceDir?.takeIf { it.isNotBlank() && File(it).exists() }
        } catch (t: Throwable) {
            lastModulePathProbe = "binderPmFailed ${t.javaClass.simpleName}:${t.message}"
            null
        }
    }

    private fun extractApkPathFromResourceUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fileIndex = url.indexOf("file:")
        val bangIndex = url.indexOf("!/")
        if (fileIndex < 0 || bangIndex <= fileIndex) return null
        return try {
            URLDecoder.decode(url.substring(fileIndex + 5, bangIndex), "UTF-8")
                .takeIf { it.endsWith(".apk", ignoreCase = true) && File(it).exists() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun createModulePackageContext(baseContext: Context): Context {
        val moduleContext = try {
            baseContext.createPackageContext(
                Common.MY_APPLICATION_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (baseError: Throwable) {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread"
            )
            val systemContext = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
            RuntimeProbe.append(
                baseContext,
                "ModuleContextCompat.baseCreatePackageContext failed ${baseError.javaClass.name}:${baseError.message}"
            )
            systemContext.createPackageContext(
                Common.MY_APPLICATION_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }
        return moduleContext.createConfigurationContext(baseContext.resources.configuration)
    }

    private fun createThemedModuleContext(
        baseContext: Context,
        @StyleRes themeResId: Int
    ): Context {
        val moduleContext = createModulePackageContext(baseContext)
        return object : ContextThemeWrapper(moduleContext, themeResId) {
            private val inflater by lazy(LazyThreadSafetyMode.NONE) {
                LayoutInflater.from(moduleContext).cloneInContext(this)
            }

            override fun getSystemService(name: String): Any? {
                if (Context.LAYOUT_INFLATER_SERVICE == name) {
                    return inflater
                }
                return super.getSystemService(name)
            }
        }
    }

    private open class ModuleContextWrapper(
        baseContext: Context,
        protected val moduleResources: Resources
    ) : ContextWrapper(baseContext) {
        protected val moduleTheme = moduleResources.newTheme().apply {
            setTo(baseContext.theme)
        }

        override fun getAssets(): AssetManager = moduleResources.assets
        override fun getResources(): Resources = moduleResources
        override fun getTheme(): Resources.Theme = moduleTheme
        override fun getPackageName(): String = Common.MY_APPLICATION_PACKAGE

        override fun getSystemService(name: String): Any? {
            if (Context.LAYOUT_INFLATER_SERVICE == name) {
                return LayoutInflater.from(baseContext).cloneInContext(this)
            }
            return super.getSystemService(name)
        }
    }

    private class ModuleThemedContextWrapper(
        baseContext: Context,
        private val themedResources: Resources,
        @StyleRes themeResId: Int
    ) : ContextThemeWrapper(baseContext, themeResId) {
        private val inflater by lazy(LazyThreadSafetyMode.NONE) {
            LayoutInflater.from(baseContext).cloneInContext(this)
        }

        override fun getAssets(): AssetManager = themedResources.assets
        override fun getResources(): Resources = themedResources
        override fun getPackageName(): String = Common.MY_APPLICATION_PACKAGE

        override fun getSystemService(name: String): Any? {
            if (Context.LAYOUT_INFLATER_SERVICE == name) {
                return inflater
            }
            return super.getSystemService(name)
        }
    }

    private class ModuleOnlyThemedContextWrapper(
        baseContext: Context,
        private val themedResources: Resources,
        @StyleRes themeResId: Int
    ) : ContextWrapper(baseContext) {
        private val themedInflater by lazy(LazyThreadSafetyMode.NONE) {
            LayoutInflater.from(baseContext).cloneInContext(this)
        }
        private val moduleTheme = themedResources.newTheme().apply {
            applyStyle(themeResId, true)
        }

        override fun getAssets(): AssetManager = themedResources.assets
        override fun getResources(): Resources = themedResources
        override fun getTheme(): Resources.Theme = moduleTheme
        override fun getPackageName(): String = Common.MY_APPLICATION_PACKAGE

        override fun getSystemService(name: String): Any? {
            if (Context.LAYOUT_INFLATER_SERVICE == name) {
                return themedInflater
            }
            return super.getSystemService(name)
        }
    }

}
