package com.xw.sillytavernpocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 前台服务:以子进程方式运行 Node.js,并负责监督(异常退出后按退避策略重启)。
 *
 * Node 运行在独立进程中:它崩溃不会带走 App 进程和 WebView,并且可以单独重启。
 * 启动阶段通过 [BootLog] 上报,开机页据此显示进度。
 */
class NodeService : Service() {

    @Volatile private var stopping = false
    @Volatile private var process: Process? = null
    private var supervisor: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // 最低重要性:状态栏不显示图标、不发声,只在通知栏底部折叠显示
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "酒馆服务", NotificationManager.IMPORTANCE_MIN)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("口袋酒馆")
            .setContentText("酒馆服务运行中")
            .setSmallIcon(R.drawable.ic_stat_notify)
            // 通知栏显示的应用图标用酒馆自己的图标,而不是系统默认的占位图
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (supervisor == null) {
            supervisor = Thread(::superviseLoop, "node-supervisor").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        process?.destroy()
        super.onDestroy()
    }

    private fun superviseLoop() {
        val exe = File(applicationInfo.nativeLibraryDir, NODE_EXE)

        BootLog.beginStage(BootLog.STAGE_EXTRACT)
        val kernelDir = try {
            KernelInstaller.ensureInstalled(this) { BootLog.setProgress(it) }
        } catch (e: Exception) {
            BootLog.setProgress(-1)
            BootLog.endStage(BootLog.STAGE_EXTRACT, false)
            BootLog.log("内核准备失败:${e.message}")
            Log.e(TAG, "内核准备失败: ${e.message}")
            return
        }
        BootLog.setProgress(-1)
        BootLog.endStage(BootLog.STAGE_EXTRACT, true)

        val dataRoot = File(filesDir, "st-data").apply { mkdirs() }
        // 端口在整个服务生命周期内固定,Node 重启后 WebView 的地址不变
        val port = pickFreePort()
        serverPort = port

        var failures = 0
        while (!stopping) {
            val startedAt = System.currentTimeMillis()
            val pb = ProcessBuilder(
                exe.absolutePath, "server.js",
                "--port", port.toString(),
                "--dataRoot", dataRoot.absolutePath,
                // Android 上没有 xdg-open,自动打开浏览器会抛出未捕获异常导致 Node 退出
                "--browserLaunchEnabled", "false",
            )
                .directory(kernelDir)
                .redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
                // 库查找路径:Node 依赖的 .so 与可执行文件在同一个 nativeLibraryDir 中
                put("LD_LIBRARY_PATH", applicationInfo.nativeLibraryDir)
                // Termux 的 OpenSSL 配置路径写死在前缀中,这里指向空配置(Node 自带 CA 证书)
                put("OPENSSL_CONF", "/dev/null")
            }

            BootLog.beginStage(BootLog.STAGE_NODE)
            val p = try {
                pb.start()
            } catch (e: IOException) {
                Log.e(TAG, "无法启动 Node: ${e.message}")
                BootLog.endStage(BootLog.STAGE_NODE, false)
                failures++
                if (failures >= MAX_FAILURES) break
                Thread.sleep(backoffMs(failures))
                continue
            }
            BootLog.endStage(BootLog.STAGE_NODE, true)
            process = p
            BootLog.beginStage(BootLog.STAGE_FRONTEND)

            p.inputStream.bufferedReader().forEachLine { line ->
                Log.i(NODE_TAG, line)
                BootLog.log(line)
                // 按 ST 的输出关键行推进阶段。ST 的输出带 ANSI 颜色码,匹配前先去掉
                val plain = line.replace(ANSI_ESCAPE, "")
                if (plain.contains("compiled successfully")) {
                    BootLog.endStage(BootLog.STAGE_FRONTEND, true)
                    BootLog.beginStage(BootLog.STAGE_HTTP)
                } else if (plain.contains("is listening on")) {
                    // 兜底:编译行没匹配上时,监听行也会把前面的阶段收尾
                    BootLog.endStage(BootLog.STAGE_FRONTEND, true)
                    BootLog.endStage(BootLog.STAGE_HTTP, true)
                }
            }
            val code = p.waitFor()
            process = null
            if (stopping) break

            BootLog.failRunning()
            BootLog.log("Node 已退出(退出码 $code),稍后自动重启")
            Log.w(TAG, "Node 退出,exit=$code")
            val uptime = System.currentTimeMillis() - startedAt
            failures = if (uptime > STABLE_UPTIME_MS) 0 else failures + 1
            if (failures >= MAX_FAILURES) {
                BootLog.log("Node 连续 $failures 次异常退出,已停止自动重启,请重新打开应用")
                Log.e(TAG, "Node 连续异常退出 $failures 次,停止重启")
                break
            }
            Thread.sleep(backoffMs(failures))
        }
    }

    private fun backoffMs(failures: Int): Long = minOf(30_000L, 1_000L shl failures.coerceAtMost(10))

    companion object {
        private const val TAG = "NodeService"
        private const val NODE_TAG = "NodeJS"
        private const val NODE_EXE = "libnode-exec.so"
        // 通道 ID 变更过一次:旧通道的重要性已固化在设备上,换 ID 才能生效
        private const val CHANNEL_ID = "node_service"
        private const val NOTIFICATION_ID = 1
        private const val MAX_FAILURES = 5
        private const val STABLE_UPTIME_MS = 60_000L
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*[A-Za-z]")

        /** 酒馆服务端口,由 superviseLoop 在启动时选定;未就绪时为 -1。 */
        @Volatile var serverPort: Int = -1
            private set

        /** 让系统分配一个空闲的回环端口,用完即释放。 */
        private fun pickFreePort(): Int =
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }
}
