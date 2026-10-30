package com.wallkiosk.console.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

data class RemoteFile(val name: String, val path: String, val isDir: Boolean)
data class RemoteContent(val text: String, val version: String?)

/**
 * 配置仓库的读写。
 *
 * 用平台 REST API 而不是 git 协议：手机上跑 JGit 依赖重、处理不了 SSH key，
 * 而 HTTPS API 只要一个 token。
 *
 * HTTP 直接用 JDK 的 HttpURLConnection，不引 OkHttp —— 只用得到 GET/PUT/POST
 * 加几个请求头，没必要为此多一个第三方依赖。
 */
interface RepoApi {
    suspend fun listDir(path: String): List<RemoteFile>
    suspend fun readFile(path: String): RemoteContent?
    suspend fun writeFile(path: String, text: String, message: String, version: String?): String?

    companion object {
        fun of(config: RepoConfig): RepoApi = when (config.provider) {
            Provider.GITLAB -> GitLabApi(config)
            Provider.GITEE -> GiteeApi(config)
            else -> GitHubApi(config)
        }
    }
}

abstract class HttpRepoApi(protected val config: RepoConfig) : RepoApi {

    protected fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                throw RepoException(code, describe(code, text))
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int, body: String): String {
        val detail = runCatching {
            val obj = JSONObject(body)
            obj.optString("message").ifBlank { obj.optString("error") }
        }.getOrDefault("")

        return when {
            detail.isNotBlank() -> "$detail（HTTP $code）"
            code == 401 -> "令牌无效或没有权限（HTTP 401）"
            code == 403 -> "没有权限（HTTP 403）"
            code == 404 -> "找不到仓库或路径（HTTP 404）"
            else -> "HTTP $code"
        }
    }

    protected fun b64encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    protected fun b64decode(text: String): String =
        String(Base64.getMimeDecoder().decode(text), Charsets.UTF_8)

    protected fun encPath(path: String): String =
        path.split("/").joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    override suspend fun listDir(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        try {
            listDirBlocking(path)
        } catch (e: RepoException) {
            if (e.status == 404) emptyList() else throw e
        }
    }

    override suspend fun readFile(path: String): RemoteContent? = withContext(Dispatchers.IO) {
        try {
            readFileBlocking(path)
        } catch (e: RepoException) {
            if (e.status == 404) null else throw e
        }
    }

    protected abstract fun listDirBlocking(path: String): List<RemoteFile>
    protected abstract fun readFileBlocking(path: String): RemoteContent?
}

/* ------------------------------------------------------------------ */
/* GitHub / Gitea / GitHub Enterprise —— Contents API 形状一致          */
/* ------------------------------------------------------------------ */

class GitHubApi(config: RepoConfig) : HttpRepoApi(config) {

    private val branch: String get() = config.branch.ifBlank { "main" }

    private fun headers(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${config.token}",
        "Accept" to "application/vnd.github+json",
    )

    private fun contentsUrl(path: String): String =
        "${config.baseUrl.trimEnd('/')}/repos/${config.repo}/contents/${encPath(path)}" +
            "?ref=${URLEncoder.encode(branch, "UTF-8")}"

    override fun listDirBlocking(path: String): List<RemoteFile> {
        val arr = JSONArray(request("GET", contentsUrl(path), headers()))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteFile(
                name = o.optString("name"),
                path = o.optString("path"),
                isDir = o.optString("type") == "dir",
            )
        }
    }

    override fun readFileBlocking(path: String): RemoteContent {
        val o = JSONObject(request("GET", contentsUrl(path), headers()))
        return RemoteContent(
            text = b64decode(o.optString("content")),
            version = o.optString("sha").ifBlank { null },
        )
    }

    override suspend fun writeFile(
        path: String,
        text: String,
        message: String,
        version: String?,
    ): String? = withContext(Dispatchers.IO) {
        val url = "${config.baseUrl.trimEnd('/')}/repos/${config.repo}/contents/${encPath(path)}"
        val payload = JSONObject().apply {
            put("message", message)
            put("content", b64encode(text))
            put("branch", branch)
            if (version != null) put("sha", version)
        }
        val body = request("PUT", url, headers(), payload.toString())
        runCatching { JSONObject(body).getJSONObject("content").optString("sha") }.getOrNull()
    }
}

/* ------------------------------------------------------------------ */
/* Gitee —— Contents API 形状和 GitHub 一致，差别在鉴权和写入方法        */
/* ------------------------------------------------------------------ */

