package com.xw.sillytavernpocket

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 把随 APK 打包的内核(assets/kernel.zip)安装到 filesDir/kernel。
 *
 * - assets 是只读的,而 Node 需要在磁盘上运行,所以必须解压出来。
 * - 用 assets/kernel.version 与 filesDir/kernel/.kernel-version 比较,不一致才重装。
 * - 进度按已写入的字节数除以 assets/kernel.size(解压后的总字节数)计算。
 * - 用户数据在 filesDir/st-data,不在内核目录里,因此升级内核不会丢数据。
 */
object KernelInstaller {

    private const val ZIP_ASSET = "kernel.zip"
    private const val VERSION_ASSET = "kernel.version"
    private const val SIZE_ASSET = "kernel.size"
    private const val VERSION_FILE = ".kernel-version"

    fun ensureInstalled(context: Context, onProgress: (Int) -> Unit = {}): File {
        val dest = File(context.filesDir, "kernel")
        val bundled = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        val installed = File(dest, VERSION_FILE).takeIf { it.exists() }?.readText()?.trim()
        if (installed == bundled) return dest

        val totalBytes = context.assets.open(SIZE_ASSET).bufferedReader().use { it.readText().trim().toLong() }
        val tmp = File(context.filesDir, "kernel.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()

        var doneBytes = 0L
        var lastPercent = -1
        val buf = ByteArray(64 * 1024)
        ZipInputStream(context.assets.open(ZIP_ASSET).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(tmp, entry.name)
                // 防止路径穿越(zip slip)
                check(out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) { "非法路径: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        doneBytes += n
                        val percent = (doneBytes * 100 / totalBytes).toInt().coerceAtMost(100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
        File(tmp, VERSION_FILE).writeText(bundled)

        dest.deleteRecursively()
        check(tmp.renameTo(dest)) { "无法安装内核到 ${dest.absolutePath}" }
        return dest
    }
}
