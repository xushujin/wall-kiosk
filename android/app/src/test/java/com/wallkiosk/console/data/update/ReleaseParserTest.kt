package com.wallkiosk.console.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布信息的解析测试。
 *
 * 下面 GitHub 那段是 2026-10-30 从
 * https://api.github.com/repos/xushujin/wall-kiosk/releases/latest 真实抓下来的响应
 * （只保留用到的字段），不是手写的样例——真实响应里多出来的 url / upload_url /
 * author 等字段不影响解析，但少了就说明字段名猜错了。
 */
class ReleaseParserTest {

    private val gitHubJson = """
        {
          "tag_name": "v1.0.1",
          "name": "v1.0.1",
          "prerelease": false,
          "draft": false,
          "body": "**Full Changelog**: https://github.com/xushujin/wall-kiosk/compare/v1.0.0...v1.0.1",
          "assets": [
            {
              "name": "wall-kiosk-console-1.0.1.apk",
              "size": 986292,
              "browser_download_url": "https://github.com/xushujin/wall-kiosk/releases/download/v1.0.1/wall-kiosk-console-1.0.1.apk",
              "content_type": "application/vnd.android.package-archive"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析真实的 GitHub 响应`() {
        val release = ReleaseParser.parse(gitHubJson, "GitHub")
        assertNotNull(release)
        release ?: return

        assertEquals("v1.0.1", release.tag)
        assertEquals("GitHub", release.source)
        assertEquals("wall-kiosk-console-1.0.1.apk", release.asset.name)
        assertEquals(986292L, release.asset.size)
        assertTrue(release.asset.url.endsWith("/v1.0.1/wall-kiosk-console-1.0.1.apk"))
        assertTrue(release.notes.contains("Full Changelog"))
    }

    @Test
    fun `Gitee 的 assets 可能是字符串数组`() {
        // Gitee 官方 swagger 把 Release.assets 声明成 array of string，
        // 实际返回的却是对象数组。两种都要能解析，否则换个平台就哑了。
        val json = """
            {
              "tag_name": "v1.1.0",
              "body": "修了点东西",
              "assets": [
                "https://gitee.com/o/r/releases/download/v1.1.0/wall-kiosk-console-1.1.0.apk"
              ]
            }
        """.trimIndent()

        val release = ReleaseParser.parse(json, "Gitee")
        assertNotNull(release)
        release ?: return

        assertEquals("v1.1.0", release.tag)
        assertEquals("wall-kiosk-console-1.1.0.apk", release.asset.name)
        // 字符串形式拿不到大小，保持 0，界面会显示"未知大小"
        assertEquals(0L, release.asset.size)
    }

    @Test
    fun `没有 apk 附件时解析不出结果`() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "assets": [
                { "name": "source.tar.gz", "size": 100, "browser_download_url": "https://x/source.tar.gz" }
              ]
            }
        """.trimIndent()
        assertNull(ReleaseParser.parse(json, "GitHub"))
    }

    @Test
    fun `没有 assets 字段时不崩`() {
        assertNull(ReleaseParser.parse("""{"tag_name":"v9.9.9"}""", "GitHub"))
    }

    @Test
    fun `不是 JSON 时返回 null 而不是抛异常`() {
        assertNull(ReleaseParser.parse("<html>502 Bad Gateway</html>", "GitHub"))
        assertNull(ReleaseParser.parse("", "GitHub"))
    }

    @Test
    fun `预发布版会被标记出来`() {
        val json = """
            {
              "tag_name": "v2.0.0-rc1",
              "prerelease": true,
              "assets": [
                { "name": "a.apk", "size": 1, "browser_download_url": "https://x/a.apk" }
              ]
            }
        """.trimIndent()
        val release = ReleaseParser.parse(json, "GitHub")
        assertNotNull(release)
        assertTrue(release?.prerelease == true)
    }

    @Test
    fun `没有 name 字段时从地址里取文件名`() {
        val json = """
            {
              "tag_name": "v1.0.0",
              "assets": [
                { "browser_download_url": "https://x/download/v1.0.0/app-release.apk?token=abc" }
              ]
            }
        """.trimIndent()
        assertEquals("app-release.apk", ReleaseParser.parse(json, "GitHub")?.asset?.name)
    }

    @Test
    fun `多附件时优先选 apk`() {
        val assets = listOf(
            ReleaseAsset("notes.txt", "https://x/notes.txt", 10),
            ReleaseAsset("app-release.apk", "https://x/app-release.apk", 20),
        )
        assertEquals("app-release.apk", ReleaseParser.pickApk(assets)?.name)
    }

    @Test
    fun `nameFromUrl 去掉查询串`() {
        assertEquals("a.apk", ReleaseParser.nameFromUrl("https://x/y/a.apk?k=v"))
        assertEquals("a.apk", ReleaseParser.nameFromUrl("https://x/y/a.apk"))
        assertEquals("download", ReleaseParser.nameFromUrl("https://x/y/"))
    }
}
