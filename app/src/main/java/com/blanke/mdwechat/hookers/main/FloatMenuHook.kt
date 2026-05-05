package com.blanke.mdwechat.hookers.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import com.blanke.mdwechat.Common
import com.blanke.mdwechat.Objects
import com.blanke.mdwechat.WeChatHelper
import com.blanke.mdwechat.bean.FLoatButtonConfigItem
import com.blanke.mdwechat.config.AppCustomConfig
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.util.ConvertUtils
import com.blanke.mdwechat.util.DrawableUtils
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.LogUtil.log
import com.blanke.mdwechat.util.ModuleContextCompat
import com.blanke.mdwechat.util.NightModeUtils
import com.blanke.mdwechat.util.RuntimeProbe
import com.github.clans.fab.FloatingActionButton
import com.github.clans.fab.FloatingActionButton.SIZE_MINI
import com.github.clans.fab.FloatingActionMenu
import com.joshcai.mdwechat.R
import de.robv.android.xposed.XposedHelpers


object FloatMenuHook {
    private const val FLOAT_MENU_DESC = "MDWECHAT_FLOAT_MENU_OK"
    private const val FLOAT_MENU_BACKGROUND_DESC = "MDWECHAT_FLOAT_MENU_BACKGROUND"
    private val reattachDelays = longArrayOf(200L, 600L, 1200L)

