package com.blanke.mdwechat.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Base64
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import com.blanke.mdwechat.Objects
import com.blanke.mdwechat.util.MaterialTabBadgePolicy
import com.blanke.mdwechat.util.MaterialTabIconTransitionPolicy
import com.blanke.mdwechat.util.TabLayoutIndicatorPolicy
import com.google.android.material.tabs.TabLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class MaterialTabItem(
    @DrawableRes val iconRes: Int,
    val text: CharSequence,
    val iconBitmap: Bitmap? = null,
    @DrawableRes val outlineIconRes: Int? = null,
    @DrawableRes val filledIconRes: Int? = null
)

@SuppressLint("SetJavaScriptEnabled")
class MdMaterialTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val compatibilityTabLayout = TabLayout(context)
    private val webView = WebView(context)
    private val touchLayer = object : View(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            return handleTouchLayerTouch(event)
        }
    }
    private val items = mutableListOf<MaterialTabItem>()
    private val unreadCounts = mutableMapOf<Int, Int>()
    private var onTabSelected: ((Int) -> Unit)? = null
    private var selectedPosition = -1
    private var selectedColor = MaterialTabIconTransitionPolicy.activeIconColor
    private var unselectedColor = MaterialTabIconTransitionPolicy.inactiveIconColor
    private var activeContainerColor = PREVIEW_ACTIVE_CONTAINER
    private var activeContainerEnabled = true
    private var indicatorColor = Color.TRANSPARENT
    private var indicatorHeightCssPx = 0f
    private var indicatorGravity = Gravity.BOTTOM
    private var badgeBackgroundColor = PREVIEW_BADGE_COLOR
    private var badgeTextColor = Color.WHITE
    private var pageReady = false
    private var pendingSelectionSource = MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartRawX = 0f
    private var touchDragging = false
    private var touchForwardingSwipe = false
    private val touchSlop = max(
        ViewConfiguration.get(context).scaledTouchSlop,
        dp(18f).toInt()
    )
    private val materialSymbolsFontDataUri by lazy(LazyThreadSafetyMode.NONE) {
        loadMaterialSymbolsFontDataUri()
    }

    init {
        clipChildren = false
        clipToPadding = false
        setupWebView()
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        setupTouchLayer()
        addView(
            touchLayer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun setTabItems(tabItems: List<MaterialTabItem>) {
        items.clear()
        items.addAll(tabItems)
        unreadCounts.clear()
        if (selectedPosition !in items.indices) {
            selectedPosition = -1
        }
        renderHtml()
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
        activeContainerEnabled: Boolean = true,
        iconTintEnabled: Boolean = true
    ) {
        this.selectedColor = MaterialTabIconTransitionPolicy.activeIconColor
        this.unselectedColor = MaterialTabIconTransitionPolicy.inactiveIconColor
        this.activeContainerColor = PREVIEW_ACTIVE_CONTAINER
        this.activeContainerEnabled = activeContainerEnabled
        this.indicatorColor = indicatorColor
        this.indicatorHeightCssPx = (indicatorHeightPx.toFloat() / resources.displayMetrics.density)
            .coerceAtLeast(1f)
        this.badgeBackgroundColor = badgeBackgroundColor
        this.badgeTextColor = badgeTextColor
        renderHtml()
    }

    fun setIndicatorGravity(gravity: Int) {
        indicatorGravity = gravity
        renderHtml()
    }

    fun setCurrentTab(index: Int, fromUser: Boolean = false) {
        if (index !in items.indices) {
            return
        }
        val source = when {
            fromUser -> MaterialTabIconTransitionPolicy.SelectionSource.CLICK
            pendingSelectionSource == MaterialTabIconTransitionPolicy.SelectionSource.SWIPE -> {
                MaterialTabIconTransitionPolicy.SelectionSource.SWIPE
            }
            else -> MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
        }
        pendingSelectionSource = MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
        val changed = selectedPosition != index
        if (!changed) {
            return
        }
        selectedPosition = index
        runJs(
            "window.MDWechatTabs && window.MDWechatTabs.setNativeActive(" +
                "$index, '${source.webName}');"
        )
        if (fromUser && changed) {
            onTabSelected?.invoke(index)
        }
    }

    fun syncIndicator(position: Int, positionOffset: Float) {
        val normalizedOffset = MaterialTabIconTransitionPolicy.swipeFraction(
            TabLayoutIndicatorPolicy.normalizePositionOffset(positionOffset)
        )
        if (position !in items.indices) {
            return
        }
        val hasNextTab = position + 1 < items.size
        if (MaterialTabIconTransitionPolicy.isActiveSwipeOffset(normalizedOffset, hasNextTab)) {
            pendingSelectionSource = MaterialTabIconTransitionPolicy.SelectionSource.SWIPE
        }
        runJs(
            "window.MDWechatTabs && window.MDWechatTabs.syncPagerSwipe(" +
                "$position, ${normalizedOffset.toJsNumber()});"
        )
    }

    fun showUnread(index: Int, count: Int) {
        if (index !in items.indices) {
            return
        }
        unreadCounts[index] = count
        runJs("window.MDWechatTabs && window.MDWechatTabs.setBadge($index, $count);")
    }

    fun hasUnread(index: Int): Boolean {
        return MaterialTabBadgePolicy.resolveMode(unreadCounts[index] ?: 0) != MaterialTabBadgePolicy.Mode.HIDDEN
    }

    fun clearUnread(index: Int) {
        unreadCounts.remove(index)
        runJs("window.MDWechatTabs && window.MDWechatTabs.clearBadge($index);")
    }

    fun getTabViews(): List<View> = listOf(touchLayer)

    fun setOnTabSelected(listener: (Int) -> Unit) {
        onTabSelected = listener
    }

    fun getMaterialTabLayout(): TabLayout = compatibilityTabLayout

    override fun onDetachedFromWindow() {
        pageReady = false
        webView.stopLoading()
        super.onDetachedFromWindow()
    }

    private fun setupWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = true
            allowContentAccess = false
            loadsImagesAutomatically = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        webView.addJavascriptInterface(TabBridge(), "MDWechatTabBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pushStateToWeb()
            }
        }
    }

    private fun setupTouchLayer() {
        touchLayer.setBackgroundColor(Color.TRANSPARENT)
        touchLayer.isClickable = true
        touchLayer.isLongClickable = false
        touchLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        touchLayer.setOnLongClickListener { true }
    }

    private fun renderHtml() {
        pageReady = false
        webView.loadDataWithBaseURL(
            null,
            buildHtml(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun pushStateToWeb() {
        if (selectedPosition in items.indices) {
            runJs(
                "window.MDWechatTabs && window.MDWechatTabs.setNativeActive(" +
                    "$selectedPosition, 'programmatic');"
            )
        }
        unreadCounts.forEach { (index, count) ->
            runJs("window.MDWechatTabs && window.MDWechatTabs.setBadge($index, $count);")
        }
    }

    private fun runJs(script: String) {
        webView.post {
            webView.loadUrl("javascript:(function(){${script}})();")
        }
    }

    private fun buildHtml(): String {
        val activeBackgroundColor = if (activeContainerEnabled) {
            activeContainerColor
        } else {
            Color.TRANSPARENT
        }
        val indicatorEnabled = (indicatorColor ushr 24) != 0 && indicatorHeightCssPx > 0f
        val indicatorDisplay = if (indicatorEnabled) "block" else "none"
        val indicatorHeight = indicatorHeightCssPx.toJsNumber()
        val indicatorPosition = when (indicatorGravity) {
            Gravity.TOP -> "top: 0;"
            Gravity.CENTER_VERTICAL -> "top: calc(50% - ${indicatorHeight}px / 2);"
            else -> "bottom: 0;"
        }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
              <style>
                @font-face {
                  font-family: "Material Symbols Rounded";
                  font-style: normal;
                  font-weight: 100 700;
                  src: url("$materialSymbolsFontDataUri") format("woff2-variations");
                }

                :root {
                  --page-bg: ${PREVIEW_NAV_BACKGROUND.toCssColor()};
                  --active-bg: ${activeBackgroundColor.toCssColor()};
                  --active-ink: ${selectedColor.toCssColor()};
                  --inactive-ink: ${unselectedColor.toCssColor()};
                  --inactive-hover: #1f1f1f;
                  --badge-bg: ${badgeBackgroundColor.toCssColor()};
                  --badge-ink: ${badgeTextColor.toCssColor()};
                  --badge-ring: #f3f4f9;
                  --indicator-bg: ${indicatorColor.toCssColor()};
                  --indicator-h: ${indicatorHeight}px;
                }

                * {
                  box-sizing: border-box;
                  -webkit-tap-highlight-color: transparent;
                }

                html,
                body {
                  width: 100%;
                  height: 100%;
                  margin: 0;
                  overflow: hidden;
                  background: transparent;
                  user-select: none;
                  touch-action: pan-y;
                  font-family: sans-serif;
                }

                .bottom-nav {
                  position: absolute;
                  inset: 0;
                  height: 100%;
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  padding: 2px 8px 3px;
                  background: var(--page-bg);
                  isolation: isolate;
                }

                .bottom-nav.no-animation *,
                .bottom-nav.no-animation *::before,
                .bottom-nav.no-animation *::after {
                  transition: none !important;
                  animation: none !important;
                }

                .sliding-pill {
                  position: absolute;
                  width: 58px;
                  height: 29px;
                  border-radius: 999px;
                  background: var(--active-bg);
                  opacity: 0;
                  pointer-events: none;
                  z-index: 0;
                  transition:
                    left 0.45s cubic-bezier(0.2, 0, 0, 1),
                    top 0.45s cubic-bezier(0.2, 0, 0, 1),
                    transform 0.45s cubic-bezier(0.2, 0, 0, 1),
                    opacity 0.18s ease;
                }

                .sliding-pill.no-transition {
                  transition: none;
                }

                .bottom-nav.swiping .sliding-pill,
                .bottom-nav.swipe-settle .sliding-pill {
                  opacity: 1;
                }

                .bottom-nav.swiping .sliding-pill {
                  transition: none;
                }

                .indicator-bar {
                  position: absolute;
                  ${indicatorPosition}
                  left: 0;
                  z-index: 2;
                  display: ${indicatorDisplay};
                  width: 32px;
                  height: var(--indicator-h);
                  border-radius: 999px;
                  background: var(--indicator-bg);
                  pointer-events: none;
                  transform: translateX(0px);
                  transition:
                    left 0.45s cubic-bezier(0.2, 0, 0, 1),
                    transform 0.45s cubic-bezier(0.2, 0, 0, 1),
                    opacity 0.18s ease;
                }

                .indicator-bar.no-transition,
                .bottom-nav.swiping .indicator-bar {
                  transition: none;
                }

                .tab {
                  position: relative;
                  z-index: 1;
                  flex: 1 1 0;
                  height: 100%;
                  border: 0;
                  padding: 0;
                  display: flex;
                  flex-direction: column;
                  align-items: center;
                  justify-content: center;
                  color: var(--inactive-ink);
                  background: transparent;
                  cursor: pointer;
                  outline: none;
                }

                .icon-shell {
                  position: relative;
                  width: 58px;
                  height: 29px;
                  margin-bottom: 3px;
                  display: grid;
                  place-items: center;
                }

                .pill {
                  position: absolute;
                  inset: 0;
                  z-index: 0;
                  border-radius: 999px;
                  background: transparent;
                  opacity: 0;
                  transform: scaleX(0.5);
                  transition:
                    opacity 0.4s cubic-bezier(0.2, 0, 0, 1),
                    transform 0.4s cubic-bezier(0.2, 0, 0, 1),
                    background-color 0.4s cubic-bezier(0.2, 0, 0, 1);
                }

                .tab.active .pill {
                  background: var(--active-bg);
                  opacity: 1;
                  transform: scaleX(1);
                }

                .bottom-nav.swiping .tab.active .pill,
                .bottom-nav.swipe-settle .tab.active .pill {
                  opacity: 0;
                  transform: scaleX(0.5);
                }

                .icon-wrap {
                  position: relative;
                  z-index: 1;
                  display: grid;
                  place-items: center;
                }

                .tab[data-id="0"] .icon-wrap {
                  transform: translateY(2px);
                }

                .material-symbols-rounded {
                  font-family: "Material Symbols Rounded";
                  font-weight: normal;
                  font-style: normal;
                  font-size: 24px;
                  line-height: 1;
                  letter-spacing: normal;
                  text-transform: none;
                  display: inline-block;
                  width: 24px;
                  height: 24px;
                  overflow: hidden;
                  text-align: center;
                  white-space: nowrap;
                  word-wrap: normal;
                  direction: ltr;
                  font-feature-settings: "liga" 1;
                  -webkit-font-feature-settings: "liga" 1;
                  -webkit-font-smoothing: antialiased;
                  transform-origin: center;
                  font-variation-settings: "FILL" 0, "wght" 400, "GRAD" 0, "opsz" 24;
                  transform: scale(1);
                  transition:
                    font-variation-settings 0.4s cubic-bezier(0.2, 0, 0, 1),
                    transform 0.4s cubic-bezier(0.2, 0, 0, 1),
                    color 0.2s ease;
                }

                .tab.active {
                  color: var(--active-ink);
                }

                .tab.active .material-symbols-rounded {
                  font-variation-settings: "FILL" 1, "wght" 400, "GRAD" 0, "opsz" 24;
                  transform: scale(1.08);
                }

                .label {
                  margin-top: 2px;
                  font-size: 12px;
                  letter-spacing: 0.02em;
                  font-weight: 500;
                  color: currentColor;
                  transition: color 0.3s ease;
                }

                .tab.active .label {
                  font-weight: 700;
                }

                .badge {
                  position: absolute;
                  top: -4px;
                  right: -7px;
                  z-index: 2;
                  min-width: 16px;
                  padding: 1px 5px;
                  border: 2px solid var(--badge-ring);
                  border-radius: 999px;
                  background: var(--badge-bg);
                  color: var(--badge-ink);
                  font-size: 10px;
                  font-weight: 700;
                  line-height: 14px;
                  text-align: center;
                  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
                  display: none;
                }

                .badge.dot {
                  width: 8px;
                  min-width: 8px;
                  height: 8px;
                  padding: 0;
                  border-width: 0;
                  line-height: 8px;
                }

                @keyframes chat-jelly {
                  0% { transform: scale(1.08) rotate(0deg); }
                  30% { transform: scale(1.02, 0.94) rotate(-5deg); }
                  60% { transform: scale(1.08, 1.18) rotate(5deg); }
                  80% { transform: scale(1.03) rotate(-2deg); }
                  100% { transform: scale(1.08) rotate(0deg); }
                }

                @keyframes contact-card-flip {
                  0% { transform: perspective(400px) rotateY(0deg) scale(1.08); }
                  50% { transform: perspective(400px) rotateY(180deg) scale(1.16); }
                  100% { transform: perspective(400px) rotateY(360deg) scale(1.08); }
                }

                @keyframes compass-needle-spin {
                  0% { transform: rotate(0deg) scale(1.08); }
                  60% { transform: rotate(390deg) scale(1.08); }
                  80% { transform: rotate(350deg) scale(1.08); }
                  100% { transform: rotate(360deg) scale(1.08); }
                }

                @keyframes person-bounce {
                  0% { transform: translateY(0) scale(1.08); }
                  40% { transform: translateY(-3px) scale(1.02, 1.21); }
                  70% { transform: translateY(1px) scale(1.14, 1.03); }
                  100% { transform: translateY(0) scale(1.08); }
                }

                .tab.play-motion .chat-motion {
                  animation: chat-jelly 0.6s cubic-bezier(0.2, 0, 0, 1) both;
                }

                .tab.play-motion .contact-motion {
                  animation: contact-card-flip 0.7s cubic-bezier(0.2, 0, 0, 1) both;
                  backface-visibility: visible;
                }

                .tab.play-motion .compass-motion {
                  animation: compass-needle-spin 0.7s cubic-bezier(0.34, 1.56, 0.64, 1) both;
                }

                .tab.play-motion .person-motion {
                  animation: person-bounce 0.6s cubic-bezier(0.2, 0, 0, 1) both;
                }
              </style>
            </head>
            <body>
              <nav class="bottom-nav no-animation" aria-label="底部导航">
                <span class="sliding-pill no-transition" aria-hidden="true"></span>
                <span class="indicator-bar no-transition" aria-hidden="true"></span>
                ${buildTabsMarkup()}
              </nav>

              <script>
                const bottomNav = document.querySelector(".bottom-nav");
                const slidingPill = document.querySelector(".sliding-pill");
                const indicatorBar = document.querySelector(".indicator-bar");
                const tabs = Array.from(document.querySelectorAll(".tab"));
                let activeIndex = ${if (selectedPosition in items.indices) selectedPosition else -1};
                let pointerStartX = 0;
                let pointerStartY = 0;
                let pointerDeltaX = 0;
                let pointerPreviewOffset = 0;
                let pointerActiveIndex = activeIndex;
                let isPointerDown = false;
                let isDragging = false;
                let ignoreNextClick = false;
                let settleTimer = 0;
                let pagerSwipeFromIndex = activeIndex;
                let pagerSwipePreviewOffset = 0;
                let pagerSwipeActive = false;
                const dragStartThresholdPx = 18;

                const getIconShellPosition = (tab) => {
                  const iconShell = tab && tab.querySelector(".icon-shell");
                  if (!bottomNav || !iconShell) return null;
                  const navRect = bottomNav.getBoundingClientRect();
                  const shellRect = iconShell.getBoundingClientRect();
                  return {
                    left: shellRect.left - navRect.left,
                    top: shellRect.top - navRect.top,
                  };
                };

                const getIndicatorPosition = (tab) => {
                  if (!bottomNav || !tab || !indicatorBar) return null;
                  const navRect = bottomNav.getBoundingClientRect();
                  const tabRect = tab.getBoundingClientRect();
                  const barRect = indicatorBar.getBoundingClientRect();
                  return {
                    left: tabRect.left - navRect.left + tabRect.width / 2 - barRect.width / 2,
                  };
                };

                const moveIndicatorToTab = (tab, animate = true, offsetX = 0) => {
                  const position = getIndicatorPosition(tab);
                  if (!indicatorBar || !position) return;
                  if (!animate) indicatorBar.classList.add("no-transition");
                  indicatorBar.style.left = `${'$'}{position.left}px`;
                  indicatorBar.style.transform = `translateX(${'$'}{offsetX}px)`;
                  if (!animate) {
                    window.requestAnimationFrame(() => {
                      indicatorBar.classList.remove("no-transition");
                    });
                  }
                };

                const moveSlidingPillToTab = (tab, animate = true, offsetX = 0) => {
                  const position = getIconShellPosition(tab);
                  if (!slidingPill || !position) return;
                  if (!animate) slidingPill.classList.add("no-transition");
                  slidingPill.style.left = `${'$'}{position.left}px`;
                  slidingPill.style.top = `${'$'}{position.top}px`;
                  slidingPill.style.transform = `translateX(${'$'}{offsetX}px)`;
                  if (!animate) {
                    window.requestAnimationFrame(() => {
                      slidingPill.classList.remove("no-transition");
                    });
                  }
                };

                const settleSlidingPill = (fromIndex, targetIndex, fromOffsetX) => {
                  fromIndex = Math.max(0, Math.min(tabs.length - 1, Number(fromIndex) || 0));
                  targetIndex = Math.max(0, Math.min(tabs.length - 1, Number(targetIndex) || 0));
                  const fromPosition = getIconShellPosition(tabs[fromIndex]);
                  const targetPosition = getIconShellPosition(tabs[targetIndex]);
                  if (!slidingPill || !fromPosition || !targetPosition) return;

                  if (settleTimer) {
                    window.clearTimeout(settleTimer);
                    settleTimer = 0;
                  }
                  bottomNav.classList.remove("swiping");
                  bottomNav.classList.add("swipe-settle");

                  const visualLeft = fromPosition.left + (Number(fromOffsetX) || 0);
                  slidingPill.classList.add("no-transition");
                  slidingPill.style.left = `${'$'}{targetPosition.left}px`;
                  slidingPill.style.top = `${'$'}{targetPosition.top}px`;
                  slidingPill.style.transform = `translateX(${'$'}{visualLeft - targetPosition.left}px)`;
                  moveIndicatorToTab(tabs[targetIndex], false, visualLeft - targetPosition.left);

                  window.requestAnimationFrame(() => {
                    slidingPill.classList.remove("no-transition");
                    slidingPill.style.transform = "translateX(0px)";
                    if (indicatorBar) indicatorBar.style.transform = "translateX(0px)";
                  });

                  settleTimer = window.setTimeout(() => {
                    settleTimer = 0;
                  }, 460);
                };

                const playSelectedMotion = (targetIndex) => {
                  const tab = tabs[targetIndex];
                  if (!tab) return;
                  window.requestAnimationFrame(() => {
                    tab.classList.add("play-motion");
                  });
                };

                const setActiveTabByIndex = (targetIndex, animateIcon = false) => {
                  targetIndex = Math.max(0, Math.min(tabs.length - 1, Number(targetIndex) || 0));
                  const tab = tabs[targetIndex];
                  if (!tab || activeIndex === targetIndex) return;
                  tabs.forEach((item) => item.classList.remove("active", "play-motion"));
                  bottomNav.classList.remove("swiping", "swipe-settle");
                  slidingPill.style.transform = "translateX(0px)";
                  if (indicatorBar) indicatorBar.style.transform = "translateX(0px)";
                  tab.classList.add("active");
                  activeIndex = targetIndex;
                  moveIndicatorToTab(tab, true);
                  pagerSwipeActive = false;
                  if (animateIcon) playSelectedMotion(targetIndex);
                };

                const setNativeActive = (targetIndex, source = "programmatic") => {
                  targetIndex = Math.max(0, Math.min(tabs.length - 1, Number(targetIndex) || 0));
                  if (!tabs[targetIndex]) return;
                  if (source === "swipe") {
                    const fromIndex = pagerSwipeActive ? pagerSwipeFromIndex : activeIndex;
                    const fromOffset = pagerSwipeActive ? pagerSwipePreviewOffset : 0;
                    finishNativeSwipe(fromIndex, targetIndex, fromOffset);
                    pagerSwipeActive = false;
                    return;
                  }
                  if (source === "programmatic") {
                    bottomNav.classList.add("no-animation");
                    tabs.forEach((item, index) => {
                      item.classList.toggle("active", index === targetIndex);
                      item.classList.remove("play-motion");
                    });
                    bottomNav.classList.remove("swiping", "swipe-settle");
                    slidingPill.style.transform = "translateX(0px)";
                    moveSlidingPillToTab(tabs[targetIndex], false);
                    if (indicatorBar) indicatorBar.style.transform = "translateX(0px)";
                    moveIndicatorToTab(tabs[targetIndex], false);
                    activeIndex = targetIndex;
                    pagerSwipeActive = false;
                    window.requestAnimationFrame(() => {
                      bottomNav.classList.remove("no-animation");
                    });
                    return;
                  }
                  setActiveTabByIndex(targetIndex, false);
                };

                const syncPagerSwipe = (position, offset) => {
                  const fraction = Math.max(0, Math.min(1, Number(offset) || 0));
                  const startIndex = Math.max(0, Math.min(tabs.length - 1, Number(position) || 0));
                  const endIndex = Math.min(tabs.length - 1, startIndex + 1);
                  if (startIndex === endIndex) return;
                  if (fraction <= 0) {
                    if (!pagerSwipeActive) return;
                    bottomNav.classList.remove("swiping");
                    moveIndicatorToTab(tabs[activeIndex] || tabs[startIndex], false);
                    pagerSwipeActive = false;
                    return;
                  }

                  const start = getIconShellPosition(tabs[startIndex]);
                  const end = getIconShellPosition(tabs[endIndex]);
                  if (!start || !end) return;
                  const travel = end.left - start.left;
                  const fromIndex = activeIndex === endIndex ? endIndex : startIndex;
                  const previewOffset = fromIndex === startIndex
                    ? travel * fraction
                    : -travel * (1 - fraction);

                  pagerSwipeFromIndex = fromIndex;
                  pagerSwipePreviewOffset = previewOffset;
                  pagerSwipeActive = true;
                  bottomNav.classList.add("swiping");
                  moveSlidingPillToTab(tabs[fromIndex], false);
                  slidingPill.style.transform = `translateX(${'$'}{previewOffset}px)`;
                  moveIndicatorToTab(tabs[fromIndex], false, previewOffset);
                };

                const notifyNativeSelection = (targetIndex) => {
                  if (window.MDWechatTabBridge && window.MDWechatTabBridge.selectTab) {
                    window.MDWechatTabBridge.selectTab(targetIndex);
                  }
                };

                const previewNativeSwipe = (startIndex, deltaX) => {
                  startIndex = Math.max(0, Math.min(tabs.length - 1, Number(startIndex) || 0));
                  const tabWidth = tabs[0]?.getBoundingClientRect().width || 96;
                  const boundedOffset = Math.max(-tabWidth, Math.min(tabWidth, Number(deltaX) || 0));
                  const previewOffset = -boundedOffset;
                  pointerActiveIndex = startIndex;
                  pointerPreviewOffset = previewOffset;
                  bottomNav.classList.add("swiping");
                  pagerSwipeActive = false;
                  moveSlidingPillToTab(tabs[startIndex], false);
                  slidingPill.style.transform = `translateX(${'$'}{previewOffset}px)`;
                  moveIndicatorToTab(tabs[startIndex], false, previewOffset);
                };

                const finishNativeSwipe = (fromIndex, targetIndex, fromOffsetX) => {
                  targetIndex = Math.max(0, Math.min(tabs.length - 1, Number(targetIndex) || 0));
                  const changed = targetIndex !== activeIndex;
                  tabs.forEach((item) => item.classList.remove("active", "play-motion"));
                  settleSlidingPill(fromIndex, targetIndex, fromOffsetX);
                  tabs[targetIndex]?.classList.add("active");
                  activeIndex = targetIndex;
                  if (changed) playSelectedMotion(targetIndex);
                };

                const cancelNativeSwipe = () => {
                  finishNativeSwipe(activeIndex, activeIndex, pointerPreviewOffset);
                };

                const setActiveTabByClick = (tab) => {
                  const nextIndex = tabs.indexOf(tab);
                  if (nextIndex < 0 || activeIndex === nextIndex) return;
                  setActiveTabByIndex(nextIndex, false);
                  notifyNativeSelection(nextIndex);
                };

                const startPointerSwipe = (event) => {
                  if (event.pointerType === "mouse" && event.button !== 0) return;
                  isPointerDown = true;
                  isDragging = false;
                  pointerStartX = event.clientX;
                  pointerStartY = event.clientY;
                  pointerDeltaX = 0;
                  pointerPreviewOffset = 0;
                  pointerActiveIndex = activeIndex;
                };

                const updatePointerSwipe = (event) => {
                  if (!isPointerDown) return;
                  pointerDeltaX = event.clientX - pointerStartX;
                  const pointerDeltaY = event.clientY - pointerStartY;
                  const absX = Math.abs(pointerDeltaX);
                  const absY = Math.abs(pointerDeltaY);

                  if (!isDragging && absY > 12 && absY > absX) {
                    isPointerDown = false;
                    return;
                  }

                  if (!isDragging && absX > dragStartThresholdPx && absX > absY * 1.15) {
                    isDragging = true;
                    bottomNav.classList.add("swiping");
                    moveSlidingPillToTab(tabs[Math.max(0, pointerActiveIndex)], false);
                    moveIndicatorToTab(tabs[Math.max(0, pointerActiveIndex)], false);
                    document.body.setPointerCapture?.(event.pointerId);
                  }

                  if (!isDragging) return;

                  event.preventDefault();
                  const tabWidth = tabs[0]?.getBoundingClientRect().width || 96;
                  const boundedOffset = Math.max(-tabWidth, Math.min(tabWidth, pointerDeltaX));
                  pointerPreviewOffset = -boundedOffset;
                  slidingPill.style.transform = `translateX(${'$'}{pointerPreviewOffset}px)`;
                  if (indicatorBar) indicatorBar.style.transform = `translateX(${'$'}{pointerPreviewOffset}px)`;
                };

                const finishPointerSwipe = (event) => {
                  if (!isPointerDown) return;
                  isPointerDown = false;

                  if (!isDragging) {
                    const targetTab = document.elementFromPoint(event.clientX, event.clientY)?.closest(".tab");
                    if (targetTab && bottomNav.contains(targetTab)) {
                      ignoreNextClick = true;
                      setActiveTabByClick(targetTab);
                      window.setTimeout(() => {
                        ignoreNextClick = false;
                      }, 120);
                    }
                    return;
                  }

                  isDragging = false;
                  ignoreNextClick = true;

                  const tabWidth = tabs[0]?.getBoundingClientRect().width || 96;
                  const threshold = Math.max(38, Math.min(64, tabWidth * 0.28));
                  let targetIndex = pointerActiveIndex;

                  if (pointerDeltaX <= -threshold) {
                    targetIndex = Math.min(tabs.length - 1, pointerActiveIndex + 1);
                  } else if (pointerDeltaX >= threshold) {
                    targetIndex = Math.max(0, pointerActiveIndex - 1);
                  }

                  finishNativeSwipe(pointerActiveIndex, targetIndex, pointerPreviewOffset);
                  if (targetIndex !== pointerActiveIndex) {
                    notifyNativeSelection(targetIndex);
                  }

                  window.setTimeout(() => {
                    ignoreNextClick = false;
                  }, 120);
                };

                const cancelPointerSwipe = () => {
                  if (!isPointerDown && !isDragging) return;
                  isPointerDown = false;
                  isDragging = false;
                  finishNativeSwipe(activeIndex, activeIndex, pointerPreviewOffset);
                };

                const setBadge = (index, count) => {
                  const badge = tabs[index]?.querySelector(".badge");
                  if (!badge) return;
                  badge.classList.remove("dot");
                  const value = Number(count) || 0;
                  if (value > 0) {
                    badge.textContent = String(value);
                    badge.style.display = "block";
                  } else if (value < 0) {
                    badge.textContent = "";
                    badge.classList.add("dot");
                    badge.style.display = "block";
                  } else {
                    badge.textContent = "";
                    badge.style.display = "none";
                  }
                };

                const clearBadge = (index) => setBadge(index, 0);

                tabs.forEach((tab) => {
                  tab.addEventListener("click", () => {
                    if (ignoreNextClick) {
                      ignoreNextClick = false;
                      return;
                    }
                    if (tab.classList.contains("active")) return;
                    setActiveTabByClick(tab);
                  });

                  tab.addEventListener("animationend", () => {
                    tab.classList.remove("play-motion");
                  });
                });

                document.body.addEventListener("pointerdown", startPointerSwipe);
                document.body.addEventListener("pointermove", updatePointerSwipe, { passive: false });
                document.body.addEventListener("pointerup", finishPointerSwipe);
                document.body.addEventListener("pointercancel", cancelPointerSwipe);

                window.MDWechatTabs = {
                  setNativeActive,
                  syncPagerSwipe,
                  previewNativeSwipe,
                  finishNativeSwipe,
                  cancelNativeSwipe,
                  setBadge,
                  clearBadge,
                };

                ${buildInitialSelectionScript()}
                ${buildBadgeBootstrapScript()}

                window.addEventListener("resize", () => {
                  moveSlidingPillToTab(tabs[activeIndex] || tabs[0], false);
                  moveIndicatorToTab(tabs[activeIndex] || tabs[0], false);
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildTabsMarkup(): String {
        return items.mapIndexed { index, item ->
            val activeClass = if (index == selectedPosition) " active" else ""
            val icon = iconNameFor(index)
            val motionClass = motionClassFor(index)
            """
                <button class="tab$activeClass" type="button" data-id="$index" data-label="${item.text.toString().htmlEscape()}">
                  <span class="icon-shell">
                    <span class="pill"></span>
                    <span class="icon-wrap">
                      <span class="material-symbols-rounded $motionClass">$icon</span>
                      <span class="badge"></span>
                    </span>
                  </span>
                  <span class="label">${item.text.toString().htmlEscape()}</span>
                </button>
            """.trimIndent()
        }.joinToString("\n")
    }

    private fun buildBadgeBootstrapScript(): String {
        return unreadCounts.entries.joinToString("\n") { (index, count) ->
            "setBadge($index, $count);"
        }
    }

    private fun buildInitialSelectionScript(): String {
        if (selectedPosition !in items.indices) {
            return """
                window.requestAnimationFrame(() => {
                  bottomNav.classList.remove("no-animation");
                });
            """.trimIndent()
        }
        return "setNativeActive($selectedPosition, \"programmatic\");"
    }

    private fun iconNameFor(index: Int): String = when (index) {
        0 -> "chat"
        1 -> "contacts"
        2 -> "explore"
        3 -> "person"
        else -> "circle"
    }

    private fun motionClassFor(index: Int): String = when (index) {
        0 -> "chat-motion"
        1 -> "contact-motion"
        2 -> "compass-motion"
        else -> "person-motion"
    }

    private fun loadMaterialSymbolsFontDataUri(): String {
        return context.assets.open(FONT_ASSET_PATH).use { stream ->
            val encoded = Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
            "data:font/woff2;base64,$encoded"
        }
    }

    private fun handleTouchLayerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startNativeTouch(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateNativeTouch(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (touchForwardingSwipe) {
                    forwardTouchToViewPager(event, MotionEvent.ACTION_UP)
                    finishNativeTouch()
                    return true
                }
                if (touchDragging) {
                    finishNativeTouch()
                    return true
                }
                finishNativeTap(event.x)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (touchForwardingSwipe) {
                    forwardTouchToViewPager(event, MotionEvent.ACTION_CANCEL)
                }
                finishNativeTouch()
                return true
            }
        }
        return true
    }

    private fun startNativeTouch(event: MotionEvent) {
        touchStartX = event.x
        touchStartY = event.y
        touchStartRawX = event.rawX
        touchDragging = false
        touchForwardingSwipe = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun updateNativeTouch(event: MotionEvent) {
        val deltaX = event.x - touchStartX
        val deltaY = event.y - touchStartY
        val absX = abs(deltaX)
        val absY = abs(deltaY)
        val movedPastTap = deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop
        if (movedPastTap) {
            touchDragging = true
        }
        if (!touchForwardingSwipe && absX > touchSlop && absX > absY * 1.15f) {
            touchForwardingSwipe = true
            parent?.requestDisallowInterceptTouchEvent(true)
            forwardTouchToViewPager(event, MotionEvent.ACTION_DOWN, touchStartRawX)
        }
        if (touchForwardingSwipe) {
            forwardTouchToViewPager(event, MotionEvent.ACTION_MOVE)
        }
        parent?.requestDisallowInterceptTouchEvent(touchForwardingSwipe)
    }

    private fun finishNativeTouch() {
        touchDragging = false
        touchForwardingSwipe = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun forwardTouchToViewPager(
        sourceEvent: MotionEvent,
        action: Int,
        rawXOverride: Float? = null
    ): Boolean {
        val viewPager = Objects.Main.LauncherUI_mViewPager ?: return false
        if (viewPager.width <= 0 || viewPager.height <= 0) {
            return false
        }
        val location = IntArray(2)
        viewPager.getLocationOnScreen(location)
        val rawX = rawXOverride ?: sourceEvent.rawX
        val targetX = rawX - location[0]
        val targetY = viewPager.height / 2f
        val eventTime = if (action == MotionEvent.ACTION_DOWN) {
            sourceEvent.downTime
        } else {
            sourceEvent.eventTime
        }
        val forwarded = MotionEvent.obtain(
            sourceEvent.downTime,
            eventTime,
            action,
            targetX,
            targetY,
            sourceEvent.metaState
        )
        val handled = viewPager.dispatchTouchEvent(forwarded)
        forwarded.recycle()
        return handled
    }

    private fun finishNativeTap(x: Float) {
        val targetIndex = tabIndexAt(x)
        if (targetIndex !in items.indices || targetIndex == selectedPosition) {
            return
        }
        selectedPosition = targetIndex
        pendingSelectionSource = MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
        runJs("window.MDWechatTabs && window.MDWechatTabs.setNativeActive($targetIndex, 'click');")
        onTabSelected?.invoke(targetIndex)
    }

    private inner class TabBridge {
        @JavascriptInterface
        fun selectTab(index: Int) {
            post {
                if (index !in items.indices || index == selectedPosition) {
                    return@post
                }
                selectedPosition = index
                pendingSelectionSource = MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC
                onTabSelected?.invoke(index)
            }
        }
    }

    private val MaterialTabIconTransitionPolicy.SelectionSource.webName: String
        get() = when (this) {
            MaterialTabIconTransitionPolicy.SelectionSource.CLICK -> "click"
            MaterialTabIconTransitionPolicy.SelectionSource.SWIPE -> "swipe"
            MaterialTabIconTransitionPolicy.SelectionSource.PROGRAMMATIC -> "programmatic"
        }

    private fun Int.toCssColor(): String {
        val alpha = (this ushr 24) and 0xFF
        val red = (this ushr 16) and 0xFF
        val green = (this ushr 8) and 0xFF
        val blue = this and 0xFF
        if (alpha == 0xFF) {
            return String.format(Locale.US, "#%02x%02x%02x", red, green, blue)
        }
        return String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", red, green, blue, alpha / 255f)
    }

    private fun Float.toJsNumber(): String {
        return String.format(Locale.US, "%.4f", this)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun tabIndexAt(x: Float): Int {
        if (items.isEmpty() || width <= 0) {
            return -1
        }
        val tabWidth = width.toFloat() / items.size
        return (x / tabWidth).toInt().coerceIn(0, items.lastIndex)
    }

    private fun String.htmlEscape(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    companion object {
        private const val FONT_ASSET_PATH = "tablayout/material-symbols-rounded-latin-fill-normal.woff2"
        private const val PREVIEW_ACTIVE_CONTAINER = 0xFFC2E7FF.toInt()
        private const val PREVIEW_NAV_BACKGROUND = 0x00000000
        private const val PREVIEW_BADGE_COLOR = 0xFFB3261E.toInt()
    }
}
