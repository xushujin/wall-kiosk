package com.wallkiosk.console.ui.theme

import com.wallkiosk.console.data.ThemeIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题的 id 定义在 data 层（ThemeIds），配色在 ui 层（Palettes）。
 * 这两边必须一一对应，否则用户选了某个主题会看到空白或退回默认。
 */
class PalettesTest {

    @Test
    fun `每个主题 id 都有对应配色`() {
        for (id in ThemeIds.ALL) {
            val palette = Palettes.all.firstOrNull { it.id == id }
            assertTrue("缺少配色：$id", palette != null)
        }
    }

    @Test
    fun `配色里的 id 都在 ThemeIds 里`() {
        for (palette in Palettes.all) {
            assertTrue("多出来的配色：${palette.id}", ThemeIds.isValid(palette.id))
        }
    }

    @Test
    fun `主题数量一致`() {
        assertEquals(ThemeIds.ALL.size, Palettes.all.size)
    }

    @Test
    fun `未知 id 退回默认而不是崩溃`() {
        assertEquals(ThemeIds.DEFAULT, Palettes.byId("does-not-exist").id)
    }

    @Test
    fun `默认主题存在`() {
        assertEquals(ThemeIds.DEFAULT, Palettes.byId(ThemeIds.DEFAULT).id)
    }

    @Test
    fun `每套配色都有名字`() {
        for (palette in Palettes.all) {
            assertTrue("配色 ${palette.id} 没有名字", palette.label.isNotBlank())
        }
    }

    @Test
    fun `深浅主题的背景色不同`() {
        val dark = Palettes.all.filter { it.isDark }
        val light = Palettes.all.filter { !it.isDark }
        assertTrue(dark.isNotEmpty())
        assertTrue(light.isNotEmpty())
        assertNotEquals(dark.first().background, light.first().background)
    }

    @Test
    fun `同一套配色里高光和暗影不是同一个颜色`() {
        for (palette in Palettes.all) {
            assertNotEquals("${palette.id} 的高光和暗影相同", palette.light, palette.dark)
        }
    }
}
