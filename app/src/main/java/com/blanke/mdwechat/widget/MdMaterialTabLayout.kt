package com.blanke.mdwechat.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.blanke.mdwechat.util.MaterialTabBadgePolicy
import com.blanke.mdwechat.util.MaterialTabIconTransitionPolicy
import com.blanke.mdwechat.util.TabLayoutIndicatorPolicy
import com.google.android.material.animation.AnimationUtils as MaterialAnimationUtils
import com.google.android.material.tabs.TabLayout
import java.util.WeakHashMap

data class MaterialTabItem(
    @DrawableRes val iconRes: Int,
    val text: CharSequence,
    val iconBitmap: Bitmap? = null,
    @DrawableRes val outlineIconRes: Int? = null,
    @DrawableRes val filledIconRes: Int? = null
)

class MdMaterialTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private data class PillFrame(
        val left: Float,
        val top: Float
    )

    private data class TabHolder(
        val root: FrameLayout,
        val iconShell: FrameLayout,
        val localPill: View,
        val iconLayer: FrameLayout,
        val outlineIcon: ImageView,
        val filledIcon: ImageView,
        val label: TextView,
        val badge: TextView
    )

    private enum class PillAnimation {
        NONE,
        CLICK,
        SLIDE
    }

    private val compatibilityTabLayout = TabLayout(context)
    private val tabStrip = LinearLayout(context)
    private val selectedPill = View(context)
    private val iconAnimators = WeakHashMap<View, ValueAnimator>()
    private val fillAnimators = WeakHashMap<View, ValueAnimator>()
    private val items = mutableListOf<MaterialTabItem>()
    private val holders = mutableListOf<TabHolder>()
    private val unreadCounts = mutableMapOf<Int, Int>()
    private var onTabSelected: ((Int) -> Unit)? = null
    private var badgeBackgroundColor: Int? = null
    private var badgeTextColor: Int? = null
    private var selectedColor: Int = Color.BLACK
    private var unselectedColor: Int = Color.BLACK
    private var selectedIconTintColor: Int = MaterialTabIconTransitionPolicy.activeIconColor
    private var unselectedIconTintColor: Int = MaterialTabIconTransitionPolicy.inactiveIconColor
    private var selectedPillColor: Int = Color.TRANSPARENT
    private var iconTintEnabled: Boolean = true
    private var selectedPosition = -1
    private var localPillsSuppressed = false
    private var swipeSettleInProgress = false
    private var pendingSelectionSource = MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
    private val finishSwipeSettleRunnable = Runnable {
        swipeSettleInProgress = false
        hideSlidingPill()
        setLocalPillsSuppressed(false, animate = true)
    }

    init {
        clipChildren = false
        clipToPadding = false
        selectedPill.layoutParams = LayoutParams(dp(64), dp(32))
        selectedPill.visibility = View.INVISIBLE
        selectedPill.background = createPillDrawable(Color.TRANSPARENT)

        tabStrip.orientation = LinearLayout.HORIZONTAL
        tabStrip.gravity = Gravity.CENTER
        tabStrip.clipChildren = false
        tabStrip.clipToPadding = false
        tabStrip.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        addView(selectedPill)
        addView(tabStrip)
    }

    fun setTabItems(tabItems: List<MaterialTabItem>) {
        items.clear()
        items.addAll(tabItems)
        holders.clear()
        unreadCounts.clear()
        selectedPosition = -1
        swipeSettleInProgress = false
        removeCallbacks(finishSwipeSettleRunnable)
        tabStrip.removeAllViews()
        tabItems.forEachIndexed { index, item ->
            val holder = createTabHolder(index, item)
            holders.add(holder)
            tabStrip.addView(holder.root)
        }
        updateAllTabVisualStates(animateIconState = false)
    }

    @Suppress("UNUSED_PARAMETER")
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
        this.selectedColor = selectedColor
        this.unselectedColor = unselectedColor
        this.selectedIconTintColor = MaterialTabIconTransitionPolicy.activeIconColor
        this.unselectedIconTintColor = MaterialTabIconTransitionPolicy.inactiveIconColor
        this.iconTintEnabled = iconTintEnabled
        this.badgeBackgroundColor = badgeBackgroundColor
        this.badgeTextColor = badgeTextColor

        val pillBaseColor = if ((indicatorColor ushr 24) == 0) {
            selectedColor
        } else {
            indicatorColor
        }
        selectedPillColor = MaterialTabIconTransitionPolicy.containerColor(pillBaseColor)
        selectedPill.background = createPillDrawable(selectedPillColor)
        holders.forEach { holder ->
            holder.localPill.background = createPillDrawable(selectedPillColor)
        }
        updateAllTabVisualStates(animateIconState = false)
        unreadCounts.forEach { (index, count) ->
            updateBadge(index, count)
        }
        post { hideSlidingPill() }
    }

    fun setCurrentTab(index: Int, fromUser: Boolean = false) {
        val source = when {
            fromUser -> MaterialTabIconTransitionPolicy.SelectionSource.CLICK
            pendingSelectionSource == MaterialTabIconTransitionPolicy.SelectionSource.SWIPE -> {
                MaterialTabIconTransitionPolicy.SelectionSource.SWIPE
            }
            else -> MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
        }
        pendingSelectionSource = MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
        selectTab(index, source, dispatch = fromUser)
    }

    fun syncIndicator(position: Int, positionOffset: Float) {
        val normalizedOffset = MaterialTabIconTransitionPolicy.swipeFraction(
            TabLayoutIndicatorPolicy.normalizePositionOffset(positionOffset)
        )
        val hasNextTab = position + 1 < holders.size
        val isActiveSwipe = MaterialTabIconTransitionPolicy.isActiveSwipeOffset(
            positionOffset = normalizedOffset,
            hasNextTab = hasNextTab
        )
        if (!isActiveSwipe) {
            if (!swipeSettleInProgress) {
                hideSlidingPill()
                if (localPillsSuppressed) {
                    setLocalPillsSuppressed(false, animate = false)
                }
            }
            return
        }

        if (hasNextTab) {
            swipeSettleInProgress = false
            removeCallbacks(finishSwipeSettleRunnable)
            setLocalPillsSuppressed(true, animate = false)
        }
        updateSelectedPillForScroll(position, normalizedOffset)
        pendingSelectionSource = MaterialTabIconTransitionPolicy.SelectionSource.SWIPE
    }

    fun showUnread(index: Int, count: Int) {
        if (index !in holders.indices) {
            return
        }
        unreadCounts[index] = count
        updateBadge(index, count)
    }

    fun hasUnread(index: Int): Boolean {
        return holders.getOrNull(index)?.badge?.visibility == View.VISIBLE
    }

    fun clearUnread(index: Int) {
        unreadCounts.remove(index)
        holders.getOrNull(index)?.badge?.visibility = View.GONE
    }

    fun getTabViews(): List<View> = holders.map { it.root }

    fun setOnTabSelected(listener: (Int) -> Unit) {
        onTabSelected = listener
    }

    fun getMaterialTabLayout(): TabLayout = compatibilityTabLayout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            if (localPillsSuppressed || swipeSettleInProgress) {
                updateSelectedPillForTab(selectedPosition, PillAnimation.NONE)
            } else {
                hideSlidingPill()
            }
        }
    }

    private fun createTabHolder(index: Int, item: MaterialTabItem): TabHolder {
        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            foreground = null
            background = null
            isClickable = true
            isFocusable = true
            contentDescription = item.text
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            setPaddingRelative(paddingStart, dp(4), paddingEnd, dp(2))
            setOnClickListener {
                selectTab(
                    index = index,
                    source = MaterialTabIconTransitionPolicy.SelectionSource.CLICK,
                    dispatch = true
                )
            }
        }

        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val iconShell = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(32)).apply {
                bottomMargin = dp(1)
            }
        }

        val iconLayer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(dp(26), dp(26), Gravity.CENTER)
        }

        val localPill = View(context).apply {
            alpha = 0f
            scaleX = MaterialTabIconTransitionPolicy.activePillCollapsedScaleX
            scaleY = MaterialTabIconTransitionPolicy.activePillExpandedScale
            background = createPillDrawable(selectedPillColor)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val outlineIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val filledIcon = ImageView(context).apply {
            alpha = 0f
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val badge = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                leftMargin = dp(18)
                topMargin = -dp(4)
            }
        }

        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        iconLayer.addView(outlineIcon)
        iconLayer.addView(filledIcon)
        iconShell.addView(localPill)
        iconShell.addView(iconLayer)
        iconShell.addView(badge)
        stack.addView(iconShell)
        stack.addView(label)
        root.addView(stack)

        bindIcons(item, outlineIcon, filledIcon)
        label.text = item.text

        return TabHolder(
            root = root,
            iconShell = iconShell,
            localPill = localPill,
            iconLayer = iconLayer,
            outlineIcon = outlineIcon,
            filledIcon = filledIcon,
            label = label,
            badge = badge
        )
    }

    private fun selectTab(
        index: Int,
        source: MaterialTabIconTransitionPolicy.SelectionSource,
        dispatch: Boolean
    ) {
        if (index !in holders.indices || index == selectedPosition) {
            return
        }
        val previousPosition = selectedPosition
        selectedPosition = index
        if (source != MaterialTabIconTransitionPolicy.SelectionSource.SWIPE) {
            swipeSettleInProgress = false
            removeCallbacks(finishSwipeSettleRunnable)
            hideSlidingPill()
            localPillsSuppressed = false
        }
        val animateIconState = source != MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
        updateAllTabVisualStates(
            previousPosition = previousPosition,
            newPosition = index,
            animateIconState = animateIconState
        )
        when (source) {
            MaterialTabIconTransitionPolicy.SelectionSource.CLICK,
            MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC -> {
                hideSlidingPill()
            }
            MaterialTabIconTransitionPolicy.SelectionSource.SWIPE -> {
                swipeSettleInProgress = true
                removeCallbacks(finishSwipeSettleRunnable)
                updateSelectedPillForTab(index, PillAnimation.SLIDE)
                postDelayed(
                    finishSwipeSettleRunnable,
                    MaterialTabIconTransitionPolicy.swipePillSettleDurationMs
                )
            }
        }
        animateSelectedIconChange(previousPosition, index, source)
        if (dispatch) {
            onTabSelected?.invoke(index)
        }
    }

    private fun updateAllTabVisualStates(
        previousPosition: Int = -1,
        newPosition: Int = selectedPosition,
        animateIconState: Boolean = false
    ) {
        holders.forEachIndexed { index, holder ->
            val stateChangedTab = index == previousPosition || index == newPosition
            applyTabVisualState(holder, index, animateIconState && stateChangedTab)
        }
        invalidate()
    }

    private fun applyTabVisualState(holder: TabHolder, position: Int, animate: Boolean) {
        val isSelected = position == selectedPosition
        holder.root.isSelected = isSelected
        holder.label.setTextColor(if (isSelected) selectedColor else unselectedColor)
        holder.label.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        holder.outlineIcon.imageTintList = iconTintListFor(selected = false)
        holder.filledIcon.imageTintList = iconTintListFor(selected = true)
        applyLocalPillState(holder, isSelected, animate)

        holder.iconLayer.animate().cancel()
        cancelIconMotion(holder.iconLayer)
        holder.iconLayer.rotation = 0f
        holder.iconLayer.rotationY = 0f
        holder.iconLayer.translationY = dp(MaterialTabIconTransitionPolicy.iconTranslationYDp(position))

        if (animate) {
            animateIconFillState(holder, isSelected)
            holder.iconLayer.animate()
                .scaleX(if (isSelected) MaterialTabIconTransitionPolicy.selectedRestScale else MaterialTabIconTransitionPolicy.restingScale)
                .scaleY(if (isSelected) MaterialTabIconTransitionPolicy.selectedRestScale else MaterialTabIconTransitionPolicy.restingScale)
                .setDuration(MaterialTabIconTransitionPolicy.iconFillDurationMs)
                .setInterpolator(MaterialAnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR)
                .start()
        } else {
            cancelFillAnimation(holder.filledIcon)
            holder.outlineIcon.alpha = if (isSelected) 0f else 1f
            holder.filledIcon.alpha = if (isSelected) 1f else 0f
            val scale = if (isSelected) {
                MaterialTabIconTransitionPolicy.selectedRestScale
            } else {
                MaterialTabIconTransitionPolicy.restingScale
            }
            holder.iconLayer.scaleX = scale
            holder.iconLayer.scaleY = scale
        }
    }

    private fun animateIconFillState(holder: TabHolder, isSelected: Boolean) {
        cancelFillAnimation(holder.filledIcon)
        holder.outlineIcon.animate().cancel()
        holder.filledIcon.animate().cancel()
        if (isSelected) {
            holder.outlineIcon.alpha = 1f
            holder.filledIcon.alpha = 0f
            holder.filledIcon.scaleX = 1f
            holder.filledIcon.scaleY = 1f
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = MaterialTabIconTransitionPolicy.iconFillDurationMs
                interpolator = MaterialAnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR
                addUpdateListener { animation ->
                    val progress = animation.animatedValue as Float
                    holder.filledIcon.alpha = progress
                    holder.outlineIcon.alpha = 1f - delayedProgress(progress, 0.58f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        fillAnimators.remove(holder.filledIcon)
                        holder.filledIcon.alpha = 1f
                        holder.outlineIcon.alpha = 0f
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        fillAnimators.remove(holder.filledIcon)
                    }
                })
            }
            fillAnimators[holder.filledIcon] = animator
            animator.start()
        } else {
            holder.outlineIcon.alpha = 1f
            holder.outlineIcon.scaleX = 1f
            holder.outlineIcon.scaleY = 1f
            holder.filledIcon.alpha = 1f
            val animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = MaterialTabIconTransitionPolicy.iconFillDurationMs
                interpolator = MaterialAnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR
                addUpdateListener { animation ->
                    val progress = animation.animatedValue as Float
                    holder.filledIcon.alpha = progress
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        fillAnimators.remove(holder.filledIcon)
                        holder.filledIcon.alpha = 0f
                        holder.filledIcon.scaleX = 1f
                        holder.filledIcon.scaleY = 1f
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        fillAnimators.remove(holder.filledIcon)
                    }
                })
            }
            fillAnimators[holder.filledIcon] = animator
            animator.start()
        }
    }

    private fun cancelFillAnimation(filledIcon: View) {
        fillAnimators.remove(filledIcon)?.cancel()
    }

    private fun delayedProgress(progress: Float, delayFraction: Float): Float {
        val clampedDelay = delayFraction.coerceIn(0f, 0.99f)
        return ((progress.coerceIn(0f, 1f) - clampedDelay) / (1f - clampedDelay)).coerceIn(0f, 1f)
    }

    private fun animateSelectedIconChange(
        previousPosition: Int,
        newPosition: Int,
        source: MaterialTabIconTransitionPolicy.SelectionSource
    ) {
        val plan = MaterialTabIconTransitionPolicy.selectionPlan(
            previousPosition = previousPosition,
            selectedPosition = newPosition,
            tabCount = holders.size,
            source = source
        ) ?: return
        val motion = plan.iconMotion ?: return
        val holder = holders.getOrNull(plan.selectedPosition) ?: return
        animateIconMotion(holder.iconLayer, plan.selectedPosition, motion)
    }

    private fun animateIconMotion(
        iconLayer: View,
        position: Int,
        motion: MaterialTabIconTransitionPolicy.IconMotion
    ) {
        cancelIconMotion(iconLayer)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MaterialTabIconTransitionPolicy.durationForMotion(motion)
            interpolator = MaterialAnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR
            addUpdateListener { animation ->
                applyIconMotionFrame(
                    iconLayer = iconLayer,
                    position = position,
                    motion = motion,
                    progress = animation.animatedValue as Float
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    iconAnimators.remove(iconLayer)
                    applyTabVisualState(holders[position], position, animate = false)
                }

                override fun onAnimationCancel(animation: Animator) {
                    iconAnimators.remove(iconLayer)
                }
            })
        }
        iconAnimators[iconLayer] = animator
        animator.start()
    }

    private fun cancelIconMotion(iconLayer: View) {
        iconAnimators.remove(iconLayer)?.cancel()
    }

    private fun updateSelectedPillForTab(position: Int, animation: PillAnimation) {
        if (position !in holders.indices) {
            selectedPill.visibility = View.INVISIBLE
            return
        }
        post {
            pillFrameForPosition(position)?.let { frame ->
                moveSelectedPill(frame.left, frame.top, animation)
            }
        }
    }

    private fun hideSlidingPill() {
        selectedPill.animate().cancel()
        selectedPill.alpha = 0f
        selectedPill.visibility = View.INVISIBLE
    }

    private fun setLocalPillsSuppressed(suppressed: Boolean, animate: Boolean) {
        if (localPillsSuppressed == suppressed && !animate) {
            return
        }
        localPillsSuppressed = suppressed
        holders.forEachIndexed { index, holder ->
            applyLocalPillState(holder, index == selectedPosition, animate)
        }
    }

    private fun applyLocalPillState(holder: TabHolder, isSelected: Boolean, animate: Boolean) {
        val shouldShow = isSelected && !localPillsSuppressed && (selectedPillColor ushr 24) != 0
        holder.localPill.animate().cancel()
        if (animate) {
            if (shouldShow) {
                holder.localPill.visibility = View.VISIBLE
                holder.localPill.alpha = 0f
                holder.localPill.scaleX = MaterialTabIconTransitionPolicy.activePillCollapsedScaleX
                holder.localPill.scaleY = MaterialTabIconTransitionPolicy.activePillExpandedScale
            }
            holder.localPill.animate()
                .alpha(if (shouldShow) 1f else 0f)
                .scaleX(
                    if (shouldShow) {
                        MaterialTabIconTransitionPolicy.activePillExpandedScale
                    } else {
                        MaterialTabIconTransitionPolicy.activePillCollapsedScaleX
                    }
                )
                .scaleY(MaterialTabIconTransitionPolicy.activePillExpandedScale)
                .setDuration(MaterialTabIconTransitionPolicy.clickPillDurationMs)
                .setInterpolator(MaterialAnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR)
                .withEndAction {
                    holder.localPill.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
                }
                .start()
        } else {
            holder.localPill.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
            holder.localPill.alpha = if (shouldShow) 1f else 0f
            holder.localPill.scaleX = if (shouldShow) {
                MaterialTabIconTransitionPolicy.activePillExpandedScale
            } else {
                MaterialTabIconTransitionPolicy.activePillCollapsedScaleX
            }
            holder.localPill.scaleY = MaterialTabIconTransitionPolicy.activePillExpandedScale
        }
    }

    private fun updateSelectedPillForScroll(position: Int, positionOffset: Float) {
        if (position !in holders.indices) {
            return
        }
        post {
            val startFrame = pillFrameForPosition(position) ?: return@post
            val endPosition = (position + 1).coerceAtMost(holders.lastIndex)
            val endFrame = pillFrameForPosition(endPosition) ?: startFrame
            moveSelectedPill(
                left = lerp(startFrame.left, endFrame.left, positionOffset),
                top = lerp(startFrame.top, endFrame.top, positionOffset),
                animation = PillAnimation.NONE
            )
        }
    }

    private fun moveSelectedPill(left: Float, top: Float, animation: PillAnimation) {
        selectedPill.visibility = if ((selectedPillColor ushr 24) == 0) View.INVISIBLE else View.VISIBLE
        selectedPill.animate().cancel()
        when (animation) {
            PillAnimation.NONE -> {
                selectedPill.x = left
                selectedPill.y = top
                selectedPill.alpha = 1f
                selectedPill.scaleX = 1f
                selectedPill.scaleY = 1f
            }
            PillAnimation.CLICK -> {
                selectedPill.x = left
                selectedPill.y = top
                selectedPill.alpha = 0f
                selectedPill.scaleX = 0.5f
                selectedPill.scaleY = 1f
                selectedPill.postOnAnimation {
                    selectedPill.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(MaterialTabIconTransitionPolicy.clickPillDurationMs)
                        .setInterpolator(MaterialAnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR)
                        .start()
                }
            }
            PillAnimation.SLIDE -> {
                selectedPill.alpha = 1f
                selectedPill.scaleX = 1f
                selectedPill.scaleY = 1f
                selectedPill.animate()
                    .x(left)
                    .y(top)
                    .setDuration(MaterialTabIconTransitionPolicy.swipePillSettleDurationMs)
                    .setInterpolator(MaterialAnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR)
                    .start()
            }
        }
    }

    private fun pillFrameForPosition(position: Int): PillFrame? {
        val holder = holders.getOrNull(position) ?: return null
        val centerX = leftInSelf(holder.iconShell) + holder.iconShell.width / 2f
        val centerY = topInSelf(holder.iconShell) + holder.iconShell.height / 2f
        return PillFrame(
            left = centerX - selectedPill.layoutParams.width / 2f,
            top = centerY - selectedPill.layoutParams.height / 2f
        )
    }

    private fun updateBadge(index: Int, count: Int) {
        val badge = holders.getOrNull(index)?.badge ?: return
        when (MaterialTabBadgePolicy.resolveMode(count)) {
            MaterialTabBadgePolicy.Mode.NUMBER -> {
                badge.text = count.toString()
                badge.visibility = View.VISIBLE
                badge.minWidth = dp(16)
                badge.minHeight = dp(16)
                badge.setPadding(dp(5), 0, dp(5), 0)
                badge.background = createBadgeDrawable()
                badge.setTextColor(badgeTextColor ?: Color.WHITE)
            }
            MaterialTabBadgePolicy.Mode.DOT -> {
                badge.text = ""
                badge.visibility = View.VISIBLE
                badge.minWidth = dp(8)
                badge.minHeight = dp(8)
                badge.setPadding(0, 0, 0, 0)
                badge.background = createBadgeDrawable()
            }
            MaterialTabBadgePolicy.Mode.HIDDEN -> {
                badge.visibility = View.GONE
            }
        }
    }

    private fun bindIcons(
        item: MaterialTabItem,
        outlineIcon: ImageView,
        filledIcon: ImageView
    ) {
        if (item.iconBitmap != null) {
            val drawable = BitmapDrawable(resources, item.iconBitmap)
            outlineIcon.setImageDrawable(drawable)
            filledIcon.setImageDrawable(drawable.constantState?.newDrawable()?.mutate() ?: drawable)
            return
        }
        val outlineRes = item.outlineIconRes ?: item.iconRes
        val filledRes = item.filledIconRes ?: item.iconRes
        outlineIcon.setImageDrawable(iconDrawable(outlineRes, unselectedIconTintColor))
        filledIcon.setImageDrawable(iconDrawable(filledRes, selectedIconTintColor))
    }

    private fun iconDrawable(@DrawableRes iconRes: Int, tintColor: Int): Drawable {
        val drawable = AppCompatResources.getDrawable(context, iconRes)?.mutate()
            ?: error("Missing tab icon drawable: $iconRes")
        if (iconTintEnabled) {
            drawable.setTint(tintColor)
        }
        return drawable
    }

    private fun iconTintListFor(selected: Boolean): ColorStateList? {
        if (!iconTintEnabled) {
            return null
        }
        return ColorStateList.valueOf(
            if (selected) selectedIconTintColor else unselectedIconTintColor
        )
    }

    private fun applyIconMotionFrame(
        iconLayer: View,
        position: Int,
        motion: MaterialTabIconTransitionPolicy.IconMotion,
        progress: Float
    ) {
        val baseTranslationY = dp(MaterialTabIconTransitionPolicy.iconTranslationYDp(position))
        when (motion) {
            MaterialTabIconTransitionPolicy.IconMotion.CHAT_JELLY -> {
                iconLayer.scaleX = keyframe(progress, 0f to 1.15f, 0.3f to 1.08f, 0.6f to 1.14f, 0.8f to 1.09f, 1f to 1.15f)
                iconLayer.scaleY = keyframe(progress, 0f to 1.15f, 0.3f to 0.98f, 0.6f to 1.27f, 0.8f to 1.09f, 1f to 1.15f)
                iconLayer.rotation = keyframe(progress, 0f to 0f, 0.3f to -5f, 0.6f to 5f, 0.8f to -2f, 1f to 0f)
            }
            MaterialTabIconTransitionPolicy.IconMotion.CONTACT_FLIP -> {
                val scale = keyframe(progress, 0f to 1.15f, 0.5f to 1.25f, 1f to 1.15f)
                iconLayer.cameraDistance = resources.displayMetrics.density * 8000f
                iconLayer.scaleX = scale
                iconLayer.scaleY = scale
                iconLayer.rotationY = keyframe(progress, 0f to 0f, 0.5f to 180f, 1f to 360f)
            }
            MaterialTabIconTransitionPolicy.IconMotion.COMPASS_SPIN -> {
                iconLayer.scaleX = MaterialTabIconTransitionPolicy.selectedRestScale
                iconLayer.scaleY = MaterialTabIconTransitionPolicy.selectedRestScale
                iconLayer.rotation = keyframe(progress, 0f to 0f, 0.6f to 390f, 0.8f to 350f, 1f to 360f)
            }
            MaterialTabIconTransitionPolicy.IconMotion.PERSON_BOUNCE -> {
                iconLayer.translationY = baseTranslationY + keyframe(progress, 0f to 0f, 0.4f to -dp(4f), 0.7f to dp(1f), 1f to 0f)
                iconLayer.scaleX = keyframe(progress, 0f to 1.15f, 0.4f to 1.09f, 0.7f to 1.21f, 1f to 1.15f)
                iconLayer.scaleY = keyframe(progress, 0f to 1.15f, 0.4f to 1.32f, 0.7f to 1.09f, 1f to 1.15f)
            }
        }
    }

    private fun leftInSelf(view: View): Float {
        var left = view.left + view.translationX
        var parent = view.parent
        while (parent is View && parent !== this) {
            left += parent.left + parent.translationX
            parent = (parent as View).parent
        }
        return left
    }

    private fun topInSelf(view: View): Float {
        var top = view.top + view.translationY
        var parent = view.parent
        while (parent is View && parent !== this) {
            top += parent.top + parent.translationY
            parent = (parent as View).parent
        }
        return top
    }

    private fun keyframe(progress: Float, vararg points: Pair<Float, Float>): Float {
        val clampedProgress = progress.coerceIn(0f, 1f)
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            if (clampedProgress <= end.first) {
                val localProgress = if (end.first == start.first) {
                    1f
                } else {
                    (clampedProgress - start.first) / (end.first - start.first)
                }
                return lerp(start.second, end.second, localProgress.coerceIn(0f, 1f))
            }
        }
        return points.last().second
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }

    private fun createPillDrawable(color: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f)
            setColor(color)
        }
    }

    private fun createBadgeDrawable(): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f)
            setColor(badgeBackgroundColor ?: Color.rgb(179, 38, 30))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }
}
