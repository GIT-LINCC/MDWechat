package com.blanke.mdwechat

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import com.blanke.mdwechat.config.AppCustomConfig
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.util.ColorUtils
import com.blanke.mdwechat.util.DrawableUtils
import com.blanke.mdwechat.util.NightModeUtils
import com.blanke.mdwechat.util.NightModeUtils.colorPrimary
import com.blanke.mdwechat.util.RippleColorResolver
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileOutputStream

object WeChatHelper {
    lateinit var XMOD_PREFS: XSharedPreferences
    private const val prefsProviderAuthority = "com.lincc.mdwechat.prefs"
    private val providerPrefsFileName = Common.MOD_PREFS + ".xml"
    private var providerPrefsFile: File? = null

    //微信8.0.0第四页底色
    val wechatWhite: Int = Color.parseColor("#ffffff")
    val wechatDark: Int = Color.parseColor("#111111")

    val colorWhite: Int = Color.parseColor("#FAFAFA")
    val colorDarkWhite: Int = Color.LTGRAY

    val colorDark: Int = Color.parseColor("#232323")

    //深色模式下 action bar 的颜色
    val colorDarkPrimary: Int = Color.parseColor("#333333")


    val drawableTransparent: ColorDrawable
        get() = ColorDrawable(Color.TRANSPARENT)

    val drawableWhite: ColorDrawable = ColorDrawable(colorWhite)

    val drawableDark: ColorDrawable = ColorDrawable(colorDark)

    val colorPrimaryDrawable: ColorDrawable
        get() {
            val colorDrawable = ColorDrawable()
            colorDrawable.color = colorPrimary
            return colorDrawable
        }
    val colorPrimaryDrawableDark: ColorDrawable
        get() = ColorDrawable(colorDarkPrimary)

    val defaultImageRippleDrawable: Drawable?
        get() {
            val context = Objects.Main.LauncherUI ?: return null
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = context.obtainStyledAttributes(attrs)
            val imageRippleDrawable = ta.getDrawable(0)
            ta.recycle()
            return imageRippleDrawable
        }

    val defaultImageRippleBorderDrawable: Drawable?
        get() {
            val context = Objects.Main.LauncherUI ?: return null
            val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            val ta = context.obtainStyledAttributes(attrs)
            val imageRippleDrawable = ta.getDrawable(0)
            ta.recycle()
            return imageRippleDrawable
        }

    fun createItemRippleDrawable(): Drawable {
        if (!HookConfig.is_hook_ripple) {
            return ColorDrawable(Color.TRANSPARENT)
        }
        val resolvedRippleColor = RippleColorResolver.resolvePressedColor(HookConfig.get_color_ripple)
        return DrawableUtils.getTransparentColorRippleDrawable(Color.WHITE, resolvedRippleColor).mutate()
    }

    fun wrapItemBackgroundWithRipple(contentDrawable: Drawable?): Drawable {
        val safeContentDrawable = contentDrawable ?: ColorDrawable(
                if (NightModeUtils.isWechatNightMode()) wechatDark else wechatWhite
        )
        if (!HookConfig.is_hook_ripple) {
            return safeContentDrawable
        }
        val resolvedRippleColor = RippleColorResolver.resolvePressedColor(HookConfig.get_color_ripple)
        return DrawableUtils.wrapDrawableWithRipple(safeContentDrawable.mutate(), Color.WHITE, resolvedRippleColor).mutate()
    }

    fun getLeftRedPacketBubble(
            resources: Resources,
            isTint: Boolean = false,
            tintColor: Int = HookConfig.get_hook_bubble_tint_left
    ): Drawable? {
        val bubble = AppCustomConfig.getRedPacketBubbleLeftIcon() ?: return null
        return getBubble(bubble, resources, isTint, tintColor)
    }

    fun getUnopenedLeftRedPacketBubble(
            resources: Resources,
            isTint: Boolean = false,
            tintColor: Int = HookConfig.get_hook_bubble_tint_left
    ): Drawable? {
        val bubble = AppCustomConfig.getUnopenedRedPacketBubbleLeftIcon() ?: return null
        return getBubble(bubble, resources, isTint, tintColor)
    }

    fun getRightRedPacketBubble(
            resources: Resources,
            isTint: Boolean = false,
            tintColor: Int = HookConfig.get_hook_bubble_tint_right
    ): Drawable? {
        val bubble = AppCustomConfig.getRedPacketBubbleRightIcon() ?: return null
        return getBubble(bubble, resources, isTint, tintColor)
    }

