package com.wallkiosk.console.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallkiosk.console.data.AppSettings
import com.wallkiosk.console.data.HostConfig
import com.wallkiosk.console.data.RepoApi
import com.wallkiosk.console.data.RepoConfig
import com.wallkiosk.console.data.ScreenEntry
import com.wallkiosk.console.data.ScreensConf
import com.wallkiosk.console.data.SettingsStore
import com.wallkiosk.console.data.ThemeIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Route { SETTINGS, LIST, DETAIL, ABOUT }

class ConsoleViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    /**
     * 唯一的设置对象：主题 + 仓库。
     * 「同一个设置只有一套实现」——界面上的主题不是另存的 state，
     * 就是这里的 settings.themeId。
     */
    var settings by mutableStateOf(store.load())
        private set

    /** 磁盘上的 schema 比 App 新时禁止回写，界面要提示 */
    var readOnlySettings by mutableStateOf(store.newerSchema)
        private set

    var route by mutableStateOf(if (settings.repo == null) Route.SETTINGS else Route.LIST)
        private set

    var hosts by mutableStateOf<List<HostConfig>>(emptyList())
        private set

    var openedPath by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    /** "commit" / "create"，用来禁用按钮 */
    var busy by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val openedHost: HostConfig?
        get() = hosts.firstOrNull { it.path == openedPath }

    init {
        if (settings.repo != null) reload()
    }

    fun clearMessage() { message = null }
    fun clearError() { error = null }

    /* ---------------- 导航 ---------------- */

    fun goSettings() { route = Route.SETTINGS }

    fun goAbout() { route = Route.ABOUT }

    fun goList() {
        openedPath = null
        route = if (settings.repo == null) Route.SETTINGS else Route.LIST
    }

    fun open(path: String) {
        openedPath = path
        route = Route.DETAIL
    }

    /** 返回键：详情→列表，关于→设置，设置→列表（仓库没配就留在设置） */
    fun back(): Boolean {
        when (route) {
            Route.DETAIL -> { openedPath = null; route = Route.LIST }
            Route.ABOUT -> route = Route.SETTINGS
            Route.SETTINGS -> if (settings.repo != null) route = Route.LIST else return false
            Route.LIST -> return false
        }
        return true
    }

    /* ---------------- 设置 ---------------- */

    /** 主题改动立刻生效并落盘，不需要点保存 */
    fun setTheme(themeId: String) {
        if (!ThemeIds.isValid(themeId)) return
        val next = settings.copy(themeId = themeId)
        settings = next
        persist(next)
    }

    /**
     * 保存设置。仓库配置变了就先真的读一遍仓库，成功了才落盘 ——
     * 否则 token 填错也会被存下来，下次打开直接进空列表，很难看出是令牌的问题。
     */
    fun saveSettings(next: AppSettings) {
        val repoChanged = next.repo != settings.repo

        if (!repoChanged) {
            settings = next
            persist(next)
            message = "已保存"
            return
        }

        val repo = next.repo
        if (repo == null) {
            settings = next
            persist(next)
            hosts = emptyList()
            route = Route.SETTINGS
            return
        }

        viewModelScope.launch {
            loading = true
            error = null
            try {
                val fetched = fetchHosts(repo)
                settings = next
                persist(next)
                hosts = fetched
                route = Route.LIST
                if (fetched.isEmpty()) {
                    error = emptyRepoMessage(repo)
                }
            } catch (e: Exception) {
                error = "连接失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    private fun persist(next: AppSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.save(next) }
        }
    }

    /**
     * 仓库能连上但一个配置文件都没有。多半是分支或路径不对，
     * 所以把当前用的分支名直接写进提示里，省得用户自己猜。
     */
    private fun emptyRepoMessage(repo: RepoConfig): String =
        "仓库连上了，但没找到 ${ScreensConf.DEFAULT_PATH}，也没有 " +
            "${ScreensConf.hostsDir()}/*.conf。检查分支（当前 ${repo.branch}）和路径，" +
            "或者先按 templates/screens.conf.example 建一份提交上去。"

    /* ---------------- 读取 ---------------- */

    fun reload() {
        val repo = settings.repo ?: return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                hosts = fetchHosts(repo)
                if (hosts.isEmpty()) {
                    error = emptyRepoMessage(repo)
                }
            } catch (e: Exception) {
                error = "读取失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    private suspend fun fetchHosts(repo: RepoConfig): List<HostConfig> {
        val api = RepoApi.of(repo)

        val files = api.listDir(ScreensConf.hostsDir())
            .filter { !it.isDir && it.name.endsWith(".conf", ignoreCase = true) }

        val list = ArrayList<HostConfig>()
        for (file in files) {
            val content = api.readFile(file.path) ?: continue
            list.add(
                HostConfig(
                    name = file.name.dropLast(".conf".length),
                    path = file.path,
                    text = content.text,
                    version = content.version,
                )
            )
        }

        api.readFile(ScreensConf.DEFAULT_PATH)?.let {
            list.add(
                0,
                HostConfig(
                    name = "默认配置",
                    path = ScreensConf.DEFAULT_PATH,
                    text = it.text,
                    version = it.version,
                )
            )
        }

        return list.sortedWith(compareBy({ if (it.isDefault) 0 else 1 }, { it.name.lowercase() }))
    }

    /* ---------------- 编辑 ---------------- */

    fun updateText(path: String, text: String) {
        hosts = hosts.map { if (it.path == path) it.copy(text = text) else it }
    }

    fun applyScreenEdit(targetIface: String?, entry: ScreenEntry?) {
        val host = openedHost ?: return
        updateText(host.path, ScreensConf.applyEdit(host.text, targetIface, entry))
    }

    fun commit(commitMessage: String) {
        val host = openedHost ?: return
        val repo = settings.repo ?: return
        if (!host.dirty) return

        val errs = host.errors
        if (errs.isNotEmpty()) {
            error = "还有 ${errs.size} 个校验问题，先修掉"
            return
        }

        viewModelScope.launch {
            busy = "commit"
            try {
                val version = RepoApi.of(repo)
                    .writeFile(host.path, host.text, commitMessage, host.version)
                hosts = hosts.map {
                    if (it.path == host.path) it.copy(version = version, baseText = host.text) else it
                }
                message = "已提交：$commitMessage"
            } catch (e: Exception) {
                error = "提交失败：${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun hostExists(name: String): Boolean =
        hosts.any { it.path == ScreensConf.hostPath(name) }

    fun createHost(name: String, first: ScreenEntry) {
        val repo = settings.repo ?: return
        val path = ScreensConf.hostPath(name)
        val text = ScreensConf.newHostTemplate(name, first)

        val errs = ScreensConf.validate(text)
        if (errs.isNotEmpty()) {
            error = errs.joinToString("；") { "${it.iface}：${it.message}" }
            return
        }

        viewModelScope.launch {
            busy = "create"
            try {
                val version = RepoApi.of(repo)
                    .writeFile(path, text, "配置: 新增主机 $name", null)
                hosts = hosts + HostConfig(name, path, text, version)
                openedPath = path
                route = Route.DETAIL
                message = "已创建 $path"
            } catch (e: Exception) {
                error = "创建失败：${e.message}"
            } finally {
                busy = null
            }
        }
    }
}