    fun addFloatMenu(contentLayout: ViewGroup, bottomMargin: Int = 0) {
        RuntimeProbe.append(contentLayout.context, "FloatMenu start bottomMargin=$bottomMargin")
        contentLayout.findFloatMenu()?.let { floatMenu ->
            floatMenu.bringToFront()
            contentLayout.requestLayout()
            contentLayout.invalidate()
            RuntimeProbe.append(contentLayout.context, "FloatMenu alreadyAttached bringToFront")
            return
        }
        FloatingActionMenu.OPENED_PLUS_ROTATION_LEFT = HookConfig.value_hook_float_button_angle.toFloat()
        val context = ModuleContextCompat.wrapModuleTheme(contentLayout.context, R.style.Theme_MDWechat_FloatMenu)
        val floatConfig = AppCustomConfig.getFloatButtonConfig()
        if (floatConfig?.items == null || floatConfig.menu?.icon == null) {
            log("floatButton 主 icon 为空")
            RuntimeProbe.append(contentLayout.context, "FloatMenu configMissing")
            return
        }
        val primaryColor = NightModeUtils.colorPrimary
//        val secondaryColor = HookConfig.get_color_secondary
        val floatButtonColor = NightModeUtils.colorFloatButton
        val actionMenu = FloatingActionMenu(context)
        actionMenu.contentDescription = FLOAT_MENU_DESC
        actionMenu.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        actionMenu.menuButtonColorNormal = primaryColor
        actionMenu.menuButtonColorPressed = primaryColor
        actionMenu.setmLabelsTextColor(floatButtonColor)
        val bitmap: Bitmap? = getFloatIcon(context, floatConfig.menu!!.icon)
        var drawable: Drawable = bitmap?.let { BitmapDrawable(context.resources, it) }
                ?: fallbackMenuDrawable(context).also {
                    log("floatButton 主 icon 为空, 使用默认图标")
                    RuntimeProbe.append(contentLayout.context, "FloatMenu iconFallback ${floatConfig.menu!!.icon}")
                }
        drawable = if (HookConfig.is_hook_float_button_color_up) DrawableUtils.setDrawableColor(drawable, floatButtonColor) else drawable
        actionMenu.setMenuIcon(drawable)
        actionMenu.initMenuButton()
        actionMenu.menuButton.contentDescription = "MDWECHAT_FLOAT_MENU_BUTTON"
        actionMenu.menuButton.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        //menu items
        val floatItems = arrayListOf<FLoatButtonConfigItem>()
        floatConfig.items?.sortedBy { it.order }
                ?.forEach {
                    val drawable2: Bitmap? = getFloatIcon(context, it.icon)
                    val itemDrawable = drawable2
                            ?.let { bitmap -> BitmapDrawable(context.resources, AppCustomConfig.getScaleBitmap(bitmap)) }
                            ?: fallbackMenuDrawable(context).also { _ ->
                                log("${it.icon}不存在, 使用默认图标")
                                RuntimeProbe.append(contentLayout.context, "FloatMenu itemIconFallback ${it.icon}")
                            }
                    floatItems.add(it)
                    getFloatButton(actionMenu, context, it.text,
                            itemDrawable, primaryColor, floatButtonColor, HookConfig.is_hook_float_button_color_up)
                }

        actionMenu.setFloatButtonClickListener { fab, index ->
            //log("click fab,index=" + index + ",label" + fab.getLabelText());
            onFloatButtonClick(floatItems[index], index)
        }

        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = ConvertUtils.dp2px(contentLayout.context, 12f)
        params.rightMargin = margin
        params.bottomMargin = margin + bottomMargin
        params.gravity = Gravity.END or Gravity.BOTTOM
        if (HookConfig.is_hook_float_button_move) {
            actionMenu.menuButton.setOnTouchListener(object : View.OnTouchListener {
                var lastX: Float = 0.toFloat()
                var lastY: Float = 0.toFloat()
                var downTime: Long = 0

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        lastX = event.rawX
                        lastY = event.rawY
                        downTime = System.currentTimeMillis()
                    } else if (event.action == MotionEvent.ACTION_MOVE) {
                        val nowX = event.rawX
                        val nowY = event.rawY
                        val dx = (nowX - lastX).toInt().toFloat()
                        val dy = (nowY - lastY).toInt().toFloat()
                        lastX = nowX
                        lastY = nowY
                        actionMenu.x = actionMenu.x + dx
                        actionMenu.y = actionMenu.y + dy
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        val nowTime = System.currentTimeMillis()
                        if (nowTime - downTime > 300) {
                            actionMenu.menuButton.isPressed = false
                            return true
                        }
                    }
                    return false
                }
            })
        }

        val backgroundView = View(context)
        backgroundView.contentDescription = FLOAT_MENU_BACKGROUND_DESC
        val params2 = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        backgroundView.visibility = View.GONE
        backgroundView.setOnClickListener { view ->
            actionMenu.close(true)
            view.visibility = View.GONE
        }
        actionMenu.setOnMenuToggleListener { opened ->
            backgroundView.visibility = if (opened) View.VISIBLE else View.GONE
        }
        contentLayout.addView(backgroundView, params2)
        contentLayout.addView(actionMenu, params)
        actionMenu.bringToFront()
        ensureAttachedLater(contentLayout, backgroundView, params2, actionMenu, params)
        RuntimeProbe.append(contentLayout.context, "FloatMenu addViewDone items=${floatItems.size}")
    }

    private fun ensureAttachedLater(
        contentLayout: ViewGroup,
        backgroundView: View,
        backgroundParams: ViewGroup.LayoutParams,
        actionMenu: FloatingActionMenu,
        actionParams: ViewGroup.LayoutParams
    ) {
        reattachDelays.forEach { delay ->
            contentLayout.postDelayed({
                try {
                    if (contentLayout.findFloatMenu() == null) {
                        val backgroundParent = backgroundView.parent
                        if (backgroundParent != null && backgroundParent !== contentLayout && backgroundParent is ViewGroup) {
                            backgroundParent.removeView(backgroundView)
                        }
                        if (backgroundView.parent == null) {
                            contentLayout.addView(backgroundView, backgroundParams)
                        }
                        val actionParent = actionMenu.parent
                        if (actionParent != null && actionParent !== contentLayout && actionParent is ViewGroup) {
                            actionParent.removeView(actionMenu)
                        }
                        if (actionMenu.parent == null) {
                            contentLayout.addView(actionMenu, actionParams)
                        }
                        RuntimeProbe.append(contentLayout.context, "FloatMenu reattached delay=$delay")
                    }
                    actionMenu.bringToFront()
                    contentLayout.requestLayout()
                    contentLayout.invalidate()
                } catch (t: Throwable) {
                    LogUtil.log(t)
                    RuntimeProbe.append(contentLayout.context, "FloatMenu reattachFailed delay=$delay ${t.javaClass.name}:${t.message}")
                }
            }, delay)
        }
    }

    private fun ViewGroup.findFloatMenu(): View? {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.contentDescription == FLOAT_MENU_DESC) {
                return child
            }
        }
        return null
    }

    private fun getFloatButton(actionMenu: FloatingActionMenu, context: Context,
                               label: String, drawable: Drawable, primaryColor: Int, floatButtonColor: Int, isColorUp: Boolean = false): FloatingActionButton {
        val fab = FloatingActionButton(context)
        val filteredDrawable = if (isColorUp) DrawableUtils.setDrawableColor(drawable, floatButtonColor) else drawable

        fab.setImageDrawable(filteredDrawable)
        fab.colorNormal = primaryColor
        fab.colorPressed = primaryColor
        fab.buttonSize = SIZE_MINI
        fab.labelText = label
//        fab.setLabelTextColor(floatButtonColor)
        actionMenu.addMenuButton(fab)
        fab.setLabelColors(primaryColor, primaryColor, primaryColor)
        return fab
    }

    private fun fallbackMenuDrawable(context: Context): Drawable {
        return context.getDrawable(android.R.drawable.ic_input_add) ?: ColorDrawable(Color.TRANSPARENT)
    }

    private fun getFloatIcon(context: Context, fileName: String): Bitmap? {
        AppCustomConfig.getIcon(fileName)?.let { return it }
        return try {
            context.assets.open("${Common.ICON_DIR}/$fileName").use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun onFloatButtonClick(item: FLoatButtonConfigItem, index: Int) {
        log("点击悬浮按钮，index=$index,item=$item")
        when (item.type) {
            "weiX" -> {
                Objects.Main.LauncherUI_mWechatXMenuItem?.run {
                    this::class.java.declaredFields
                            .firstOrNull { it.type == MenuItem.OnMenuItemClickListener::class.java }
                            ?.apply {
                                this.isAccessible = true
                                val listener = this.get(this@run)
                                XposedHelpers.callMethod(listener, "onMenuItemClick", this@run)
                            }
                }
            }
            else -> {
                try {
                    WeChatHelper.startActivity(item.type)
                } catch (e: Throwable) {
                    LogUtil.log(e)
                    Objects.Main.LauncherUI?.apply {
                        Toast.makeText(this, "跳转失败，请检查类名是否正确", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

}
