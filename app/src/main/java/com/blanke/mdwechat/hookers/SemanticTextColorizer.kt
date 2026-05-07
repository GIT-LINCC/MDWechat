package com.blanke.mdwechat.hookers

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TextAppearanceSpan
import android.view.View
import android.widget.TextView
import com.blanke.mdwechat.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections

object SemanticTextColorizer {
    private const val keyCachedSpannedText = "mdwechat_semantic_cached_spanned_text"
    private const val keySemanticTextColor = "mdwechat_semantic_text_color"
    private const val keySemanticPressedColor = "mdwechat_semantic_pressed_color"
    private val hookedClasses = Collections.synchronizedSet(mutableSetOf<String>())

    fun installWechatHooks(classLoader: ClassLoader?) {
        classLoader ?: return
        listOf(
            "com.tencent.mm.pluginsdk.ui.span.z0",
            "com.tencent.mm.ui.widget.MMNeat7extView",
            "com.tencent.neattextview.textview.view.NeatTextView"
        ).forEach { className ->
            val clazz = try {
                Class.forName(className, false, classLoader)
            } catch (_: Throwable) {
                null
            }
            clazz?.let { onWechatClassLoaded(it) }
        }
    }

    fun onWechatClassLoaded(clazz: Class<*>) {
        when (clazz.name) {
            "com.tencent.mm.pluginsdk.ui.span.z0" -> hookPressableClickSpan(clazz)
            "com.tencent.mm.ui.widget.MMNeat7extView",
            "com.tencent.neattextview.textview.view.NeatTextView" -> hookNeatTextView(clazz)
        }
    }

