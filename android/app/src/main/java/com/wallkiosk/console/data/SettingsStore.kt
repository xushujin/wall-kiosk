package com.wallkiosk.console.data

import android.content.Context
import java.io.File

/**
 * 设置的落盘。
 *
 * 为什么用文件而不是 SharedPreferences：schema 要能显式迁移，
 * 文件里带 version 字段，升级时读旧文件、变换、写回，全程可测、可回滚。
 * SharedPreferences 只有"有/没有"两个状态，做不了这件事。
 *
 * 旧版（v1）确实用的 SharedPreferences，所以这里保留了从 prefs 迁移的路径：
 * 读到旧数据 → 转成 v2 写文件 → **不清空旧 prefs**（万一要回滚还有）。
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    /** 磁盘上的版本比当前 App 还新（降级安装），此时禁止回写，避免冲掉新字段 */
    var newerSchema: Boolean = false
        private set

    fun load(): AppSettings {
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrElse { "" }
            val decoded = SettingsCodec.decode(text)
            newerSchema = decoded.newerThanApp
            return decoded.settings
        }

        val legacy = readLegacyPrefs()
        if (legacy.isNotEmpty()) {
            val migrated = SettingsMigrations.fromLegacyPrefs(legacy)
            newerSchema = false
            save(migrated)
            return migrated
        }

        return AppSettings()
    }

    fun save(settings: AppSettings) {
        if (newerSchema) return

        // 先写临时文件再 rename：写到一半断电不会留下半个文件
        val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(SettingsCodec.encode(settings))
        if (file.exists() && !file.delete()) {
            // 删不掉就退化成直接覆盖，总比丢设置好
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
            return
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun readLegacyPrefs(): Map<String, String?> {
        val prefs = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (!prefs.all.containsKey("repo")) return emptyMap()

        return mapOf(
            "provider" to prefs.getString("provider", null),
            "baseUrl" to prefs.getString("baseUrl", null),
            "repo" to prefs.getString("repo", null),
            "branch" to prefs.getString("branch", null),
            "token" to prefs.getString("token", null),
        )
    }

    private companion object {
        const val FILE_NAME = "settings.conf"
        const val LEGACY_PREFS = "wall-console"
    }
}
