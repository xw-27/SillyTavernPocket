package com.xw.sillytavernpocket.ui

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生外壳使用的颜色。来源是酒馆页面的实际主题(由桥接插件读取),不在原生代码里写死配色。
 * 读取失败或尚未就绪时使用 [FALLBACK]。
 */
internal data class ShellTheme(
    val background: Int,
    val text: Int,
    val accent: Int,
) {
    /** 次要文字:正文颜色与背景按 60% 混合 */
    val subText: Int get() = blend(text, background, 0.6f)

    companion object {
        /** 与酒馆默认深色主题接近的兜底值 */
        val FALLBACK = ShellTheme(
            background = Color.rgb(0x1E, 0x1E, 0x1E),
            text = Color.rgb(0xEE, 0xEE, 0xEE),
            accent = Color.rgb(0x6C, 0xB4, 0xFF),
        )

        /**
         * 解析桥接插件返回的 {"background":[r,g,b],"text":[r,g,b],"accent":[r,g,b]}。
         * 缺少任一字段时返回 null。
         */
        fun fromJson(json: String): ShellTheme? = runCatching {
            val o = JSONObject(json)
            ShellTheme(
                background = rgbOf(o.getJSONArray("background")),
                text = rgbOf(o.getJSONArray("text")),
                accent = rgbOf(o.getJSONArray("accent")),
            )
        }.getOrNull()

        private fun rgbOf(arr: JSONArray): Int =
            Color.rgb(arr.getInt(0), arr.getInt(1), arr.getInt(2))

        private fun blend(fg: Int, bg: Int, fgRatio: Float): Int {
            fun mix(a: Int, b: Int) = (a * fgRatio + b * (1 - fgRatio)).toInt()
            return Color.rgb(
                mix(Color.red(fg), Color.red(bg)),
                mix(Color.green(fg), Color.green(bg)),
                mix(Color.blue(fg), Color.blue(bg)),
            )
        }
    }
}
