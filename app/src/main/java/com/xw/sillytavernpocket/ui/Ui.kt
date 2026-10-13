package com.xw.sillytavernpocket.ui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/**
 * 按系统栏、刘海和键盘高度设置内边距。
 * targetSdk 36 为 edge-to-edge,内容会画到状态栏、刘海、导航栏和键盘下面,这里统一留出空间。
 * API 30 以下回退到 fitsSystemWindows。
 */
internal fun View.applySafeAreaPadding() {
    if (Build.VERSION.SDK_INT >= 30) {
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    } else {
        fitsSystemWindows = true
    }
}

/** 系统的按压水波纹背景 */
internal fun Context.selectableBackground(): Int {
    val tv = android.util.TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return tv.resourceId
}

/** 统一的图标按钮:40dp 方块,图标 24dp,颜色由 [tint] 设置。 */
internal fun Context.iconView(resId: Int, onClick: () -> Unit): android.widget.ImageView =
    android.widget.ImageView(this).apply {
        setImageResource(resId)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundResource(selectableBackground())
        isClickable = true
        setOnClickListener { onClick() }
    }

internal fun android.widget.ImageView.tint(color: Int) {
    setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
}
