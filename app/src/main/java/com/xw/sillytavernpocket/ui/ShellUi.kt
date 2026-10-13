package com.xw.sillytavernpocket.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xw.sillytavernpocket.R

/**
 * 原生界面的唯一入口,所有原生界面操作都集中在 ui 包内:
 * 开机页、入口页、主界面外壳(顶栏 + 酒馆页面 + 对话抽屉)、返回键处理。
 * 与酒馆页面的通信全部经由 [StBridge]。
 *
 * 层级(从下到上):酒馆页面 → 入口页 → 遮罩 → 抽屉。
 * 酒馆页面在后台一直加载,入口页盖在它上面;进入对话时隐藏入口页。
 */
class ShellUi(private val activity: Activity) {

    val root: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        applySafeAreaPadding()
    }

    private val boot = BootScreen(activity)
    private var bridge: StBridge? = null
    private var drawerOpen = false
    private var inChat = false
    private var theme = ShellTheme.FALLBACK

    private lateinit var topBar: LinearLayout
    private lateinit var menuButton: ImageView
    private lateinit var plusButton: ImageView
    private lateinit var homeScreen: HomeScreen
    private lateinit var homeView: View
    private lateinit var scrim: View
    private lateinit var drawerPanel: View
    private lateinit var chatDrawer: ChatDrawer

    /** 显示开机页,启动阶段和日志由 BootLog 驱动。 */
    fun showBoot() {
        root.removeAllViews()
        root.addView(boot.view, FrameLayout.LayoutParams(MATCH, MATCH))
        boot.start()
    }

    /** 服务就绪后进入主界面:酒馆页面在后台加载 [url],先显示入口页。 */
    fun showShell(url: String) {
        boot.stop()
        val web = StBridge(
            context = activity,
            onReady = { refreshAll() },
            onProblem = { msg -> toast(msg) },
        )
        bridge = web
        root.removeAllViews()
        buildShell(web.view)
        web.load(url)
        showHome()
    }

    fun onResume() {
        bridge?.onResume()
    }

    fun onDestroy() {
        boot.stop()
    }

    /**
     * 返回键:先关抽屉;在对话中则先回退酒馆页面,无处可退时回到入口页;
     * 在入口页时返回 false,交给系统(退出应用)。
     */
    fun onBackPressed(): Boolean {
        if (drawerOpen) {
            closeDrawer()
            return true
        }
        if (!inChat) return false
        val web = bridge
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            showHome()
        }
        return true
    }

    private fun buildShell(web: View) {
        menuButton = activity.iconView(R.drawable.ic_menu) { openDrawer() }
        plusButton = activity.iconView(R.drawable.ic_plus) { newChat() }
        topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(menuButton, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(plusButton, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        val chatScreen = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(MATCH, dp(48)))
            addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        homeScreen = HomeScreen(
            context = activity,
            onMenu = { openDrawer() },
            onNewChat = { newChat() },
        )
        homeView = homeScreen.view

        scrim = View(activity).apply {
            setBackgroundColor(0x80000000.toInt())
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        chatDrawer = ChatDrawer(
            context = activity,
            onNewChat = { newChat() },
            onPanel = { entry -> openPanel(entry.id) },
            onClose = { closeDrawer() },
        )
        drawerPanel = chatDrawer.view.apply { visibility = View.GONE }

        root.addView(chatScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(homeView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(drawerPanel, FrameLayout.LayoutParams(drawerWidth(), MATCH, Gravity.START))
        applyTheme(theme)
    }

    /** 显示入口页(酒馆页面仍在后台)。 */
    private fun showHome() {
        inChat = false
        closeDrawer()
        bridge?.closePanels()
        homeView.visibility = View.VISIBLE
    }

    /** 进入对话:隐藏入口页,显示酒馆页面。 */
    private fun enterChat() {
        inChat = true
        homeView.visibility = View.GONE
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        refreshTheme()
        refreshPanels()
        scrim.visibility = View.VISIBLE
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.translationX = -drawerWidth().toFloat()
        drawerPanel.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawerPanel.animate().translationX(-drawerWidth().toFloat()).setDuration(200).withEndAction {
            drawerPanel.visibility = View.GONE
            scrim.visibility = View.GONE
        }.start()
    }

    private fun refreshAll() {
        refreshTheme()
    }

    /** 向酒馆页面读取当前主题颜色;失败时保持现有颜色。 */
    private fun refreshTheme() {
        bridge?.fetchTheme { r -> r.onSuccess { applyTheme(it) } }
    }

    private fun applyTheme(t: ShellTheme) {
        theme = t
        root.setBackgroundColor(t.background)
        if (::topBar.isInitialized) {
            topBar.setBackgroundColor(t.background)
            menuButton.tint(t.text)
            plusButton.tint(t.text)
            homeScreen.applyTheme(t)
            chatDrawer.applyTheme(t)
        }
    }

    private fun newChat() {
        bridge?.newChat { r ->
            r.onSuccess {
                enterChat()
                closeDrawer()
            }.onFailure { toast("新建失败:${it.message}") }
        }
    }

    private fun openPanel(domId: String) {
        closeDrawer()
        enterChat()
        bridge?.openPanel(domId) { r ->
            r.onFailure { toast("打开失败:${it.message}") }
        }
    }

    /** 读取酒馆顶部的全部按钮,显示在抽屉菜单里。 */
    private fun refreshPanels() {
        bridge?.listPanels { r ->
            r.onSuccess {
                chatDrawer.setPanels(it)
                val params = drawerPanel.layoutParams
                params.width = chatDrawer.preferredWidth(it)
                drawerPanel.layoutParams = params
                drawerPanel.requestLayout()
            }.onFailure { chatDrawer.setStatus("读取菜单失败:${it.message}") }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /** 初始宽度;读取菜单后会按最长标题动态调整。 */
    private fun drawerWidth(): Int = dp(240)

    private fun dp(v: Int): Int = activity.dp(v)
}
