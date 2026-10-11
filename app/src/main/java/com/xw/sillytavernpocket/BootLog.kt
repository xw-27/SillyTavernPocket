package com.xw.sillytavernpocket

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 启动过程的阶段状态与 Node 输出,供开机页显示。
 *
 * 直接保存在进程内存中,不经过 logcat(logcat 在部分设备上会丢行)。
 * 监听器在主线程回调,多次变化会合并成一次刷新。
 */
object BootLog {

    /** 一个启动阶段:进行中的文案与完成后的文案。 */
    data class Step(val running: String, val done: String)

    val STAGE_EXTRACT = Step("正在准备酒馆内核", "酒馆内核已就绪")
    val STAGE_NODE = Step("正在启动 Node 运行时", "Node 运行时已启动")
    val STAGE_FRONTEND = Step("正在编译前端资源", "前端资源编译完成")
    val STAGE_HTTP = Step("正在启动 HTTP 服务", "HTTP 服务已就绪")
    val STAGE_READY = Step("即将进入酒馆", "一切就绪")

    enum class State { RUNNING, OK, FAILED }

    class Stage(val step: Step, val startMs: Long) {
        @Volatile var state: State = State.RUNNING
        @Volatile var endMs: Long = 0L

        val elapsedMs: Long
            get() = (if (endMs == 0L) System.currentTimeMillis() else endMs) - startMs

        /** 界面上显示的文案:进行中 / 已完成 / 未完成。 */
        fun label(): String = when (state) {
            State.RUNNING -> step.running
            State.OK -> step.done
            State.FAILED -> "未完成:${step.running.removePrefix("正在")}"
        }
    }

    private const val MAX_LINES = 300
    private const val REFRESH_DELAY_MS = 100L

    private val lock = Any()
    private val stages = mutableListOf<Stage>()
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var refreshScheduled = false

    @Volatile var progress: Int = -1  // 0..100,-1 表示不显示进度条
        private set

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun beginStage(step: Step) {
        synchronized(lock) { stages.add(Stage(step, System.currentTimeMillis())) }
        notifyChanged()
    }

    fun endStage(step: Step, ok: Boolean) {
        synchronized(lock) {
            stages.lastOrNull { it.step == step && it.state == State.RUNNING }?.let {
                it.state = if (ok) State.OK else State.FAILED
                it.endMs = System.currentTimeMillis()
            }
        }
        notifyChanged()
    }

    /** 把所有仍在进行中的阶段标记为失败(例如 Node 异常退出时)。 */
    fun failRunning() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            stages.filter { it.state == State.RUNNING }.forEach {
                it.state = State.FAILED
                it.endMs = now
            }
        }
        notifyChanged()
    }

    fun setProgress(percent: Int) {
        progress = percent
        notifyChanged()
    }

    fun log(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        notifyChanged()
    }

    fun snapshot(): Triple<List<Stage>, List<String>, Int> = synchronized(lock) {
        Triple(stages.toList(), lines.toList(), progress)
    }

    private fun notifyChanged() {
        synchronized(lock) {
            if (refreshScheduled) return
            refreshScheduled = true
        }
        main.postDelayed({
            synchronized(lock) { refreshScheduled = false }
            listeners.forEach { it() }
        }, REFRESH_DELAY_MS)
    }
}
