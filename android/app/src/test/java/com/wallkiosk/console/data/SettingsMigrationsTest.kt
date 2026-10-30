package com.wallkiosk.console.data

import com.wallkiosk.console.data.update.UpdateSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 迁移链的测试。
 *
 * 规范要求「schema 变更必须写显式迁移，禁止 destructive migration」，
 * 所以这里既测"旧数据能读出来"，也测"读不出来的时候不会把数据清空"。
 */
class SettingsMigrationsTest {

    @Test
    fun `从旧版 SharedPreferences 迁移出仓库设置`() {
        val prefs = mapOf(
            "provider" to "GITEA",
            "baseUrl" to "https://git.example.com/api/v1",
            "repo" to "ops/wall-config",
            "branch" to "prod",
            "token" to "tok",
        )

        val settings = SettingsMigrations.fromLegacyPrefs(prefs)

        assertEquals(ThemeIds.DEFAULT, settings.themeId)
        val repo = settings.repo
        assertNotNull(repo)
        assertEquals(Provider.GITEA, repo?.provider)
        assertEquals("https://git.example.com/api/v1", repo?.baseUrl)
        assertEquals("ops/wall-config", repo?.repo)
        assertEquals("prod", repo?.branch)
        assertEquals("tok", repo?.token)
    }

    @Test
    fun `旧数据里没有仓库时迁移结果也没有仓库`() {
        val settings = SettingsMigrations.fromLegacyPrefs(mapOf("theme" to ThemeIds.SAND))
        assertNull(settings.repo)
        assertEquals(ThemeIds.SAND, settings.themeId)
    }

    @Test
    fun `旧数据缺 token 时不构造半个仓库`() {
        val prefs = mapOf("repo" to "o/r", "baseUrl" to "https://api.github.com")
        assertNull(SettingsMigrations.fromLegacyPrefs(prefs).repo)
    }

    @Test
    fun `v1 迁移到 v2 会补上主题`() {
        val v1 = mapOf(
            "provider" to "GITHUB",
            "baseUrl" to "https://api.github.com",
            "repo" to "o/r",
            "token" to "t",
        )

        val settings = SettingsMigrations.migrate(v1, fromVersion = 1)

        assertEquals(ThemeIds.DEFAULT, settings.themeId)
        assertEquals("o/r", settings.repo?.repo)
    }

    @Test
    fun `迁移不会丢掉已有主题`() {
        val v1 = mapOf("theme" to ThemeIds.PORCELAIN, "repo" to "o/r", "token" to "t")
        assertEquals(ThemeIds.PORCELAIN, SettingsMigrations.migrate(v1, fromVersion = 1).themeId)
    }

    @Test
    fun `版本号比当前新时不做任何改写`() {
        // 降级安装：文件是 v99 写的，App 只认识 v2。
        // 正确做法是原样读出已知字段，并且不回写（由 SettingsStore 拦），
        // 而不是把文件当成坏的清掉。
        val future = mapOf("theme" to ThemeIds.SAND, "repo" to "o/r", "token" to "t")
        val settings = SettingsMigrations.migrate(future, fromVersion = 99)

        assertEquals(ThemeIds.SAND, settings.themeId)
        assertEquals("o/r", settings.repo?.repo)
    }

    @Test
    fun `未知 provider 退回 GitHub`() {
        val prefs = mapOf("provider" to "BITBUCKET", "repo" to "o/r", "token" to "t")
        assertEquals(Provider.GITHUB, SettingsMigrations.fromLegacyPrefs(prefs).repo?.provider)
    }

    @Test
    fun `缺 branch 时用 main`() {
        val prefs = mapOf("repo" to "o/r", "token" to "t")
        assertEquals("main", SettingsMigrations.fromLegacyPrefs(prefs).repo?.branch)
    }

    @Test
    fun `缺 baseUrl 时用平台的默认值`() {
        val prefs = mapOf("provider" to "GITHUB", "repo" to "o/r", "token" to "t")
        assertEquals(
            Provider.GITHUB.defaultBaseUrl,
            SettingsMigrations.fromLegacyPrefs(prefs).repo?.baseUrl,
        )
    }

    @Test
    fun `v2 迁移到 v3 补上默认更新源`() {
        val v2 = mapOf("theme" to ThemeIds.SAND, "repo" to "o/r", "token" to "t")
        val settings = SettingsMigrations.migrate(v2, fromVersion = 2)

        assertEquals(UpdateSources.DEFAULT_REPO, settings.updateRepo)
        // 迁移只补新字段，老字段一个都不能动
        assertEquals(ThemeIds.SAND, settings.themeId)
        assertEquals("o/r", settings.repo?.repo)
    }

    @Test
    fun `已经填过更新源时迁移不覆盖`() {
        val v2 = mapOf("theme" to ThemeIds.SAND, "updateRepo" to "mine/fork")
        assertEquals("mine/fork", SettingsMigrations.migrate(v2, fromVersion = 2).updateRepo)
    }

    @Test
    fun `v1 一路迁到 v3，两步都生效`() {
        val v1 = mapOf("repo" to "o/r", "token" to "t")
        val settings = SettingsMigrations.migrate(v1, fromVersion = 1)

        assertEquals(ThemeIds.DEFAULT, settings.themeId)
        assertEquals(UpdateSources.DEFAULT_REPO, settings.updateRepo)
        assertEquals("o/r", settings.repo?.repo)
    }
}
