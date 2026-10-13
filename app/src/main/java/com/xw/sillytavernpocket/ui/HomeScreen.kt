package com.xw.sillytavernpocket.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xw.sillytavernpocket.R

/**
 * 进入应用后的首页(原生):左上角 ≡ 和 +(并排),中间问候语。
 * 历史对话在左侧抽屉选择;发消息在酒馆对话页里进行。颜色全部跟随酒馆主题。
 */
internal class HomeScreen(
    private val context: Context,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
) {
    private val menu: ImageView = context.iconView(R.drawable.ic_menu, onMenu)
    private val newChat: ImageView = context.iconView(R.drawable.ic_plus, onNewChat)
    private val greeting = TextView(context).apply {
        text = "你好，今天想聊点什么？"
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menu, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(newChat, LinearLayout.LayoutParams(dp(36), dp(36)))
        }, LinearLayout.LayoutParams(MATCH, dp(48)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(greeting, LinearLayout.LayoutParams(WRAP, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    init {
        applyTheme(ShellTheme.FALLBACK)
    }

    /** 应用酒馆主题颜色 */
    fun applyTheme(t: ShellTheme) {
        view.setBackgroundColor(t.background)
        menu.tint(t.text)
        newChat.tint(t.text)
        greeting.setTextColor(t.text)
    }

    private fun dp(v: Int): Int = context.dp(v)
}
