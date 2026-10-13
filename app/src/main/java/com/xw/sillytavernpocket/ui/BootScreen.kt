package com.xw.sillytavernpocket.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.xw.sillytavernpocket.BootLog

/**
 * 开机页:以 systemd 风格显示启动阶段、进度和 Node 日志。数据来自 [BootLog]。
 */
internal class BootScreen(context: Context) {

    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        visibility = View.GONE
    }
    private val stageText = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextColor(Color.rgb(0xCC, 0xCC, 0xCC))
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
    }
    private val logText = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextColor(Color.rgb(0x88, 0x88, 0x88))
        setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(16))
    }
    private val logScroll = ScrollView(context).apply { addView(logText) }
    private val refresh: () -> Unit = { render() }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(progressBar, LinearLayout.LayoutParams(MATCH, context.dp(4)))
        addView(stageText, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    fun start() {
        BootLog.addListener(refresh)
        render()
    }

    fun stop() {
        BootLog.removeListener(refresh)
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val (stages, lines, progress) = BootLog.snapshot()
        val sb = SpannableStringBuilder()
        for (s in stages) {
            val (tag, color) = when (s.state) {
                BootLog.State.OK -> "[  OK  ]" to Color.rgb(0x55, 0xFF, 0x55)
                BootLog.State.RUNNING -> "[ .... ]" to Color.rgb(0xFF, 0xD7, 0x5F)
                BootLog.State.FAILED -> "[FAILED]" to Color.rgb(0xFF, 0x55, 0x55)
            }
            val start = sb.length
            sb.append(tag)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ").append(s.label())
            if (s.state != BootLog.State.RUNNING) {
                sb.append("  用时 %.1f 秒".format(s.elapsedMs / 1000.0))
            }
            sb.append("\n")
        }
        stageText.text = sb
        logText.text = lines.joinToString("\n")

        if (progress in 0..100) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = progress
        } else {
            progressBar.visibility = View.GONE
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
