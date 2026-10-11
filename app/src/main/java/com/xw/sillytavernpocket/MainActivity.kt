package com.xw.sillytavernpocket

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 开机页:以 systemd 风格显示启动阶段与进度,服务就绪后切换到酒馆 WebView。
 */
class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var stageText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progressBar: ProgressBar
    private var webView: WebView? = null
    private val refresh: () -> Unit = { render() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        stageText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.rgb(0xCC, 0xCC, 0xCC))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(0x88, 0x88, 0x88))
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        logScroll = ScrollView(this).apply { addView(logText) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
            addView(stageText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // targetSdk 36 为 edge-to-edge:内容会画到状态栏、刘海、导航栏和键盘下面,这里统一留出内边距
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            applySafeAreaPadding()
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(container)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        BootLog.addListener(refresh)
        render()
        startForegroundService(Intent(this, NodeService::class.java))
        requestBatteryExemptionOnce()

        thread(name = "wait-server") { waitUntilReady() }
    }

    override fun onResume() {
        super.onResume()
        // 切回前台时恢复 WebView 的定时器与渲染。只恢复、不暂停:后台生成需要 JS 继续处理数据流
        webView?.onResume()
        webView?.resumeTimers()
    }

    /** 首次启动时请求加入电池优化白名单,避免国产系统在后台冻结或杀死服务。只提示一次。 */
    private fun requestBatteryExemptionOnce() {
        val prefs = getSharedPreferences("boot", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            // 部分系统没有这个页面,忽略
        }
    }

    override fun onDestroy() {
        BootLog.removeListener(refresh)
        super.onDestroy()
    }

    private fun waitUntilReady() {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        // 先等服务选定端口,再等 HTTP 服务应答
        while (NodeService.serverPort < 0 && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val url = "http://127.0.0.1:${NodeService.serverPort}/"
        val ready = NodeService.serverPort >= 0 && waitForServer(url, deadline)

        BootLog.beginStage(BootLog.STAGE_READY)
        if (!ready) {
            BootLog.endStage(BootLog.STAGE_READY, false)
            BootLog.log("等待 5 分钟仍无响应,酒馆服务可能没有正常启动")
            return
        }
        BootLog.endStage(BootLog.STAGE_READY, true)
        Thread.sleep(600) // 让用户看到最后的 [  OK  ]
        runOnUiThread { showWebView(url) }
    }

    private fun showWebView(url: String) {
        BootLog.removeListener(refresh)
        val view = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        webView = view
        container.removeAllViews()
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.loadUrl(url)
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

    private fun waitForServer(url: String, deadline: Long): Boolean {
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 1_000
                conn.readTimeout = 1_000
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return true
            } catch (_: Exception) {
                // 服务还没起来,继续等
            }
            Thread.sleep(1_000)
        }
        return false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 按系统栏、刘海和键盘高度设置内边距;低版本回退到 fitsSystemWindows。 */
    private fun View.applySafeAreaPadding() {
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

    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    companion object {
        // 首次启动要解压内核并编译前端,留足时间
        private const val WAIT_TIMEOUT_MS = 300_000L
    }
}
