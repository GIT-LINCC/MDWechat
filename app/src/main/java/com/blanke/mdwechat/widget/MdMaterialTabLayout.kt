package com.blanke.mdwechat.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.blanke.mdwechat.util.MaterialTabBadgePolicy
import com.blanke.mdwechat.util.TabLayoutIndicatorPolicy
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.tabs.TabLayout

data class MaterialTabItem(
    @DrawableRes val iconRes: Int,
    val text: CharSequence,
    val iconBitmap: Bitmap? = null
)

class MdMaterialTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val tabLayout = TabLayout(context)
    private val items = mutableListOf<MaterialTabItem>()
    private var onTabSelected: ((Int) -> Unit)? = null
    private var dispatchSelectionCallback = true
    private var badgeBackgroundColor: Int? = null
    private var badgeTextColor: Int? = null
    private var selectedColor: Int = android.graphics.Color.BLACK
    private var unselectedColor: Int = android.graphics.Color.BLACK

    init {
        tabLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        tabLayout.tabMode = TabLayout.MODE_FIXED
        tabLayout.tabGravity = TabLayout.GRAVITY_FILL
        tabLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        tabLayout.isInlineLabel = false
        tabLayout.clipChildren = false
        tabLayout.clipToPadding = false
        addView(tabLayout)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                updateAllTabVisualStates()
                if (dispatchSelectionCallback) {
                    onTabSelected?.invoke(tab.position)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                updateAllTabVisualStates()
            }

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    fun setTabItems(tabItems: List<MaterialTabItem>) {
        items.clear()
        items.addAll(tabItems)
        tabLayout.removeAllTabs()
        tabItems.forEach { item ->
            val tab = tabLayout.newTab()
                .setText(item.text)
                .setIcon(requireDrawable(item).mutate())
            tab.contentDescription = item.text
            tabLayout.addTab(tab, false)
        }
        applyTabViewSpacing()
        updateAllTabVisualStates()
    }

    fun configureAppearance(
        selectedColor: Int,
        unselectedColor: Int,
        selectedIconColor: Int,
        unselectedIconColor: Int,
        indicatorColor: Int,
        indicatorHeightPx: Int,
        rippleColor: Int,
        badgeBackgroundColor: Int,
        badgeTextColor: Int,
        indicatorOnContent: Boolean,
        iconTintEnabled: Boolean = true
    ) {
        val textColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(selectedColor, unselectedColor)
        )
        val iconColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(selectedIconColor, unselectedIconColor)
        )
        this.selectedColor = selectedColor
        this.unselectedColor = unselectedColor
        tabLayout.setSelectedTabIndicatorColor(indicatorColor)
        tabLayout.setSelectedTabIndicatorHeight(indicatorHeightPx)
        tabLayout.isTabIndicatorFullWidth = !indicatorOnContent
        tabLayout.tabRippleColor = ColorStateList.valueOf(rippleColor)
        this.badgeBackgroundColor = badgeBackgroundColor
        this.badgeTextColor = badgeTextColor
        tabLayout.setTabTextColors(textColors)
        tabLayout.tabIconTint = if (iconTintEnabled) iconColors else null
        applyTabViewSpacing()
        for (index in 0 until tabLayout.tabCount) {
            val badge = tabLayout.getTabAt(index)?.badge
            badge?.let(::configureBadge)
        }
        updateAllTabVisualStates()
    }

    fun setCurrentTab(index: Int, fromUser: Boolean = false) {
        val tab = tabLayout.getTabAt(index) ?: return
        dispatchSelectionCallback = fromUser
        tab.select()
        dispatchSelectionCallback = true
        updateAllTabVisualStates()
    }

    fun syncIndicator(position: Int, positionOffset: Float) {
        val normalizedOffset = TabLayoutIndicatorPolicy.normalizePositionOffset(positionOffset)
        tabLayout.setScrollPosition(position, normalizedOffset, false, true)
    }

    fun showUnread(index: Int, count: Int) {
        val tab = tabLayout.getTabAt(index) ?: return
        when (MaterialTabBadgePolicy.resolveMode(count)) {
            MaterialTabBadgePolicy.Mode.NUMBER -> {
                val badge = tab.orCreateBadge
                badge.number = count
                badge.isVisible = true
                configureBadge(badge)
            }

            MaterialTabBadgePolicy.Mode.DOT -> {
                val badge = tab.orCreateBadge
                badge.clearNumber()
                badge.isVisible = true
                configureBadge(badge)
            }

            MaterialTabBadgePolicy.Mode.HIDDEN -> tab.removeBadge()
        }
    }

    fun hasUnread(index: Int): Boolean {
        val badge = tabLayout.getTabAt(index)?.badge ?: return false
        return badge.isVisible
    }

    fun clearUnread(index: Int) {
        tabLayout.getTabAt(index)?.removeBadge()
    }

    fun getTabViews(): List<View> {
        val strip = tabLayout.getChildAt(0) as? ViewGroup ?: return emptyList()
        return (0 until strip.childCount).map(strip::getChildAt)
    }

    fun setOnTabSelected(listener: (Int) -> Unit) {
        onTabSelected = listener
    }

    fun getMaterialTabLayout(): TabLayout = tabLayout

    private fun updateAllTabVisualStates() {
        tabLayout.invalidate()
    }

    private fun applyTabViewSpacing() {
        (tabLayout.getChildAt(0) as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        getTabViews().forEach { tabView ->
            tabView.clipToOutline = false
            tabView.translationY = 0f
            if (tabView is ViewGroup) {
                tabView.clipChildren = false
                tabView.clipToPadding = false
                findFirst(tabView, ImageView::class.java)?.translationY = dp(1).toFloat()
                findFirst(tabView, TextView::class.java)?.apply {
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    translationY = -dp(6).toFloat()
                }
            }
            tabView.setPaddingRelative(
                tabView.paddingStart,
                dp(4),
                tabView.paddingEnd,
                dp(2)
            )
        }
    }

    private fun configureBadge(badge: BadgeDrawable) {
        badgeBackgroundColor?.let { badge.backgroundColor = it }
        badgeTextColor?.let { badge.badgeTextColor = it }
        badge.badgeGravity = BadgeDrawable.TOP_END
        badge.verticalOffset = dp(6)
        badge.horizontalOffset = dp(1)
    }

    private fun <T : View> findFirst(root: View, clazz: Class<T>): T? {
        if (clazz.isInstance(root)) {
            return clazz.cast(root)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findFirst(root.getChildAt(index), clazz)?.let { return it }
            }
        }
        return null
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun requireDrawable(item: MaterialTabItem): Drawable {
        item.iconBitmap?.let { return BitmapDrawable(resources, it) }
        return AppCompatResources.getDrawable(context, item.iconRes)
            ?: error("Missing tab icon drawable: ${item.iconRes}")
    }
}