/**
 * Gitee v5 的 contents 接口：
 *   GET  /repos/{owner}/{repo}/contents/{path}?ref=&access_token=
 *   POST /repos/{owner}/{repo}/contents/{path}   新建
 *   PUT  /repos/{owner}/{repo}/contents/{path}   更新
 *
 * 和 GitHub 的两点不同：
 *   1. 鉴权用 access_token 参数，不是 Authorization 头
 *   2. 新建和更新是两个方法（GitHub 都用 PUT），所以必须先知道文件在不在
 *
 * 代价是 token 会出现在 URL 里，可能被中间代理的访问日志记下。
 * Gitee 只认这种方式，属于平台限制，文档里已标注。
 */
class GiteeApi(config: RepoConfig) : HttpRepoApi(config) {

    private val branch: String get() = config.branch.ifBlank { "main" }
    private val base: String get() = config.baseUrl.trimEnd('/')

    private fun tokenParam(): String =
        "access_token=" + URLEncoder.encode(config.token, "UTF-8")

    private fun contentsUrl(path: String): String =
        "$base/repos/${config.repo}/contents/${encPath(path)}" +
            "?ref=${URLEncoder.encode(branch, "UTF-8")}&${tokenParam()}"

    override fun listDirBlocking(path: String): List<RemoteFile> {
        val arr = JSONArray(request("GET", contentsUrl(path), emptyMap()))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteFile(
                name = o.optString("name"),
                path = o.optString("path"),
                isDir = o.optString("type") == "dir",
            )
        }
    }

    override fun readFileBlocking(path: String): RemoteContent {
        val o = JSONObject(request("GET", contentsUrl(path), emptyMap()))
        return RemoteContent(
            text = b64decode(o.optString("content")),
            version = o.optString("sha").ifBlank { null },
        )
    }

    override suspend fun writeFile(
        path: String,
        text: String,
        message: String,
        version: String?,
    ): String? = withContext(Dispatchers.IO) {
        val url = "$base/repos/${config.repo}/contents/${encPath(path)}"
        val payload = JSONObject().apply {
            put("access_token", config.token)
            put("content", b64encode(text))
            put("message", message)
            put("branch", branch)
            if (version != null) put("sha", version)
        }

        val method = if (version != null) "PUT" else "POST"
        val body = request(method, url, emptyMap(), payload.toString())

        val sha = runCatching {
            JSONObject(body).getJSONObject("content").optString("sha")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        // 响应结构万一变了就拿不到新 sha，下次提交会被当成"新建"而失败。
        // 这种情况下回读一次，多一个请求换确定性。
        sha ?: readFile(path)?.version
    }
}

/* ------------------------------------------------------------------ */
/* GitLab —— Repository Files API                                      */
/* ------------------------------------------------------------------ */

class GitLabApi(config: RepoConfig) : HttpRepoApi(config) {

    private val branch: String get() = config.branch.ifBlank { "main" }
    private val base: String get() = config.baseUrl.trimEnd('/')

    private fun headers(): Map<String, String> = mapOf(
        "PRIVATE-TOKEN" to config.token,
    )

    private fun project(): String = URLEncoder.encode(config.repo, "UTF-8")

    override fun listDirBlocking(path: String): List<RemoteFile> {
        val url = "$base/projects/${project()}/repository/tree" +
            "?path=${URLEncoder.encode(path, "UTF-8")}" +
            "&ref=${URLEncoder.encode(branch, "UTF-8")}&per_page=100"
        val arr = JSONArray(request("GET", url, headers()))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteFile(
                name = o.optString("name"),
                path = o.optString("path"),
                isDir = o.optString("type") == "tree",
            )
        }
    }

    override fun readFileBlocking(path: String): RemoteContent {
        val url = "$base/projects/${project()}/repository/files/" +
            "${URLEncoder.encode(path, "UTF-8")}/raw" +
            "?ref=${URLEncoder.encode(branch, "UTF-8")}"
        // raw 接口不返回 sha，用一个常量当"文件已存在"的标记
        return RemoteContent(text = request("GET", url, headers()), version = VERSION_EXISTS)
    }

    override suspend fun writeFile(
        path: String,
        text: String,
        message: String,
        version: String?,
    ): String? = withContext(Dispatchers.IO) {
        val url = "$base/projects/${project()}/repository/files/${URLEncoder.encode(path, "UTF-8")}"
        val payload = JSONObject().apply {
            put("branch", branch)
            put("content", text)
            put("commit_message", message)
        }
        // GitLab 区分新建和更新：已存在用 PUT，不存在用 POST
        val method = if (version != null) "PUT" else "POST"
        request(method, url, headers(), payload.toString())
        VERSION_EXISTS
    }

    private companion object {
        const val VERSION_EXISTS = "exists"
    }
}
