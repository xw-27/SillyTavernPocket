package com.xw.sillytavernpocket

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.xw.sillytavernpocket.ui.ShellUi
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 应用入口。只负责生命周期、系统权限和服务启动;所有界面操作都在 [ShellUi] 中完成。
 */
class MainActivity : Activity() {

    private lateinit var ui: ShellUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ui = ShellUi(this)
        setContentView(ui.root)
        ui.showBoot()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        try {
            PluginInstaller.install(this)
        } catch (e: Exception) {
            BootLog.log("桥接插件安装失败:${e.message}")
        }
        startForegroundService(Intent(this, NodeService::class.java))
        requestBatteryExemptionOnce()

        thread(name = "wait-server") { waitUntilReady() }
    }

    override fun onResume() {
        super.onResume()
        ui.onResume()
    }

    override fun onDestroy() {
        ui.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!ui.onBackPressed()) super.onBackPressed()
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
        runOnUiThread { ui.showShell(url) }
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

    companion object {
        // 首次启动要解压内核并编译前端,留足时间
        private const val WAIT_TIMEOUT_MS = 300_000L
    }
}
