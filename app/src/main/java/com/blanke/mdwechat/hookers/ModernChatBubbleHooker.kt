package com.blanke.mdwechat.hookers

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.blanke.mdwechat.CC
import com.blanke.mdwechat.WechatGlobal
import com.blanke.mdwechat.hookers.base.Hooker
import com.blanke.mdwechat.hookers.base.HookerProvider
import com.blanke.mdwechat.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ModernChatBubbleHooker : HookerProvider {
    private const val enableModernChatBubble = true
    private const val enableLegacyBubbleFallbacks = false

    private val installLogs = mutableSetOf<String>()
    private val hookedRecyclerClasses = mutableSetOf<String>()
    private val hookedAdapterClasses = mutableSetOf<String>()
    private val hookedBindMethods = mutableSetOf<String>()
    private val hookedChattingItemMethods = mutableSetOf<String>()
    private val drawProbeLogs = mutableSetOf<String>()
    private val recyclerProbeLogs = mutableSetOf<String>()
    private var classLoadHookInstalled = false
    private const val keyPendingApply = "mdwechat_modern_chat_bubble_pending_apply"
    private const val keyPendingRecyclerApply = "mdwechat_modern_chat_bubble_pending_recycler_apply"
    private const val keyLastDrawSignature = "mdwechat_modern_chat_bubble_last_draw_signature"
    private const val bubbleProbeFile = "chat_bubble_probe.txt"
    private const val enableDrawApply = false
    private const val enableVisibleWindowApply = false

    override fun provideStaticHookers(): List<Hooker>? {
        if (!enableModernChatBubble) {
            LogUtil.log("ModernChatBubble disabled while custom renderer is redesigned")
            return emptyList()
        }
        return if (enableLegacyBubbleFallbacks) {
            listOf(
                textViewDrawFallbackHook,
                viewAttachHook,
                recyclerViewChildFallbackHook,
                recyclerViewAttachHook,
                chattingItemBindHook,
                chatAdapterBindHook,
                recyclerViewAdapterBindHook,
                backgroundResetHook
            )
        } else {
            listOf(
                chattingItemBindHook,
                chatAdapterBindHook,
                recyclerViewAdapterBindHook
            )
        }
    }

    private val textViewDrawFallbackHook = Hooker {
        if (!enableDrawApply) {
            logOnce("textView.onDraw.disabled", "ModernChatBubble TextView.onDraw apply disabled")
            return@Hooker
        }
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "onDraw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        val view = param?.thisObject as? TextView ?: return
                        applyFromDrawIfNeeded(view)
                    }
                }
            )
            logOnce("textView.onDraw", "ModernChatBubble hooked TextView.onDraw fallback")
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker TextView.onDraw failed: ${throwable.javaClass.simpleName}")
        }
    }

    private val viewAttachHook = Hooker {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val view = param?.thisObject as? View ?: return
                        if (isChatRecyclerView(view)) {
                            installRecyclerAdapterHooks(view as ViewGroup, "viewAttach")
                        }
                    }
                }
            )
            logOnce("view.onAttached", "ModernChatBubble hooked View.onAttachedToWindow")
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker View.onAttachedToWindow failed: ${throwable.javaClass.simpleName}")
        }
    }

    private val recyclerViewChildFallbackHook = Hooker {
        try {
            XposedHelpers.findAndHookMethod(
                ViewGroup::class.java,
                "onViewAdded",
                CC.View,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val parent = param?.thisObject as? ViewGroup ?: return
                        if (!isRecyclerViewLike(parent)) {
                            return
                        }
                        val child = param.args?.getOrNull(0) as? View ?: return
                        applyRecyclerChild(parent, child)
                    }
                }
            )
            logOnce("fallback.onViewAdded", "ModernChatBubble hooked ViewGroup.onViewAdded fallback")
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker fallback onViewAdded failed: ${throwable.javaClass.simpleName}")
        }
    }

    private val recyclerViewAttachHook = Hooker {
        listOf(
            "com.tencent.mm.pluginsdk.ui.tools.ScrollControlRecyclerView",
            "com.tencent.mm.view.recyclerview.WxRecyclerView",
            "androidx.recyclerview.widget.RecyclerView",
            "android.support.v7.widget.RecyclerView"
        ).forEach { className ->
            val recyclerClass = findClassQuietly(className)
            logOnce("class.$className", "ModernChatBubble class $className found=${recyclerClass != null}")
            if (recyclerClass == null) {
                return@forEach
            }
            hookRecyclerViewClass(recyclerClass)
        }
    }

    private fun hookRecyclerViewClass(recyclerClass: Class<*>) {
        val className = recyclerClass.name
        if (hookedRecyclerClasses.contains(className)) {
            return
        }
        hookedRecyclerClasses.add(className)
        try {
            XposedHelpers.findAndHookMethod(recyclerClass, "onViewAdded", CC.View, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val child = param?.args?.getOrNull(0) as? View ?: return
                    val parent = param.thisObject as? ViewGroup ?: return
                    applyRecyclerChild(parent, child)
                }
            })
            logOnce("onViewAdded.${recyclerClass.name}", "ModernChatBubble hooked onViewAdded ${recyclerClass.name}")
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker onViewAdded skip ${recyclerClass.name}: ${throwable.javaClass.simpleName}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                recyclerClass,
                "onChildAttachedToWindow",
                CC.View,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val child = param?.args?.getOrNull(0) as? View ?: return
                        val parent = param.thisObject as? ViewGroup ?: return
                        applyRecyclerChild(parent, child)
                    }
                }
            )
            logOnce("onChildAttached.${recyclerClass.name}", "ModernChatBubble hooked onChildAttached ${recyclerClass.name}")
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker onChildAttached skip ${recyclerClass.name}: ${throwable.javaClass.simpleName}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                recyclerClass,
                "onScrolled",
                CC.Int,
                CC.Int,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val parent = param?.thisObject as? ViewGroup ?: return
                        scheduleRecyclerVisibleApply(parent, "onScrolled")
                    }
                }
            )
            logOnce("onScrolled.${recyclerClass.name}", "ModernChatBubble hooked onScrolled ${recyclerClass.name}")
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker onScrolled skip ${recyclerClass.name}: ${throwable.javaClass.simpleName}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                recyclerClass,
                "onScrollStateChanged",
                CC.Int,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val parent = param?.thisObject as? ViewGroup ?: return
                        scheduleRecyclerVisibleApply(parent, "onScrollStateChanged")
                    }
                }
            )
            logOnce(
                "onScrollStateChanged.${recyclerClass.name}",
                "ModernChatBubble hooked onScrollStateChanged ${recyclerClass.name}"
            )
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker onScrollStateChanged skip ${recyclerClass.name}: ${throwable.javaClass.simpleName}")
        }

        hookRecyclerAdapterSetters(recyclerClass)
        logOnce("layoutScroll.${recyclerClass.name}", "ModernChatBubble uses bind-time grouping for ${recyclerClass.name}")
    }

    private fun hookRecyclerAdapterSetters(recyclerClass: Class<*>) {
        listOf("setAdapter", "swapAdapter").forEach { methodName ->
            try {
                XposedBridge.hookAllMethods(recyclerClass, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val recycler = param?.thisObject as? ViewGroup ?: return
                        installRecyclerAdapterHooks(recycler, methodName)
                    }
                })
                logOnce("$methodName.${recyclerClass.name}", "ModernChatBubble hooked $methodName ${recyclerClass.name}")
            } catch (throwable: Throwable) {
                LogUtil.log("ModernChatBubbleHooker $methodName skip ${recyclerClass.name}: ${throwable.javaClass.simpleName}")
            }
        }
    }

    private val chatAdapterBindHook = Hooker {
        hookWxAdapterClassLoad()
        hookChatAdapterDirect()
    }

    private val chattingItemBindHook = Hooker {
        hookWxAdapterClassLoad()
        hookChattingItemBindDirect()
    }

    private val recyclerViewAdapterBindHook = Hooker {
        hookWxAdapterClassLoad()
        hookWxRecyclerAdapterDirect()

        listOf(
            "com.tencent.mm.pluginsdk.ui.tools.r0",
            "com.tencent.mm.pluginsdk.ui.tools.r3",
            "com.tencent.mm.view.recyclerview.WxRecyclerAdapter",
            "androidx.recyclerview.widget.w1",
            "androidx.recyclerview.widget.RecyclerView\$Adapter",
            "android.support.v7.widget.RecyclerView\$Adapter"
        ).forEach { className ->
            findClassQuietly(className)?.let { adapterClass ->
                hookConcreteAdapter(adapterClass)
            }
        }

        listOf(
            "androidx.recyclerview.widget.RecyclerView",
            "android.support.v7.widget.RecyclerView"
        ).forEach { recyclerClassName ->
            val adapterClass = findClassQuietly("$recyclerClassName\$Adapter")
            val holderClass = findClassQuietly("$recyclerClassName\$ViewHolder")
            logOnce(
                "adapterClass.$recyclerClassName",
                "ModernChatBubble adapter $recyclerClassName found=${adapterClass != null} holder=${holderClass != null}"
            )
            if (adapterClass == null || holderClass == null) {
                return@forEach
            }
            try {
                XposedHelpers.findAndHookMethod(
                    adapterClass,
                    "bindViewHolder",
                    holderClass,
                    CC.Int,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam?) {
                            val adapter = param?.thisObject ?: return
                            val holder = param?.args?.getOrNull(0) ?: return
                            val position = param.args?.getOrNull(1) as? Int ?: return
                            val itemView = extractItemView(holder) ?: return
                            hookConcreteAdapter(adapter.javaClass)
                            ModernChatBubbleRenderer.applyFromAdapterBind(adapter, position, itemView)
                        }
                    }
                )
                logOnce("bindViewHolder.$recyclerClassName", "ModernChatBubble hooked bindViewHolder $recyclerClassName")
            } catch (throwable: Throwable) {
                LogUtil.log("ModernChatBubbleHooker bindViewHolder skip $recyclerClassName: ${throwable.javaClass.simpleName}")
            }
        }
    }

    private fun hookWxAdapterClassLoad() {
        if (classLoadHookInstalled) {
            return
        }
        classLoadHookInstalled = true
        try {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val className = param?.args?.getOrNull(0) as? String ?: return
                    val adapterClass = param.result as? Class<*> ?: return
                    when {
                        className == "com.tencent.mm.view.recyclerview.WxRecyclerView" -> {
                            if (enableLegacyBubbleFallbacks) {
                                hookRecyclerViewClass(adapterClass)
                            }
                            logOnce(
                                "loadClass.$className",
                                "ModernChatBubble class-load hooked $className loader=${adapterClass.classLoader}"
                            )
                        }
                        className == "com.tencent.mm.pluginsdk.ui.tools.ScrollControlRecyclerView" -> {
                            if (enableLegacyBubbleFallbacks) {
                                hookRecyclerViewClass(adapterClass)
                            }
                            logOnce(
                                "loadClass.$className",
                                "ModernChatBubble class-load hooked $className loader=${adapterClass.classLoader}"
                            )
                        }
                        className == "com.tencent.mm.ui.chatting.adapter.x0" ||
                                className == "com.tencent.mm.ui.chatting.adapter.i1" -> {
                            hookConcreteAdapter(adapterClass)
                            logOnce(
                                "loadClass.$className",
                                "ModernChatBubble class-load hooked chat adapter $className loader=${adapterClass.classLoader}"
                            )
                        }
                        className == "com.tencent.mm.pluginsdk.ui.tools.r0" ||
                                className == "com.tencent.mm.pluginsdk.ui.tools.r3" -> {
                            hookConcreteAdapter(adapterClass)
                            logOnce(
                                "loadClass.$className",
                                "ModernChatBubble class-load hooked chat adapter wrapper $className loader=${adapterClass.classLoader}"
                            )
                        }
                        className.startsWith("com.tencent.mm.ui.chatting.adapter.") &&
                                isRecyclerAdapterLikeClass(adapterClass) -> {
                            hookConcreteAdapter(adapterClass)
                        }
                        className == "com.tencent.mm.view.recyclerview.WxRecyclerAdapter" -> {
                            hookConcreteAdapter(adapterClass)
                            hookKnownWxAdapterMethods(adapterClass)
                            logOnce(
                                "loadClass.$className",
                                "ModernChatBubble class-load hooked $className loader=${adapterClass.classLoader}"
                            )
                        }
                        className.startsWith("com.tencent.mm.ui.chatting.viewitems.") -> {
                            hookChattingItemBindClass(adapterClass)
                        }
                    }
                }
            })
            logOnce("ClassLoader.loadClass", "ModernChatBubble hooked ClassLoader.loadClass for chat bind targets")
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker ClassLoader hook failed: ${throwable.javaClass.simpleName}")
        }
    }

    private fun hookChatAdapterDirect() {
        listOf(
            "com.tencent.mm.ui.chatting.adapter.x0",
            "com.tencent.mm.ui.chatting.adapter.i1",
            "com.tencent.mm.pluginsdk.ui.tools.r0",
            "com.tencent.mm.pluginsdk.ui.tools.r3"
        ).forEach { className ->
            val adapterClass = findClassQuietly(className)
            logOnce(
                "direct.chatAdapter.$className",
                "ModernChatBubble direct chat adapter $className found=${adapterClass != null}"
            )
            adapterClass?.let { hookConcreteAdapter(it) }
        }
    }

    private fun hookWxRecyclerAdapterDirect() {
        val adapterClass = findClassQuietly("com.tencent.mm.view.recyclerview.WxRecyclerAdapter")
        logOnce(
            "direct.WxRecyclerAdapter.lookup",
            "ModernChatBubble direct WxRecyclerAdapter found=${adapterClass != null}"
        )
        if (adapterClass == null) {
            return
        }
        hookConcreteAdapter(adapterClass)
        hookKnownWxAdapterMethods(adapterClass)
    }

    private fun hookKnownWxAdapterMethods(adapterClass: Class<*>) {
        val hooked = adapterClass.declaredMethods
            .asSequence()
            .filter { method -> method.name in setOf("c1", "d1", "o0", "p0") }
            .filter { method -> isAdapterBindLikeMethod(method) }
            .count { method -> hookBindMethod(method) }
        logOnce(
            "direct.WxRecyclerAdapter.methods.${adapterClass.name}",
            "ModernChatBubble direct WxRecyclerAdapter bindHooks=$hooked class=${adapterClass.name}"
        )
    }

    private fun hookChattingItemBindDirect() {
        listOf(
            "com.tencent.mm.ui.chatting.viewitems.c4",
            "com.tencent.mm.ui.chatting.viewitems.d5",
            "com.tencent.mm.ui.chatting.viewitems.j4",
            "com.tencent.mm.ui.chatting.viewitems.m4",
            "com.tencent.mm.ui.chatting.viewitems.s4",
            "com.tencent.mm.ui.chatting.viewitems.t4",
            "com.tencent.mm.ui.chatting.viewitems.z3"
        ).forEach { className ->
            findClassQuietly(className)?.let { itemClass ->
                hookChattingItemBindClass(itemClass)
            }
        }
    }

    private fun hookChattingItemBindClass(itemClass: Class<*>) {
        var hooked = 0
        itemClass.declaredMethods
            .filter { method -> isChattingItemBindLikeMethod(method) }
            .forEach { method ->
                if (hookChattingItemBindMethod(method)) {
                    hooked++
                }
            }
        if (hooked > 0) {
            logOnce(
                "chattingItem.${itemClass.name}",
                "ModernChatBubble hooked chatting item bind class=${itemClass.name} methods=$hooked"
            )
        }
    }

    private fun hookChattingItemBindMethod(method: Method): Boolean {
        return try {
            val methodKey = bindMethodKey(method)
            if (hookedChattingItemMethods.contains(methodKey)) {
                return false
            }
            hookedChattingItemMethods.add(methodKey)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val holder = param?.args?.getOrNull(0) ?: return
                    val msgInfo = param.args?.getOrNull(2) ?: return
                    val itemView = extractItemView(holder) ?: return
                    ModernChatBubbleRenderer.applyFromChattingItemBind(itemView, msgInfo)
                }
            })
            true
        } catch (throwable: Throwable) {
            LogUtil.log(
                "ModernChatBubbleHooker chat item hook skip ${method.declaringClass.name}.${method.name}: " +
                        throwable.javaClass.simpleName
            )
            false
        }
    }

    private fun isChattingItemBindLikeMethod(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE || method.name != "j") {
            return false
        }
        val params = method.parameterTypes
        return params.size == 4 &&
                params[0].name == "com.tencent.mm.ui.chatting.viewitems.f0" &&
                params[1].name == "vb4.c" &&
                params[2].name == "com.tencent.mm.storage.k9" &&
                params[3] == String::class.java
    }

    private fun hookConcreteAdapter(adapterClass: Class<*>) {
        val className = adapterClass.name
        if (hookedAdapterClasses.contains(className)) {
            return
        }
        hookedAdapterClasses.add(className)
        var hooked = 0
        var current: Class<*>? = adapterClass
        while (current != null && current != Any::class.java) {
            current.declaredMethods
                .filter { method -> isAdapterBindLikeMethod(method) }
                .forEach { method ->
                    if (hookBindMethod(method)) {
                        hooked++
                    }
                }
            current = current.superclass
        }
        logOnce(
            "concreteAdapter.$className",
            "ModernChatBubble concrete adapter class=$className bindHooks=$hooked"
        )
    }

    private fun hookBindMethod(method: Method): Boolean {
        return try {
            val methodKey = bindMethodKey(method)
            if (hookedBindMethods.contains(methodKey)) {
                return false
            }
            hookedBindMethods.add(methodKey)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val adapter = param?.thisObject ?: return
                    val holder = param.args?.getOrNull(0) ?: return
                    val position = param.args?.getOrNull(1) as? Int ?: return
                    val itemView = extractItemView(holder) ?: return
                    ModernChatBubbleRenderer.applyFromAdapterBind(adapter, position, itemView)
                }
            })
            true
        } catch (throwable: Throwable) {
            LogUtil.log(
                "ModernChatBubbleHooker onBind hook skip ${method.declaringClass.name}.${method.name}: " +
                        throwable.javaClass.simpleName
            )
            false
        }
    }

    private fun bindMethodKey(method: Method): String {
        return buildString {
            append(method.declaringClass.name)
            append('#')
            append(method.name)
            append('(')
            append(method.parameterTypes.joinToString(",") { it.name })
            append(')')
        }
    }

    private fun isAdapterBindLikeMethod(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) {
            return false
        }
        val params = method.parameterTypes
        if (params.size < 2 || params[1] != CC.Int) {
            return false
        }
        if (!isViewHolderLike(params[0])) {
            return false
        }
        if (params.size == 2) {
            return true
        }
        return params.size == 3 && (
                java.util.List::class.java.isAssignableFrom(params[2]) ||
                        params[2].name == "cj4.s"
                )
    }

    private fun isViewHolderLike(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            val name = current.name
            if (name == "androidx.recyclerview.widget.RecyclerView\$ViewHolder" ||
                name == "android.support.v7.widget.RecyclerView\$ViewHolder" ||
                name == "androidx.recyclerview.widget.c3" ||
                name == "cj4.n0" ||
                name.endsWith("RecyclerView\$ViewHolder")
            ) {
                return true
            }
            current = current.superclass
        }
        return false
    }

    private fun isRecyclerAdapterLikeClass(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            val name = current.name
            if (name == "androidx.recyclerview.widget.w1" ||
                name == "android.support.v7.widget.RecyclerView\$Adapter" ||
                name == "com.tencent.mm.pluginsdk.ui.tools.r3" ||
                name.endsWith("RecyclerView\$Adapter")
            ) {
                return true
            }
            current = current.superclass
        }
        return false
    }

    private fun extractItemView(holder: Any): View? {
        listOf("itemView", "convertView", "d").forEach { fieldName ->
            try {
                (XposedHelpers.getObjectField(holder, fieldName) as? View)?.let {
                    return it
                }
            } catch (_: Throwable) {
            }
        }

        var current: Class<*>? = holder.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (!View::class.java.isAssignableFrom(field.type)) {
                    return@forEach
                }
                try {
                    field.isAccessible = true
                    (field.get(holder) as? View)?.let {
                        return it
                    }
                } catch (_: Throwable) {
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun applyRecyclerChild(parent: ViewGroup, child: View) {
        if (!isChatRecyclerView(parent)) {
            return
        }
        val adapter = getRecyclerAdapter(parent)
        if (adapter == null) {
            return
        }
        hookConcreteAdapter(adapter.javaClass)
        val position = getChildAdapterPosition(parent, child)
        if (position >= 0) {
            val applied = ModernChatBubbleStyler.applyFromAdapterBind(adapter, position, child)
            if (!applied && enableVisibleWindowApply) {
                child.post {
                    val currentAdapter = getRecyclerAdapter(parent) ?: return@post
                    val currentPosition = getChildAdapterPosition(parent, child)
                    if (currentPosition >= 0) {
                        ModernChatBubbleStyler.applyFromAdapterBind(currentAdapter, currentPosition, child)
                    }
                    scheduleRecyclerVisibleApply(parent, "child.post")
                }
            }
        }
    }

    private fun installRecyclerAdapterHooks(recycler: ViewGroup, source: String) {
        val adapter = getRecyclerAdapter(recycler) ?: return
        hookConcreteAdapter(adapter.javaClass)
        probeRecyclerAdapter(recycler, adapter, source)
        if (enableVisibleWindowApply) {
            applyVisibleChildrenFromRecycler(recycler, adapter, source)
            scheduleRecyclerVisibleApply(recycler, "$source.posted")
        }
    }

    private fun scheduleRecyclerVisibleApply(recycler: ViewGroup, source: String) {
        if (!enableVisibleWindowApply) {
            return
        }
        if (!isChatRecyclerView(recycler) ||
            XposedHelpers.getAdditionalInstanceField(recycler, keyPendingRecyclerApply) == true
        ) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(recycler, keyPendingRecyclerApply, true)
        recycler.post {
            XposedHelpers.removeAdditionalInstanceField(recycler, keyPendingRecyclerApply)
            val adapter = getRecyclerAdapter(recycler) ?: return@post
            val applied = applyVisibleChildrenFromRecycler(recycler, adapter, source)
            if (applied == 0 && recycler.childCount > 0) {
                recycler.postDelayed({
                    val lateAdapter = getRecyclerAdapter(recycler) ?: return@postDelayed
                    val lateApplied = applyVisibleChildrenFromRecycler(recycler, lateAdapter, "$source.afterLayout")
                    if (lateApplied == 0 && recycler.childCount > 0) {
                        recycler.postDelayed({
                            val finalAdapter = getRecyclerAdapter(recycler) ?: return@postDelayed
                            applyVisibleChildrenFromRecycler(recycler, finalAdapter, "$source.afterDraw")
                        }, 320L)
                    }
                }, 80L)
            }
        }
    }

    private fun applyVisibleChildrenFromRecycler(recycler: ViewGroup, adapter: Any, source: String): Int {
        val applied = ModernChatBubbleStyler.applyVisibleChildrenFromAdapter(recycler, adapter)
        if (ModernChatBubbleStyler.shouldWriteVerboseProbe()) {
            ModernChatBubbleStyler.probeRuntime(
                recycler.context,
                bubbleProbeFile,
                "ModernChatBubble visible apply source=$source adapter=${adapter.javaClass.name} " +
                        "children=${recycler.childCount} applied=$applied"
            )
        }
        return applied
    }

    private fun getRecyclerAdapter(parent: ViewGroup): Any? {
        return try {
            XposedHelpers.callMethod(parent, "getAdapter")
        } catch (_: Throwable) {
            null
        }
    }

    private fun getChildAdapterPosition(parent: ViewGroup, child: View): Int {
        listOf("getChildAdapterPosition", "getChildLayoutPosition").forEach { methodName ->
            val value = try {
                XposedHelpers.callMethod(parent, methodName, child) as? Int
            } catch (_: Throwable) {
                null
            }
            if (value != null && value >= 0) {
                return value
            }
        }
        val holder = listOf("getChildViewHolder", "findContainingViewHolder")
            .asSequence()
            .mapNotNull { methodName ->
                try {
                    XposedHelpers.callMethod(parent, methodName, child)
                } catch (_: Throwable) {
                    null
                }
            }
            .firstOrNull()
        val holderPosition = readAdapterPositionFromHolder(holder)
        if (holderPosition >= 0) {
            return holderPosition
        }
        return -1
    }

    private fun readAdapterPositionFromHolder(holder: Any?): Int {
        holder ?: return -1
        listOf("getAdapterPosition", "getLayoutPosition", "getBindingAdapterPosition", "getAbsoluteAdapterPosition")
            .forEach { methodName ->
                val value = try {
                    XposedHelpers.callMethod(holder, methodName) as? Int
                } catch (_: Throwable) {
                    null
                }
                if (value != null && value >= 0) {
                    return value
                }
            }
        return listOf("a", "c", "mPosition", "mOldPosition")
            .asSequence()
            .mapNotNull { fieldName ->
                try {
                    XposedHelpers.getIntField(holder, fieldName)
                } catch (_: Throwable) {
                    null
                }
            }
            .firstOrNull { it >= 0 } ?: -1
    }

    private fun findClassQuietly(className: String): Class<*>? {
        return try {
            Class.forName(className, false, WechatGlobal.wxLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private val backgroundResetHook = Hooker {
        listOf("setBackground", "setBackgroundDrawable").forEach { methodName ->
            try {
                XposedBridge.hookAllMethods(View::class.java, methodName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        val view = param?.thisObject as? View ?: return
                        replaceBackgroundArgumentIfNeeded(view, param)
                    }

                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val view = param?.thisObject as? View ?: return
                        if (shouldRestoreCachedBubble(view)) {
                            ModernChatBubbleStyler.restoreLastBubbleBackground(view)
                        } else {
                            scheduleApplyAfterBackgroundChange(view)
                        }
                    }
                })
            } catch (throwable: Throwable) {
                LogUtil.log("ModernChatBubbleHooker $methodName failed: ${throwable.javaClass.simpleName}")
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setBackgroundResource",
                CC.Int,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        val view = param?.thisObject as? View ?: return
                        if (!ModernChatBubbleStyler.canHandleBubbleBackground(view)) {
                            return
                        }
                        if (ModernChatBubbleStyler.applyPendingBubbleBackground(view)) {
                            return
                        }
                        if (shouldRestoreCachedBubble(view)) {
                            ModernChatBubbleStyler.restoreLastBubbleBackground(view)
                        } else {
                            scheduleApplyAfterBackgroundChange(view)
                        }
                    }
                }
            )
        } catch (throwable: Throwable) {
            LogUtil.log("ModernChatBubbleHooker setBackgroundResource failed: ${throwable.javaClass.simpleName}")
        }

        logOnce("backgroundReset", "ModernChatBubble hooked background reset")
    }

    private fun replaceBackgroundArgumentIfNeeded(view: View, param: XC_MethodHook.MethodHookParam) {
        if (!ModernChatBubbleStyler.canHandleBubbleBackground(view)) {
            return
        }
        val pendingDrawable = ModernChatBubbleStyler.createPendingBubbleDrawable(view)
        if (pendingDrawable != null && param.args != null && param.args.isNotEmpty()) {
            param.args[0] = pendingDrawable
            return
        }
        if (!shouldRestoreCachedBubble(view)) {
            return
        }
        val drawable = ModernChatBubbleStyler.createLastBubbleDrawable(view) ?: return
        if (param.args != null && param.args.isNotEmpty()) {
            param.args[0] = drawable
        }
    }

    private fun scheduleApplyAfterBackgroundChange(view: View) {
        if (!ModernChatBubbleStyler.canHandleBubbleBackground(view) ||
            !ModernChatBubbleStyler.isEnabled ||
            ModernChatBubbleStyler.isReplacingBackground(view) ||
            !ModernChatBubbleStyler.isKnownMessageView(view) ||
            XposedHelpers.getAdditionalInstanceField(view, keyPendingApply) == true
        ) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(view, keyPendingApply, true)
        view.post {
            XposedHelpers.removeAdditionalInstanceField(view, keyPendingApply)
            ModernChatBubbleStyler.applyFromChangedView(view)
        }
    }

    private fun applyFromDrawIfNeeded(view: TextView) {
        probeDrawOnce(view, "entry")
        if (!ModernChatBubbleStyler.isEnabled ||
            ModernChatBubbleStyler.isReplacingBackground(view) ||
            !ModernChatBubbleStyler.isMessageTextView(view)
        ) {
            return
        }
        val signature = ModernChatBubbleStyler.drawSignature(view) ?: return
        if (XposedHelpers.getAdditionalInstanceField(view, keyLastDrawSignature) == signature) {
            if (ModernChatBubbleStyler.isKnownMessageView(view)) {
                ModernChatBubbleStyler.restoreLastBubbleBackground(view)
            }
            return
        }
        XposedHelpers.setAdditionalInstanceField(view, keyLastDrawSignature, signature)
        ModernChatBubbleStyler.applyFromRenderedMessage(view)
    }

    private fun probeDrawOnce(view: TextView, stage: String) {
        if (!ModernChatBubbleStyler.isMessageTextView(view)) {
            return
        }
        val key = "${stage}:${view.text?.hashCode()}:${view.left},${view.top}"
        if (drawProbeLogs.size >= 24 || drawProbeLogs.contains(key)) {
            return
        }
        drawProbeLogs.add(key)
        ModernChatBubbleStyler.probeDrawEntry(view, stage)
    }

    private fun shouldRestoreCachedBubble(view: View): Boolean {
        return ModernChatBubbleStyler.isEnabled &&
                ModernChatBubbleStyler.isKnownMessageView(view) &&
                !ModernChatBubbleStyler.isReplacingBackground(view)
    }

    private fun isRecyclerViewLike(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("RecyclerView") || name.contains("WxRecyclerView")
    }

    private fun isChatRecyclerView(view: View): Boolean {
        if (view !is ViewGroup || !isRecyclerViewLike(view)) {
            return false
        }
        return getResourceEntryName(view) == "bp0"
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

    private fun probeRecyclerAdapter(recycler: View, adapter: Any, source: String) {
        if (!ModernChatBubbleStyler.shouldWriteVerboseProbe()) {
            return
        }
        val key = "$source:${adapter.javaClass.name}:${System.identityHashCode(recycler)}"
        if (recyclerProbeLogs.size >= 24 || recyclerProbeLogs.contains(key)) {
            return
        }
        recyclerProbeLogs.add(key)
        ModernChatBubbleStyler.probeRuntime(
            recycler.context,
            bubbleProbeFile,
            "ModernChatBubble recycler adapter source=$source recycler=${recycler.javaClass.name} " +
                    "res=${getResourceEntryName(recycler)} adapter=${adapter.javaClass.name} " +
                    "super=${adapter.javaClass.superclass?.name}"
        )
    }

    private fun logOnce(key: String, message: String): Unit? {
        if (installLogs.contains(key)) {
            return Unit
        }
        installLogs.add(key)
        LogUtil.log(message)
        return Unit
    }
}
