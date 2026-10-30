package com.wallkiosk.console.ui.theme

import androidx.compose.ui.graphics.Color
import com.wallkiosk.console.data.ThemeIds

/**
 * 拟态风格需要三种颜色：底色、左上高光、右下暗影。
 * 底色和三个阴影必须成组调，单改一个会立刻"脏"。
 */
data class NeuPalette(
    val id: String,
    val label: String,
    val background: Color,
    val light: Color,
    val dark: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val textDim: Color,
    val danger: Color,
    val isDark: Boolean,
)

object Palettes {

    val graphite = NeuPalette(
        id = ThemeIds.GRAPHITE,
        label = "石墨",
        background = Color(0xFF2B2F36),
        light = Color(0xFF363B44),
        dark = Color(0xFF1E2127),
        accent = Color(0xFF5B8DEF),
        onAccent = Color(0xFF0B1018),
        text = Color(0xFFE9ECF2),
        textDim = Color(0xFF9AA3B2),
        danger = Color(0xFFE5484D),
        isDark = true,
    )

    val midnight = NeuPalette(
        id = ThemeIds.MIDNIGHT,
        label = "午夜",
        background = Color(0xFF1B2230),
        light = Color(0xFF252E40),
        dark = Color(0xFF10151D),
        accent = Color(0xFF3FD0C9),
        onAccent = Color(0xFF05201E),
        text = Color(0xFFE6ECF5),
        textDim = Color(0xFF8C99AD),
        danger = Color(0xFFE5484D),
        isDark = true,
    )

    val porcelain = NeuPalette(
        id = ThemeIds.PORCELAIN,
        label = "瓷白",
        background = Color(0xFFE4E7EC),
        light = Color(0xFFFFFFFF),
        dark = Color(0xFFBCC2CC),
        accent = Color(0xFF3B6FD4),
        onAccent = Color(0xFFFFFFFF),
        text = Color(0xFF2B303A),
        textDim = Color(0xFF6C7480),
        danger = Color(0xFFD1373C),
        isDark = false,
    )

    val sand = NeuPalette(
        id = ThemeIds.SAND,
        label = "沙褐",
        background = Color(0xFFE9E1D5),
        light = Color(0xFFFFFAF2),
        dark = Color(0xFFC7BBA9),
        accent = Color(0xFFC1703A),
        onAccent = Color(0xFFFFF8F0),
        text = Color(0xFF3A332B),
        textDim = Color(0xFF7A6E60),
        danger = Color(0xFFC0392B),
        isDark = false,
    )

    val all: List<NeuPalette> = listOf(graphite, midnight, porcelain, sand)

    /** 未知 id 退回默认，不让一个坏配置把界面搞成黑的 */
    fun byId(id: String): NeuPalette = all.firstOrNull { it.id == id } ?: graphite

    fun ids(): List<String> = all.map { it.id }
}