    fun apply(view: View, color: Int) {
        val source = readText(view) ?: return
        val spanned = source as? Spanned ?: return
        val spans = semanticSpans(spanned)
        if (spans.isEmpty()) {
            return
        }
        val editable = when (source) {
            is Spannable -> source
            else -> SpannableStringBuilder(source)
        }
        val ranges = spans.map { it.range }.toSet()
        spans.forEach { span ->
            recolorSpan(span.span, color)
        }
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach { span ->
            val range = spanRange(editable, span) ?: return@forEach
            if (ranges.any { overlaps(it, range) }) {
                editable.removeSpan(span)
            }
        }
        ranges.forEach { range ->
            editable.setSpan(
                ForegroundColorSpan(color),
                range.first,
                range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (editable !== source) {
            writeText(view, editable)
        } else {
            view.invalidate()
        }
    }

    private fun hookPressableClickSpan(spanClass: Class<*>) {
        val hookKey = "span:${spanClass.name}"
        if (!hookedClasses.add(hookKey)) {
            return
        }
        try {
            XposedBridge.hookAllMethods(spanClass, "setColor", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    val span = param?.thisObject ?: return
                    val color = semanticColor(span) ?: return
                    val args = param.args ?: return
                    if (args.size >= 2) {
                        args[0] = color
                        args[1] = pressedColor(span, color)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam?) {
                    val span = param?.thisObject ?: return
                    val color = semanticColor(span) ?: return
                    writeSpanColorFields(span, color)
                }
            })
        } catch (_: Throwable) {
        }
        try {
            XposedBridge.hookAllMethods(spanClass, "setColorConfig", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val span = param?.thisObject ?: return
                    val color = semanticColor(span) ?: return
                    writeSpanColorFields(span, color)
                }
            })
        } catch (_: Throwable) {
        }
        try {
            XposedBridge.hookAllMethods(spanClass, "updateDrawState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val span = param?.thisObject ?: return
                    val textPaint = param.args?.getOrNull(0) as? TextPaint ?: return
                    val color = semanticColor(span) ?: return
                    textPaint.color = color
                    textPaint.linkColor = color
                    textPaint.bgColor = if (isSpanPressed(span)) {
                        pressedColor(span, color)
                    } else {
                        0
                    }
                    textPaint.setUnderlineText(false)
                }
            })
            LogUtil.log("SemanticTextColorizer hooked ${spanClass.name}")
        } catch (throwable: Throwable) {
            LogUtil.log("SemanticTextColorizer updateDrawState hook failed: ${throwable.javaClass.simpleName}")
        }
    }

    private fun hookNeatTextView(viewClass: Class<*>) {
        hookNeatTextMethod(viewClass, "setText")
        hookNeatTextMethod(viewClass, "b")
        hookNeatTextMethod(viewClass, "c")
    }

    private fun hookNeatTextMethod(viewClass: Class<*>, methodName: String) {
        val hookKey = "text:${viewClass.name}:$methodName"
        if (!hookedClasses.add(hookKey)) {
            return
        }
        try {
            XposedBridge.hookAllMethods(viewClass, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val view = param?.thisObject as? View ?: return
                    val text = param.args?.firstOrNull() as? CharSequence
                    if (text is Spanned) {
                        XposedHelpers.setAdditionalInstanceField(view, keyCachedSpannedText, text)
                    } else {
                        XposedHelpers.removeAdditionalInstanceField(view, keyCachedSpannedText)
                    }
                }
            })
            LogUtil.log("SemanticTextColorizer hooked ${viewClass.name}.$methodName")
        } catch (throwable: Throwable) {
            LogUtil.log("SemanticTextColorizer $methodName hook failed ${viewClass.name}: ${throwable.javaClass.simpleName}")
        }
    }

    private fun semanticSpans(text: Spanned): List<SemanticSpan> {
        val spans = mutableListOf<SemanticSpan>()
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { span ->
            spanRange(text, span, allowFullText = false)?.let {
                spans.add(SemanticSpan(span, it))
            }
        }
        text.getSpans(0, text.length, TextAppearanceSpan::class.java).forEach { span ->
            if (span.textColor != null) {
                spanRange(text, span, allowFullText = false)?.let {
                    spans.add(SemanticSpan(span, it))
                }
            }
        }
        text.getSpans(0, text.length, ClickableSpan::class.java).forEach { span ->
            spanRange(text, span, allowFullText = true)?.let {
                spans.add(SemanticSpan(span, it))
            }
        }
        return spans
    }

    private fun spanRange(text: Spanned, span: Any, allowFullText: Boolean = false): IntRange? {
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) {
            return null
        }
        if (!allowFullText && start == 0 && end == text.length) {
            return null
        }
        return start until end
    }

    private fun recolorSpan(span: Any, color: Int) {
        if (span is ForegroundColorSpan || span is TextAppearanceSpan) {
            return
        }
        markSpanColor(span, color)
        if (writeSpanColorFields(span, color)) {
            return
        }
        try {
            XposedHelpers.callMethod(span, "setColor", color, pressedBackgroundColor(color))
        } catch (_: Throwable) {
        }
    }

    private fun markSpanColor(span: Any, color: Int) {
        XposedHelpers.setAdditionalInstanceField(span, keySemanticTextColor, color)
        XposedHelpers.setAdditionalInstanceField(span, keySemanticPressedColor, pressedBackgroundColor(color))
    }

    private fun writeSpanColorFields(span: Any, color: Int): Boolean {
        return try {
            XposedHelpers.setIntField(span, "mLinkColor", color)
            XposedHelpers.setIntField(span, "mBgColor", pressedBackgroundColor(color))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun semanticColor(span: Any): Int? {
        return XposedHelpers.getAdditionalInstanceField(span, keySemanticTextColor) as? Int
    }

    private fun pressedColor(span: Any, fallbackColor: Int): Int {
        return XposedHelpers.getAdditionalInstanceField(span, keySemanticPressedColor) as? Int
            ?: pressedBackgroundColor(fallbackColor)
    }

    private fun isSpanPressed(span: Any): Boolean {
        return try {
            XposedHelpers.callMethod(span, "getPress") as? Boolean == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun pressedBackgroundColor(color: Int): Int {
        return Color.argb(38, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun overlaps(first: IntRange, second: IntRange): Boolean {
        return first.first <= second.last && second.first <= first.last
    }

    private fun readText(view: View): CharSequence? {
        val rendered = if (view is TextView) {
            view.text
        } else try {
            XposedHelpers.callMethod(view, "getText") as? CharSequence
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(view, "a") as? CharSequence
            } catch (_: Throwable) {
                null
            }
        }
        val cached = XposedHelpers.getAdditionalInstanceField(view, keyCachedSpannedText) as? CharSequence
        if (cached is Spanned && (rendered == null || cached.toString() == rendered.toString())) {
            return cached
        }
        return rendered ?: cached
    }

    private fun writeText(view: View, text: CharSequence) {
        if (text is Spanned) {
            XposedHelpers.setAdditionalInstanceField(view, keyCachedSpannedText, text)
        }
        if (view is TextView) {
            view.text = text
            return
        }
        try {
            XposedHelpers.callMethod(view, "b", text)
            return
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(view, "c", text, TextView.BufferType.SPANNABLE, java.lang.Boolean.FALSE)
            return
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(view, "setText", text)
        } catch (_: Throwable) {
        }
    }

    private data class SemanticSpan(
        val span: Any,
        val range: IntRange
    )
}
