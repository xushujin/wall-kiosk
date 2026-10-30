package com.wallkiosk.console.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal const val UPDATE_USER_AGENT = "wall-kiosk-console-android"

class UpdateException(message: String) : Exception(message)

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class LatestRelease(
    val tag: String,
    val notes: String,
    val asset: ReleaseAsset,
    val source: String,
    val prerelease: Boolean = false,
)

/**
 * 更新源地址的解析。
 *
 * 一个字段支持两种写法，都是为了少让用户填东西：
 *   - owner/repo    → 依次试 GitHub 和 Gitee（国内直连 GitHub 常常很慢）
 *   - http(s)://... → 当成完整的 releases/latest 接口地址，自建服务用这种
 */
object UpdateSources {

    const val DEFAULT_REPO = "xushujin/wall-kiosk"

    fun urls(value: String): List<String> {
        val v = value.trim()
        if (v.isEmpty()) return urls(DEFAULT_REPO)

        if (v.startsWith("http://") || v.startsWith("https://")) {
            return listOf(v)
        }

        val repo = v.trim('/').trim()
        if (repo.isEmpty()) return urls(DEFAULT_REPO)

        return listOf(
            "https://api.github.com/repos/$repo/releases/latest",
            "https://gitee.com/api/v5/repos/$repo/releases/latest",
        )
    }

    fun sourceOf(url: String): String = when {
        url.contains("api.github.com") -> "GitHub"
        url.contains("gitee.com") -> "Gitee"
        else -> "自定义源"
    }
}

/**
 * 发布信息的解析。
 *
 * GitHub 和 Gitee 的 release 对象字段名基本一致（tag_name / body / assets），
 * 但 Gitee 的官方 swagger 把 assets 声明成"字符串数组"，而实际返回的是对象数组。
 * 两种都兜住，免得哪天换了实现就解析不出来。
 */
object ReleaseParser {

    fun parse(json: String, source: String): LatestRelease? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null

        val tag = obj.optString("tag_name").ifBlank { obj.optString("tag") }
        if (tag.isBlank()) return null

        val asset = pickApk(assetsOf(obj)) ?: return null

        return LatestRelease(
            tag = tag,
            notes = obj.optString("body").trim(),
            asset = asset,
            source = source,
            prerelease = obj.optBoolean("prerelease", false) || obj.optBoolean("draft", false),
        )
    }

    fun assetsOf(obj: JSONObject): List<ReleaseAsset> {
        val arr: JSONArray = obj.optJSONArray("assets") ?: return emptyList()
        val out = ArrayList<ReleaseAsset>(arr.length())
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is JSONObject -> {
                    val url = item.optString("browser_download_url")
                        .ifBlank { item.optString("download_url") }
                    if (url.isBlank()) continue
                    out.add(
                        ReleaseAsset(
                            name = item.optString("name").ifBlank { nameFromUrl(url) },
                            url = url,
                            size = item.optLong("size", 0L),
                        )
                    )
                }
                // Gitee swagger 声明的形状：数组元素直接是 URL 字符串
                is String -> if (item.isNotBlank()) {
                    out.add(ReleaseAsset(nameFromUrl(item), item, 0L))
                }
            }
        }
        return out
    }

    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    fun nameFromUrl(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "download" }
}

/**
 * 查最新版本。
 *
 * 按 [UpdateSources.urls] 给出的顺序逐个试，第一个拿到可下载 APK 的就用。
 * 全部失败才抛异常，异常里带上每一源各自的失败原因——排查时不用猜是哪个环节断的。
 */
class UpdateChecker {

    suspend fun latest(updateRepo: String): LatestRelease = withContext(Dispatchers.IO) {
        val failures = ArrayList<String>()

        for (url in UpdateSources.urls(updateRepo)) {
            val source = UpdateSources.sourceOf(url)
            try {
                val release = ReleaseParser.parse(fetch(url), source)
                when {
                    release == null -> failures.add("$source：这个版本没有可下载的 APK 附件")
                    release.prerelease ->
                        failures.add("$source：最新是预发布版 ${release.tag}，不自动更新")
                    else -> return@withContext release
                }
            } catch (e: Exception) {
                failures.add("$source：${e.message ?: "请求失败"}")
            }
        }

        throw UpdateException(failures.joinToString("；").ifBlank { "没有可用的更新源" })
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", UPDATE_USER_AGENT)

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) throw UpdateException(describe(code))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int): String = when (code) {
        401 -> "没有权限（HTTP 401）"
        403 -> "被限流或没有权限（HTTP 403）"
        404 -> "没有发布版本（HTTP 404）"
        else -> "HTTP $code"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
