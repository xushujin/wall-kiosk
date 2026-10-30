package com.wallkiosk.console.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Release 的 body 是 Markdown。更新对话框用的是纯文本控件，
 * 不处理的话用户会看到字面的 `**` 和 `[文字](链接)`。
 */
class ReleaseNotesTest {

    @Test
    fun `去掉加粗和行内代码`() {
        assertEquals(
            "Full Changelog: 修好了",
            ReleaseNotes.plain("**Full Changelog**: `修好了`"),
        )
    }

    @Test
    fun `链接保留文字并附上地址`() {
        assertEquals(
            "看这里（https://example.com/a）",
            ReleaseNotes.plain("[看这里](https://example.com/a)"),
        )
    }

    @Test
    fun `链接文字和地址相同时只留一份`() {
        assertEquals(
            "https://example.com/a",
            ReleaseNotes.plain("[https://example.com/a](https://example.com/a)"),
        )
    }

    @Test
    fun `标题去掉井号`() {
        assertEquals("What's Changed", ReleaseNotes.plain("## What's Changed"))
    }

    @Test
    fun `列表项换成点`() {
        assertEquals("· 甲\n· 乙", ReleaseNotes.plain("* 甲\n* 乙"))
        assertEquals("· 丙", ReleaseNotes.plain("- 丙"))
    }

    @Test
    fun `横线不会被当成列表项`() {
        // "---" 后面没有空格，不该被替换成 "· -"
        assertEquals("---", ReleaseNotes.plain("---"))
    }

    @Test
    fun `压掉多余空行`() {
        assertEquals("a\n\nb", ReleaseNotes.plain("a\n\n\n\nb"))
    }

    @Test
    fun `真实 GitHub 自动生成的说明`() {
        val raw = """
            ## What's Changed
            * 修 Gitee 连接失败 by @xushujin in https://github.com/xushujin/wall-kiosk/pull/1
            * 更新说明不再显示 Markdown 符号 by @xushujin in https://github.com/xushujin/wall-kiosk/pull/2

            **Full Changelog**: https://github.com/xushujin/wall-kiosk/compare/v1.1.0...v1.1.1
        """.trimIndent()

        val expected = """
            What's Changed
            · 修 Gitee 连接失败 by @xushujin in https://github.com/xushujin/wall-kiosk/pull/1
            · 更新说明不再显示 Markdown 符号 by @xushujin in https://github.com/xushujin/wall-kiosk/pull/2

            Full Changelog: https://github.com/xushujin/wall-kiosk/compare/v1.1.0...v1.1.1
        """.trimIndent()

        assertEquals(expected, ReleaseNotes.plain(raw))
    }

    @Test
    fun `空输入和纯空白`() {
        assertEquals("", ReleaseNotes.plain(""))
        assertEquals("", ReleaseNotes.plain("   \n\n  "))
    }

    @Test
    fun `CRLF 也能处理`() {
        assertEquals("· 甲\n· 乙", ReleaseNotes.plain("* 甲\r\n* 乙"))
    }
}