    fun getUnopenedRightRedPacketBubble(
            resources: Resources,
            isTint: Boolean = false,
            tintColor: Int = HookConfig.get_hook_bubble_tint_right
    ): Drawable? {
        val bubble = AppCustomConfig.getUnopenedRedPacketBubbleRightIcon() ?: return null
        return getBubble(bubble, resources, isTint, tintColor)
    }

    fun getLeftBubble(
            resources: Resources,
            isTint: Boolean = HookConfig.is_hook_bubble_tint,
            tintColor: Int = HookConfig.get_hook_bubble_tint_left
    ): Drawable? {
        val bubble = AppCustomConfig.getBubbleLeftIcon() ?: return null
        return getBubble(bubble, resources, isTint, tintColor)
    }

    fun getRightBubble(
            resources: Resources,
            isTint: Boolean = HookConfig.is_hook_bubble_tint,
            tintColor: Int = HookConfig.get_hook_bubble_tint_right
    ): Drawable? {
        val bubble = AppCustomConfig.getBubbleRightIcon() ?: return null
        return getBubble(bubble, resources, isTint, tintColor)
    }

    private fun getBubble(
            sourceBitmap: Bitmap,
            resources: Resources,
            isTint: Boolean = HookConfig.is_hook_bubble_tint,
            tintColor: Int = HookConfig.get_hook_bubble_tint_right
    ): Drawable? {
        val bubbleDrawable = DrawableUtils.getNineDrawable(resources, sourceBitmap)
        val drawable = StateListDrawable()
        val pressBubbleDrawable = bubbleDrawable.constantState!!.newDrawable().mutate()
        if (isTint) {
            bubbleDrawable.setTint(tintColor)
            pressBubbleDrawable.setTint(getDarkColor(tintColor))
        } else {
            pressBubbleDrawable.setTint(getDarkColor(Color.WHITE))
        }
        drawable.addState(intArrayOf(android.R.attr.state_pressed), pressBubbleDrawable)
        drawable.addState(intArrayOf(android.R.attr.state_focused), pressBubbleDrawable)
        drawable.addState(intArrayOf(), bubbleDrawable)
        return drawable
    }

    fun startActivity(actName: String) {
        val context = Objects.Main.LauncherUI ?: return
        val intent = Intent()
        intent.setClassName(context as Context, actName)
        context.startActivity(intent)
    }

    fun initPrefs() {
        val providerFile = refreshProviderPrefsFile()
        val providerPrefs = providerFile?.let { XSharedPreferences(it) }
        val externalPrefsFile = File(AppCustomConfig.getConfigFile(Common.MOD_PREFS + ".xml"))
        val externalPrefs = XSharedPreferences(externalPrefsFile)
        val packagePrefs = XSharedPreferences(Common.MY_APPLICATION_PACKAGE, Common.MOD_PREFS)
        XMOD_PREFS = if (providerFile?.canRead() == true && providerPrefs != null) {
            providerPrefs
        } else if (externalPrefsFile.canRead()) {
            externalPrefs
        } else if (packagePrefs.file?.canRead() == true) {
            packagePrefs
        } else {
            externalPrefs
        }
        try {
            XMOD_PREFS.makeWorldReadable()
        } catch (_: Throwable) {
        }
        try {
            XMOD_PREFS.reload()
        } catch (_: Throwable) {
        }
    }

    fun reloadPrefs() {
        refreshProviderPrefsFile()
        XMOD_PREFS.reload()
    }

    private fun refreshProviderPrefsFile(): File? {
        return try {
            val systemContext = getSystemContext()
            val appInfo = systemContext.packageManager.getApplicationInfo(Common.WECHAT_PACKAGENAME, 0)
            val target = File(File(appInfo.dataDir, "cache/mdwechat"), providerPrefsFileName)
            target.parentFile?.mkdirs()
            val uri = Uri.parse("content://$prefsProviderAuthority/$providerPrefsFileName")
            systemContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                }
            } ?: return providerPrefsFile
            providerPrefsFile = target
            if (target.length() > 0L) target else null
        } catch (_: Throwable) {
            providerPrefsFile
        }
    }

    private fun getSystemContext(): Context {
        val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread")
        return XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
    }

    private fun getDarkColor(color: Int): Int {
        return ColorUtils.getDarkerColor(color, 0.8F)
    }
}
