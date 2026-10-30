package com.wallkiosk.console.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalPalette = staticCompositionLocalOf { Palettes.graphite }

object NeuDims {
    /** 阴影偏移和模糊半径，整套组件共用，改这里等于整体调"凸起高度" */
    val shadowOffset = 5.dp
    val shadowBlur = 9.dp
    val shadowSteps = 8

    val corner = 18.dp
    val cornerSmall = 12.dp
    val gap = 14.dp
    val screenPadding = 16.dp
}

object NeuType {
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val bodyStrong = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val tiny = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp)
    val monoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

/**
 * 主题切换的落点：整棵树从这里拿配色。
 * 主题 id 存在设置里（唯一来源），这里只负责 id → 配色。
 */
@Composable
fun NeuTheme(themeId: String, content: @Composable () -> Unit) {
    val palette = Palettes.byId(themeId)
    CompositionLocalProvider(LocalPalette provides palette, content = content)
}
