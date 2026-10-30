package com.wallkiosk.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wallkiosk.console.data.update.UpdateSources

class SettingsCodecTest {

    private val sample = AppSettings(
        themeId = ThemeIds.MIDNIGHT,
        repo = RepoConfig(
            provider = Provider.GITLAB,
            baseUrl = "https://git.example.com/api/v4",
            repo = "group/sub/proj",
            branch = "release",
            token = "glpat-abc123",
        ),
    )

    @Test
    fun `编码后能原样读回来`() {
        val text = SettingsCodec.encode(sample)
        val decoded = SettingsCodec.decode(text)

        assertEquals(SettingsCodec.CURRENT_VERSION, decoded.version)
        assertFalse(decoded.newerThanApp)
        assertEquals(sample, decoded.settings)
    }

    @Test
    fun `文件头带版本号`() {
        val text = SettingsCodec.encode(sample)
        assertTrue(text.contains("version=${SettingsCodec.CURRENT_VERSION}"))
        assertTrue(text.contains("theme=${ThemeIds.MIDNIGHT}"))
        assertTrue(text.startsWith("#"))
    }

    @Test
    fun `没有主题字段的旧文件补默认主题`() {
        val text = """
            provider=GITHUB
            baseUrl=https://api.github.com
            repo=o/r
            branch=main
            token=t
        """.trimIndent()

        val decoded = SettingsCodec.decode(text)
        assertEquals(1, decoded.version)
        assertEquals(ThemeIds.DEFAULT, decoded.settings.themeId)
        assertEquals("o/r", decoded.settings.repo?.repo)
    }

    @Test
    fun `未知主题 id 退回默认`() {
        val text = SettingsCodec.encode(sample).replace("theme=${ThemeIds.MIDNIGHT}", "theme=nope")
        assertEquals(ThemeIds.DEFAULT, SettingsCodec.decode(text).settings.themeId)
    }

    @Test
    fun `反斜杠和换行会被转义`() {
        val tricky = sample.copy(
            repo = sample.repo?.copy(token = "a\\b\nc")
        )
        val text = SettingsCodec.encode(tricky)

        // 一个字段只能占一行，否则会把后面的 key 冲掉
        val tokenLines = text.split("\n").filter { it.startsWith("token=") }
        assertEquals(1, tokenLines.size)

        assertEquals("a\\b\nc", SettingsCodec.decode(text).settings.repo?.token)
    }

    @Test
    fun `没有仓库时不写仓库字段`() {
        val text = SettingsCodec.encode(AppSettings(themeId = ThemeIds.SAND))
        assertFalse(text.contains("provider="))
        assertNull(SettingsCodec.decode(text).settings.repo)
    }

    @Test
    fun `版本比 App 新时标记为只读`() {
        val text = "version=99\ntheme=sand\n"
        val decoded = SettingsCodec.decode(text)
        assertTrue(decoded.newerThanApp)
        assertEquals(99, decoded.version)
        // 仍然尽力解析已知字段，而不是把用户设置丢掉
        assertEquals(ThemeIds.SAND, decoded.settings.themeId)
    }

    @Test
    fun `注释和空行不影响解析`() {
        val text = "# 注释\n\n   \nversion=2\ntheme=porcelain\n"
        assertEquals(ThemeIds.PORCELAIN, SettingsCodec.decode(text).settings.themeId)
    }

    @Test
    fun `字段值里的等号不会被截断`() {
        // 只在第一个 = 处切分，token 里的 = 要原样保留（base64 结尾常见）
        val text = "version=2\ntheme=graphite\nrepo=o/r\ntoken=abc=def==\n"
        assertEquals("abc=def==", SettingsCodec.decode(text).settings.repo?.token)
    }

    @Test
    fun `每个平台都能存下来再读回去`() {
        // 枚举改名或加值时会在这里暴露出来，避免老用户设置读不回来
        for (provider in Provider.values()) {
            val s = AppSettings(
                themeId = ThemeIds.GRAPHITE,
                repo = RepoConfig(provider = provider, repo = "o/r", token = "t"),
            )
            val decoded = SettingsCodec.decode(SettingsCodec.encode(s))
            // JUnit 的三参数形式是 (message, expected, actual)，顺序不能反
            assertEquals("平台 $provider 往返失败", provider, decoded.settings.repo?.provider)
        }
    }

    @Test
    fun `Gitee 的默认 API 地址`() {
        assertEquals("https://gitee.com/api/v5", Provider.GITEE.defaultBaseUrl)
    }

    @Test
    fun `更新源能存下来再读回去`() {
        val s = AppSettings(updateRepo = "myorg/myapp")
        val decoded = SettingsCodec.decode(SettingsCodec.encode(s))
        assertEquals("myorg/myapp", decoded.settings.updateRepo)
    }

    @Test
    fun `v2 的老文件里没有更新源时补默认值`() {
        // 老版本写的文件：没有 updateRepo 字段
        val text = "version=2\ntheme=sand\nrepo=o/r\ntoken=t\n"
        val decoded = SettingsCodec.decode(text)

        // 文件版本号原样保留，只在内存里按 v3 的形状补字段
        assertEquals(2, decoded.version)
        assertEquals(UpdateSources.DEFAULT_REPO, decoded.settings.updateRepo)
        assertEquals(ThemeIds.SAND, decoded.settings.themeId)
        assertEquals("o/r", decoded.settings.repo?.repo)
    }

    @Test
    fun `写出来的文件带当前版本号`() {
        assertTrue(SettingsCodec.encode(sample).contains("version=3\n"))
        assertEquals(3, SettingsCodec.CURRENT_VERSION)
    }
}
