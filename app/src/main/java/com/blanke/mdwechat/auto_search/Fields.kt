package com.blanke.mdwechat.auto_search

import com.blanke.mdwechat.CC
import com.blanke.mdwechat.Version
import com.blanke.mdwechat.auto_search.Classes.ContactFragment
import com.blanke.mdwechat.auto_search.Classes.ConversationFragment
import com.blanke.mdwechat.auto_search.Classes.ConversationListView
import com.blanke.mdwechat.auto_search.Classes.ConversationWithAppBrandListView
import com.blanke.mdwechat.auto_search.Classes.CustomViewPager
import com.blanke.mdwechat.auto_search.Classes.HomeUI
import com.blanke.mdwechat.auto_search.Classes.LauncherUI
//import com.blanke.mdwechat.auto_search.Classes.LauncherUIBottomTabViewItem
import com.blanke.mdwechat.auto_search.Classes.MainTabUI
import com.blanke.mdwechat.auto_search.Classes.PreferenceFragment
import com.blanke.mdwechat.auto_search.Logs.i
import com.blanke.mdwechat.util.ReflectionUtil.findFieldsWithType
import java.lang.reflect.Field

object Fields {
    private val actionBarTypeNames = arrayOf(
            "android.support.v7.app.ActionBar",
            "androidx.appcompat.app.ActionBar"
    )
    private val appCompatInternalPackages = arrayOf(
            "android.support.v7.app.",
            "androidx.appcompat.app."
    )

    private fun loadClass(typeName: String): Class<*>? {
        return try {
            Class.forName(typeName, false, WechatGlobal.wxLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun fieldMatchesActionBar(field: Field, actionBarClass: Class<*>): Boolean {
        return generateSequence(field.type) { it.superclass }.any { currentClass ->
            actionBarClass.isAssignableFrom(currentClass) ||
                    currentClass.declaredFields.any { nestedField ->
                        actionBarClass.isAssignableFrom(nestedField.type)
                    }
        }
    }

    private fun allFields(clazz: Class<*>): Sequence<Field> {
        return generateSequence(clazz) { it.superclass }
                .takeWhile { it != Any::class.java }
                .flatMap { it.declaredFields.asSequence() }
    }

    private fun logHomeUIHierarchy(homeUI: Class<*>) {
        generateSequence(homeUI) { it.superclass }
                .take(6)
                .forEach { clazz ->
                    i("HomeUI调试 ${clazz.name}")
                    clazz.declaredFields.forEach { field ->
                        i("  ${field.name}: ${field.type.name}")
                    }
                }
    }

    val LauncherUI_mHomeUI: Field?
        get() {
            return findFieldsWithType(LauncherUI!!, HomeUI!!.name)
                    .firstOrNull()?.apply { isAccessible = true }
        }

    val HomeUI_mMainTabUI: Field?
        get() {
            return findFieldsWithType(HomeUI!!, MainTabUI!!.name)
                    .firstOrNull()?.apply { isAccessible = true }
        }

    val MainTabUI_mCustomViewPager: Field?
        get() {
            return findFieldsWithType(
                    MainTabUI!!, CustomViewPager!!.name)
                    .firstOrNull()?.apply { isAccessible = true }
        }

    val HomeUI_mActionBar: Field?
        get() {
            val homeUI = HomeUI ?: return null
            val actionBarClasses = actionBarTypeNames
                    .mapNotNull(::loadClass)

            actionBarClasses
                    .forEach { actionBarClass ->
                        allFields(homeUI).firstOrNull { actionBarClass.isAssignableFrom(it.type) }
                                ?.apply { isAccessible = true }
                                ?.let { return it }
                    }

            actionBarClasses
                    .forEach { actionBarClass ->
                        allFields(homeUI).firstOrNull { fieldMatchesActionBar(it, actionBarClass) }
                                ?.apply { isAccessible = true }
                                ?.let { return it }
                    }

            homeUI.declaredFields
                    .firstOrNull { candidateField ->
                        val typeName = candidateField.type.name
                        appCompatInternalPackages.any { typeName.startsWith(it) } &&
                                typeName !in actionBarTypeNames
                    }
                    ?.apply { isAccessible = true }
                    ?.let { return it }

            logHomeUIHierarchy(homeUI)
            return null
        }

//    val LauncherUIBottomTabViewItem_mTextViews: List<Field>?
//        get() {
//            return findFieldsWithType(LauncherUIBottomTabViewItem!!, CC.TextView.name)
//        }

    val ConversationFragment_mListView: Field?
        get() {
            if (WechatGlobal.wxVersion!! < Version("7.0.3")) {
                return findFieldsWithType(ConversationFragment!!, ConversationWithAppBrandListView!!.name)
                        .firstOrNull()?.apply { isAccessible = true }
            }
            return findFieldsWithType(ConversationFragment!!, ConversationListView!!.name)
                    .firstOrNull()?.apply { isAccessible = true }
        }

    val ContactFragment_mListView: Field?
        get() {
            if (ContactFragment!!::class.java.name == ClassNotSupported::class.java.name) {
                return findFieldsWithType(ContactFragment!!, CC.ListView.name)
                        .firstOrNull()?.apply { isAccessible = true }
            } else {
                      return findFieldsWithType(ClassNotSupported().javaClass, CC.Field.name)
                        .firstOrNull()?.apply { isAccessible = true }
            }
        }

    val PreferenceFragment_mListView: Field?
        get() {
            return findFieldsWithType(PreferenceFragment!!, CC.ListView.name)
                    .firstOrNull()?.apply { isAccessible = true }
        }
}
