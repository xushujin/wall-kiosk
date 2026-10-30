package com.wallkiosk.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置解析 / 行级编辑 / 校验。
 *
 * 这套规则同时存在于 bin/wall.sh、web/index.html 和这里，仓库根目录的
 * test/console.mjs 会拿同一批用例去跑真实的 wall.sh --check 做交叉验证。
 * 改这里的期望值之前先想清楚另外两处要不要跟着改。
 */
class ScreensConfTest {

    @Test
    fun `解析出注释 空行 和配置行`() {
        val text = listOf(
            "# 头注释",
            "",
            "DP-1|https://a.example.com/x|1.0|0",
            "  HDMI-1 | https://b.example.com/y | 0.9 | 120  ",
            "# 中间注释",
            "DP-2|https://c.example.com/z||",
        ).joinToString("\n")

        val rows = ScreensConf.parse(text)
        assertEquals(6, rows.size)
        assertTrue(rows[0] is ConfRow.Comment)
        assertTrue(rows[1] is ConfRow.Blank)

        val entries = ScreensConf.entries(text)
        assertEquals(3, entries.size)
        assertEquals("HDMI-1", entries[1].iface)
        assertEquals("0.9", entries[1].zoom)
        assertEquals("120", entries[1].refresh)
        assertEquals("", entries[2].zoom)
    }

    @Test
    fun `CRLF 换行不会把回车带进字段`() {
        val text = "DP-1|https://a/x|1.0|0\r\nHDMI-1|https://b/y|1.0|0\r\n"
        val entries = ScreensConf.entries(text)
        assertEquals(2, entries.size)
        assertEquals("0", entries[0].refresh)
        assertEquals("https://b/y", entries[1].url)
    }

    @Test
    fun `序列化去掉尾部空字段`() {
        assertEquals(
            "DP-1|https://a/x",
            ScreensConf.format(ScreenEntry("DP-1", "https://a/x", "", "")),
        )
        assertEquals(
            "DP-1|https://a/x|0.9",
            ScreensConf.format(ScreenEntry("DP-1", "https://a/x", "0.9", "")),
        )
        assertEquals(
            "DP-1|https://a/x||120",
            ScreensConf.format(ScreenEntry("DP-1", "https://a/x", "", "120")),
        )
    }

    @Test
    fun `改一行时注释和顺序原样保留`() {
        val text = listOf(
            "# 这是头注释，不能丢",
            "",
            "DP-1|https://a/x|1.0|0",
            "# 这是 DP-2 的说明",
            "DP-2|https://c/z|1.0|0",
            "",
        ).joinToString("\n")

        val out = ScreensConf.applyEdit(
            text,
            "DP-1",
            ScreenEntry("DP-1", "https://a/NEW", "1.0", "0"),
        )

        val lines = out.split("\n")
        assertEquals("# 这是头注释，不能丢", lines[0])
        assertEquals("", lines[1])
        assertEquals("DP-1|https://a/NEW|1.0|0", lines[2])
        assertEquals("# 这是 DP-2 的说明", lines[3])
        assertEquals("DP-2|https://c/z|1.0|0", lines[4])
        assertEquals(2, ScreensConf.entries(out).size)
    }

    @Test
    fun `追加到末尾不会多出空行`() {
        val out = ScreensConf.applyEdit(
            "DP-1|https://a/x|1.0|0\n",
            null,
            ScreenEntry("HDMI-1", "https://b/y", "", ""),
        )
        assertEquals("DP-1|https://a/x|1.0|0\nHDMI-1|https://b/y\n", out)
    }

    @Test
    fun `删除只删那一行`() {
        val out = ScreensConf.applyEdit(
            "DP-1|https://a/x|1.0|0\nDP-2|https://c/z|1.0|0\n",
            "DP-2",
            null,
        )
        assertEquals("DP-1|https://a/x|1.0|0\n", out)
    }

    @Test
    fun `空文件可以追加第一条`() {
        val out = ScreensConf.applyEdit(
            "",
            null,
            ScreenEntry("DP-1", "https://a/x", "", ""),
        )
        assertEquals("DP-1|https://a/x\n", out)
    }

    @Test
    fun `接口名重复时只改第一条`() {
        val out = ScreensConf.applyEdit(
            "DP-1|https://a/1|1.0|0\nDP-1|https://a/2|1.0|0\n",
            "DP-1",
            ScreenEntry("DP-1", "https://a/3", "", ""),
        )
        val lines = out.split("\n")
        assertEquals("DP-1|https://a/3", lines[0])
        assertEquals("DP-1|https://a/2|1.0|0", lines[1])
    }

    @Test
    fun `合法配置没有错误`() {
        assertTrue(ScreensConf.validate("DP-1|https://a/x|1.0|0").isEmpty())
        assertTrue(ScreensConf.validate("DP-1|http://a/x").isEmpty())
        assertTrue(ScreensConf.validate("DP-1|https://a/x|0.9|120").isEmpty())
    }

    @Test
    fun `缺地址被拦下`() {
        val errors = ScreensConf.validate("DP-1|")
        assertTrue(errors.any { it.message.contains("缺少地址") })
    }

    @Test
    fun `非 http(s) 地址被拦下`() {
        assertTrue(ScreensConf.validate("DP-1|ftp://a/x").any { it.message.contains("不是 http(s)") })
        assertTrue(ScreensConf.validate("DP-1|a/x").any { it.message.contains("不是 http(s)") })
    }

    @Test
    fun `缩放非数字被拦下`() {
        assertTrue(ScreensConf.validate("DP-1|https://a/x|abc").any { it.message.contains("缩放不是数字") })
    }

    @Test
    fun `定时重启非整数被拦下`() {
        assertTrue(
            ScreensConf.validate("DP-1|https://a/x|1.0|两小时")
                .any { it.message.contains("不是整数") }
        )
    }

    @Test
    fun `接口名重复被拦下`() {
        val errors = ScreensConf.validate("DP-1|https://a/1|1.0|0\nDP-1|https://a/2|1.0|0")
        assertTrue(errors.any { it.message.contains("重复") })
    }

    @Test
    fun `一条配置都没有时被拦下`() {
        assertTrue(ScreensConf.validate("# 只有注释\n\n").any { it.message.contains("没有任何屏幕配置") })
    }

    @Test
    fun `新建主机的模板本身是合法的`() {
        val text = ScreensConf.newHostTemplate("lobby", ScreenEntry("DP-1", "https://a/x", "", ""))
        assertTrue(ScreensConf.validate(text).isEmpty())
        assertEquals(1, ScreensConf.entries(text).size)
        assertTrue(text.startsWith("# lobby"))
    }
}
