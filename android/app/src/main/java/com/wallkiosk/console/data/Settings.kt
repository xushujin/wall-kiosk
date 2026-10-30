package com.wallkiosk.console.data

import com.wallkiosk.console.data.update.UpdateSources

/**
 * 主题只存 id。具体配色在 ui/theme/Palettes.kt 里，两边用同一组常量，
 * 由 PalettesTest 保证「每个 id 都有对应配色」。
 */
object ThemeIds {
    const val GRAPHITE = "graphite"
    const val MIDNIGHT = "midnight"
    const val PORCELAIN = "porcelain"
    const val SAND = "sand"

    const val DEFAULT = GRAPHITE

    val ALL = listOf(GRAPHITE, MIDNIGHT, PORCELAIN, SAND)

    fun isValid(id: String): Boolean = ALL.contains(id)
}

/**
 * 整个 App 只有这一个设置对象：主题 + 仓库。
 *
 * 「同一个设置只有一套实现」——主题不是 UI 层另存的 state，
 * 它就是这里的 themeId，界面通过 CompositionLocal 读出来。
 */
data class AppSettings(
    val themeId: String = ThemeIds.DEFAULT,
    val repo: RepoConfig? = null,
    /** 从哪查 App 自己的新版本：owner/repo 或完整的 releases/latest 地址 */
    val updateRepo: String = UpdateSources.DEFAULT_REPO,
)

data class DecodedSettings(
    val version: Int,
    val settings: AppSettings,
    /** 文件版本比当前 App 还新，说明是降级安装，此时不允许回写 */
    val newerThanApp: Boolean,
)

/**
 * 设置文件的编解码。用行式 key=value 而不是 JSON：
 * 整个项目（screens.conf）就是这个风格，人可以直接改，也不用引 JSON 库。
 *
 *   # wall-kiosk console settings
 *   version=3
 *   theme=graphite
 *   updateRepo=xushujin/wall-kiosk
 *   provider=GITHUB
 *   baseUrl=https://api.github.com
 *   repo=your-org/wall-config
 *   branch=main
 *   token=...
 */
object SettingsCodec {

    /** 当前 schema 版本。加字段就要加版本号 + 迁移步骤，见 SettingsMigrations。 */
    const val CURRENT_VERSION = 3

    private const val HEADER = "# wall-kiosk console settings —— 由 App 生成，可以手改"

    fun encode(settings: AppSettings, version: Int = CURRENT_VERSION): String {
        val out = StringBuilder()
        out.append(HEADER).append('\n')
        out.append("version=").append(version).append('\n')
        out.append("theme=").append(escape(settings.themeId)).append('\n')
        out.append("updateRepo=").append(escape(settings.updateRepo)).append('\n')

        val repo = settings.repo
        if (repo != null) {
            out.append("provider=").append(repo.provider.name).append('\n')
            out.append("baseUrl=").append(escape(repo.baseUrl)).append('\n')
            out.append("repo=").append(escape(repo.repo)).append('\n')
            out.append("branch=").append(escape(repo.branch)).append('\n')
            out.append("token=").append(escape(repo.token)).append('\n')
        }
        return out.toString()
    }

    fun decode(text: String): DecodedSettings {
        val map = parse(text)
        val version = map["version"]?.toIntOrNull() ?: 1

        // 版本比 App 新就不做任何"猜"，只按已知字段尽力解析，并禁止回写
        val migrated = SettingsMigrations.migrate(map, version)

        return DecodedSettings(
            version = version,
            settings = migrated,
            newerThanApp = version > CURRENT_VERSION,
        )
    }

    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in text.split("\n")) {
            val raw = line.removeSuffix("\r")
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val eq = raw.indexOf('=')
            if (eq <= 0) continue

            val key = raw.substring(0, eq).trim()
            val value = raw.substring(eq + 1).trim()
            map[key] = unescape(value)
        }
        return map
    }

    private fun escape(v: String): String = v
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun unescape(v: String): String {
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (v[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/**
 * 显式迁移链。禁止 destructive migration：任何版本变更都必须能"读旧数据 → 变换 → 写新数据"，
 * 不允许"版本对不上就清空重来"。
 */
object SettingsMigrations {

    /** 一步迁移：把 from 版本的数据原地变换成 to 版本的形状 */
    private class Step(val from: Int, val to: Int, val apply: (MutableMap<String, String>) -> Unit)

    private val steps: List<Step> = listOf(
        // v1 → v2：v1 只有仓库设置（还是存在 SharedPreferences 里的），没有主题字段。
        // 迁移就是补一个默认主题，别的什么都不动。
        Step(1, 2) { map ->
            if (map["theme"].isNullOrBlank()) {
                map["theme"] = ThemeIds.DEFAULT
            }
        },
        // v2 → v3：加了应用内更新源。老用户没有这个字段，补默认值即可，
        // 原来的仓库配置和主题一个都不动。
        Step(2, 3) { map ->
            if (map["updateRepo"].isNullOrBlank()) {
                map["updateRepo"] = UpdateSources.DEFAULT_REPO
            }
        },
    )

    fun migrate(raw: Map<String, String>, fromVersion: Int): AppSettings {
        val map = LinkedHashMap(raw)
        var version = fromVersion

        // 只向前迁移。版本比 App 新时不动数据，由调用方决定只读。
        for (step in steps) {
            if (version == step.from && version < SettingsCodec.CURRENT_VERSION) {
                step.apply(map)
                version = step.to
            }
        }

        return build(map)
    }

    /**
     * 从旧版 SharedPreferences 迁移。旧版把仓库设置直接摊在 prefs 里，
     * 没有主题、没有 version。这里读出来转成 AppSettings，不清空旧数据（可回滚）。
     */
    fun fromLegacyPrefs(prefs: Map<String, String?>): AppSettings {
        val map = LinkedHashMap<String, String>()
        for ((k, v) in prefs) {
            if (v != null) map[k] = v
        }
        return migrate(map, fromVersion = 1)
    }

    private fun build(map: Map<String, String>): AppSettings {
        val themeId = map["theme"]?.takeIf { ThemeIds.isValid(it) } ?: ThemeIds.DEFAULT

        val provider = runCatching {
            Provider.valueOf(map["provider"] ?: Provider.GITHUB.name)
        }.getOrDefault(Provider.GITHUB)

        val repo = map["repo"]?.takeIf { it.isNotBlank() }
        val token = map["token"]?.takeIf { it.isNotBlank() }

        val repoConfig = if (repo != null && token != null) {
            RepoConfig(
                provider = provider,
                baseUrl = map["baseUrl"]?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl,
                repo = repo,
                branch = map["branch"]?.takeIf { it.isNotBlank() } ?: "main",
                token = token,
            )
        } else {
            null
        }

        return AppSettings(
            themeId = themeId,
            repo = repoConfig,
            updateRepo = map["updateRepo"]?.takeIf { it.isNotBlank() } ?: UpdateSources.DEFAULT_REPO,
        )
    }
}
