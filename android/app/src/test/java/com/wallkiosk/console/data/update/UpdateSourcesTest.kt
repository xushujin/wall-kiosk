package com.wallkiosk.console.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSourcesTest {

    @Test
    fun `owner 斜杠 repo 会依次给出 GitHub 和 Gitee`() {
        val urls = UpdateSources.urls("xushujin/wall-kiosk")
        assertEquals(2, urls.size)
        assertEquals(
            "https://api.github.com/repos/xushujin/wall-kiosk/releases/latest",
            urls[0],
        )
        assertEquals(
            "https://gitee.com/api/v5/repos/xushujin/wall-kiosk/releases/latest",
            urls[1],
        )
    }

    @Test
    fun `完整地址原样使用`() {
        val custom = "https://git.example.com/api/v1/repos/o/r/releases/latest"
        assertEquals(listOf(custom), UpdateSources.urls(custom))
        assertEquals(listOf(custom), UpdateSources.urls("  $custom  "))
    }

    @Test
    fun `留空时用默认仓库`() {
        val urls = UpdateSources.urls("")
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains(UpdateSources.DEFAULT_REPO))
    }

    @Test
    fun `首尾多余的斜杠会被去掉`() {
        assertEquals(
            UpdateSources.urls("o/r"),
            UpdateSources.urls("/o/r/"),
        )
    }

    @Test
    fun `sourceOf 认得出两个平台`() {
        assertEquals("GitHub", UpdateSources.sourceOf("https://api.github.com/repos/o/r/releases/latest"))
        assertEquals("Gitee", UpdateSources.sourceOf("https://gitee.com/api/v5/repos/o/r/releases/latest"))
        assertEquals("自定义源", UpdateSources.sourceOf("https://git.example.com/api/v1/repos/o/r/releases/latest"))
    }
}
