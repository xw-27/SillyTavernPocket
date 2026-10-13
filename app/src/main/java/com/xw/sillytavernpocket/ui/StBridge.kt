package com.xw.sillytavernpocket.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 酒馆顶部设置栏里的一个面板入口:id 用于点击,title 用于显示。 */
internal data class PanelEntry(val id: String, val title: String, val icon: android.graphics.Bitmap?)

/**
 * 原生界面与酒馆之间的唯一通道,对应桥接插件 pocket-bridge 的协议 v1。
 *
 * 这里不包含任何酒馆页面的 JS:所有对酒馆的操作都以"命令"的形式交给插件执行。
 * 本类只负责:发送命令、接收结果、转发插件事件、检测插件是否就绪。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class StBridge(
    context: Context,
    private val onReady: () -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, (Result<String>) -> Unit>()
    private val readyCheck = Runnable { onProblem(PLUGIN_MISSING_HINT) }

    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // UA 后缀是插件判断"是否运行在口袋酒馆里"的依据,不需要向页面注入任何全局变量
        settings.userAgentString = settings.userAgentString + " SillyTavernPocket/" + appVersion(context)
        addJavascriptInterface(Native(), "PocketNative")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 插件在页面加载后初始化;超时仍没有 ready,说明插件没有启用
                main.removeCallbacks(readyCheck)
                main.postDelayed(readyCheck, READY_TIMEOUT_MS)
            }
        }
    }

    fun load(url: String) = view.loadUrl(url)
    fun canGoBack(): Boolean = view.canGoBack()
    fun goBack() = view.goBack()

    /** 与 WebView 的生命周期保持一致:切回前台时恢复渲染与定时器,不暂停后台生成。 */
    fun onResume() {
        view.onResume()
        view.resumeTimers()
    }

    fun newChat(callback: (Result<Unit>) -> Unit) {
        call("newChat", JSONObject()) { r -> callback(r.map { }) }
    }

    /** 读取酒馆页面的主题颜色(由插件返回,已换算为不透明颜色)。 */
    fun fetchTheme(callback: (Result<ShellTheme>) -> Unit) {
        call("theme", JSONObject()) { r ->
            callback(r.mapCatching { ShellTheme.fromJson(it) ?: throw IllegalStateException("主题数据不完整") })
        }
    }

    /** 读取酒馆顶部设置栏里的面板入口,用于原生应用菜单。 */
    fun listPanels(callback: (Result<List<PanelEntry>>) -> Unit) {
        call("listPanels", JSONObject()) { r ->
            callback(r.mapCatching { json ->
                val arr = JSONArray(json)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    PanelEntry(
                        id = o.getString("id"),
                        title = o.optString("title", o.getString("id")),
                        icon = decodePng(o.optString("icon")),
                    )
                }
            })
        }
    }

    /** 解码插件传来的 data:image/png;base64 图标,失败时返回 null。 */
    private fun decodePng(dataUrl: String): android.graphics.Bitmap? {
        if (dataUrl.isEmpty()) return null
        val b64 = dataUrl.substringAfter("base64,", "")
        if (b64.isEmpty()) return null
        return runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** 收起酒馆里展开的面板。失败时不影响界面,调用方可忽略结果。 */
    fun closePanels(callback: (Result<Unit>) -> Unit = {}) {
        call("closePanels", JSONObject()) { r -> callback(r.map { }) }
    }

    /** 打开酒馆原有的面板(AI 配置、角色管理、世界书等),入口由插件转发为点击。 */
    fun openPanel(domId: String, callback: (Result<Unit>) -> Unit) {
        call("openPanel", JSONObject().put("id", domId)) { r -> callback(r.map { }) }
    }

    /** 发送命令。插件不存在、超时或执行出错,都以 Result.failure 返回。 */
    private fun call(cmd: String, args: JSONObject, callback: (Result<String>) -> Unit) {
        val id = ids.incrementAndGet()
        pending[id] = callback
        val script = "if (window.__pocketShell) { window.__pocketShell.call($id, ${JSONObject.quote(cmd)}, $args); }" +
            " else { PocketNative.onError($id, 'NO_PLUGIN'); }"
        main.post { view.evaluateJavascript(script, null) }
        main.postDelayed({ complete(id, Result.failure(IllegalStateException("操作超时"))) }, CALL_TIMEOUT_MS)
    }

    private fun complete(id: Int, result: Result<String>) {
        val cb = pending.remove(id) ?: return
        main.post { cb(result) }
    }

    private fun onPluginReady() {
        main.removeCallbacks(readyCheck)
        // 确认协议版本,不匹配则提示,不继续使用
        call("hello", JSONObject()) { r ->
            val protocol = r.mapCatching { JSONObject(it).optInt("protocol", -1) }.getOrDefault(-1)
            if (protocol == PROTOCOL) {
                onReady()
            } else {
                onProblem("桥接插件版本不匹配(协议 $protocol),请更新口袋酒馆")
            }
        }
    }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "dev"

    /**
     * 插件回传的接收端。WebView 要求方法为 public,所以不用 private。
     * 只提供回传与事件通知,没有任何能直接执行原生操作的方法。
     */
    inner class Native {
        @JavascriptInterface
        fun onResult(id: Int, json: String) {
            val result = try {
                val o = JSONObject(json)
                if (o.optBoolean("ok")) {
                    Result.success(o.opt("data")?.toString() ?: "null")
                } else {
                    Result.failure(IllegalStateException(o.optString("error", "未知错误")))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            complete(id, result)
        }

        @JavascriptInterface
        fun onError(id: Int, message: String) {
            // id < 0 表示插件自身初始化失败,与具体命令无关
            if (id < 0) {
                main.post { onProblem(message) }
                return
            }
            val text = if (message == "NO_PLUGIN") PLUGIN_MISSING_HINT else message
            complete(id, Result.failure(IllegalStateException(text)))
        }

        @JavascriptInterface
        fun onEvent(name: String) {
            main.post {
                when (name) {
                    "ready" -> onPluginReady()
                }
            }
        }
    }

    private companion object {
        const val PROTOCOL = 1
        const val CALL_TIMEOUT_MS = 60_000L
        const val READY_TIMEOUT_MS = 30_000L
        const val PLUGIN_MISSING_HINT = "桥接插件未启用,请在酒馆的扩展面板中启用「口袋酒馆桥接」"
    }
}
