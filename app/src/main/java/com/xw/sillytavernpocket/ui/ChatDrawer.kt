package com.xw.sillytavernpocket.ui

import android.content.Context
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xw.sillytavernpocket.R

/**
 * 左侧抽屉就是一个菜单:顶部新对话(+)和收起(‹),下面是酒馆顶部的全部按钮(经桥接器读取),
 * 每个按钮一行。只做展示和事件转发,颜色全部来自 [applyTheme]。
 */
internal class ChatDrawer(
    private val context: Context,
    private val onNewChat: () -> Unit,
    private val onPanel: (PanelEntry) -> Unit,
    private val onClose: () -> Unit,
) {
    private val menu = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(context).apply {
        textSize = 13f
        setPadding(dp(20), dp(14), dp(20), dp(14))
    }
    private val icons = mutableListOf<ImageView>()
    private val rowIcons = mutableListOf<ImageView>()
    private val rowTexts = mutableListOf<TextView>()

    private var theme = ShellTheme.FALLBACK

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(header(), LinearLayout.LayoutParams(MATCH, dp(48)))
        addView(ScrollView(context).apply { addView(menu) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    init {
        applyTheme(theme)
    }

    /** 应用酒馆主题颜色 */
    fun applyTheme(t: ShellTheme) {
        theme = t
        view.setBackgroundColor(t.background)
        icons.forEach { it.tint(t.text) }
        rowIcons.forEach { it.tint(t.text) }
        rowTexts.forEach { it.setTextColor(t.text) }
        status.setTextColor(t.subText)
    }

    /** 用酒馆的面板列表替换菜单内容 */
    /** 根据最长标题计算接近 wrap_content 的侧边栏宽度。 */
    fun preferredWidth(panels: List<PanelEntry>): Int {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f * context.resources.displayMetrics.scaledDensity
        }
        val longest = panels.maxOfOrNull { paint.measureText(it.title).toInt() } ?: 0
        // 左右边距 + 图标/图标间距;上下限只防止过窄或异常长标题撑满屏幕。
        return (longest + dp(40) + dp(38)).coerceIn(dp(200), dp(320))
    }

    fun setPanels(panels: List<PanelEntry>) {
        menu.removeAllViews()
        rowTexts.clear()
        rowIcons.clear()
        if (panels.isEmpty()) {
            status.text = "没有可用的菜单项"
            menu.addView(status)
        } else {
            panels.forEach { menu.addView(row(it)) }
        }
        applyTheme(theme)
    }

    fun setStatus(text: String) {
        menu.removeAllViews()
        rowTexts.clear()
        status.text = text
        menu.addView(status)
        applyTheme(theme)
    }

    /** 顶部:左边新对话(+),右边收起(‹)。 */
    private fun header(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        addView(context.iconView(R.drawable.ic_plus) { onNewChat() }.also { icons.add(it) },
            LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(View(context), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(context.iconView(R.drawable.ic_chevron_left) { onClose() }.also { icons.add(it) },
            LinearLayout.LayoutParams(dp(36), dp(36)))
    }

    /** 菜单的一行:酒馆的一个按钮,点击后打开对应面板。 */
    private fun row(entry: PanelEntry): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setBackgroundResource(context.selectableBackground())
        isClickable = true
        setOnClickListener { onPanel(entry) }
        entry.icon?.let { bmp ->
            addView(ImageView(context).apply {
                setImageBitmap(bmp)
                rowIcons.add(this)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        addView(TextView(context).apply {
            text = entry.title
            textSize = 16f
            rowTexts.add(this)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(16) })
    }

    private fun dp(v: Int): Int = context.dp(v)
}
