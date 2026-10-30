package com.wallkiosk.console.data

/** 配置里的一行：接口名|地址|缩放|定时重启分钟 */
data class ScreenEntry(
    val iface: String = "",
    val url: String = "",
    val zoom: String = "",
    val refresh: String = "",
)

/** 配置文件的原始行，编辑时要能原样写回注释和空行 */
sealed class ConfRow {
    abstract val raw: String

    data class Comment(override val raw: String) : ConfRow()
    data class Blank(override val raw: String) : ConfRow()
    data class Data(override val raw: String, val entry: ScreenEntry) : ConfRow()
}

data class ConfError(val iface: String, val message: String)

/** 一台主机的配置。name 是主机名，path 是仓库里的相对路径。 */
data class HostConfig(
    val name: String,
    val path: String,
    /** 当前编辑中的内容 */
    val text: String,
    val version: String?,
    /** 最近一次从仓库读到的内容，用来判断有没有真的改动 */
    val baseText: String = text,
) {
    val isDefault: Boolean get() = path == ScreensConf.DEFAULT_PATH
    val entries: List<ScreenEntry> get() = ScreensConf.entries(text)
    val errors: List<ConfError> get() = ScreensConf.validate(entries)

    /** 改回原样就不算脏，避免提交一个空变更 */
    val dirty: Boolean get() = text != baseText
}

enum class Provider(val label: String, val defaultBaseUrl: String) {
    GITHUB("GitHub", "https://api.github.com"),
    GITEE("Gitee", "https://gitee.com/api/v5"),
    GITEA("Gitea", ""),
    GITLAB("GitLab", "https://gitlab.com/api/v4"),
}

data class RepoConfig(
    val provider: Provider = Provider.GITHUB,
    val baseUrl: String = Provider.GITHUB.defaultBaseUrl,
    val repo: String = "",
    val branch: String = "main",
    val token: String = "",
) {
    val usable: Boolean get() = baseUrl.isNotBlank() && repo.isNotBlank() && token.isNotBlank()
}

class RepoException(val status: Int, message: String) : Exception(message)
