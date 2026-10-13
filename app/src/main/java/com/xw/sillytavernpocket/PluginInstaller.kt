package com.xw.sillytavernpocket

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 把内置的桥接插件 pocket-bridge 安装到酒馆默认用户的扩展目录。
 *
 * 插件源码位于仓库 ui/pocket-bridge,构建时打进 assets/pocket-bridge。
 * 每次启动都覆盖安装,保证插件版本与 APK 一致;用户数据不在扩展目录里,不受影响。
 * 扩展目录位于用户数据内,内核升级不会丢失。
 */
object PluginInstaller {
    private const val ASSET_DIR = "pocket-bridge"
    private val FILES = listOf("manifest.json", "index.js", "style.css")

    fun install(context: Context) {
        val extensions = File(context.filesDir, "st-data/default-user/extensions").apply { mkdirs() }
        val target = File(extensions, "pocket-bridge")
        val tmp = File(extensions, "pocket-bridge.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        for (name in FILES) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                File(tmp, name).outputStream().use { input.copyTo(it) }
            }
        }
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("无法安装桥接插件到 ${target.absolutePath}")
    }
}
