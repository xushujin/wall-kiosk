package com.wallkiosk.console.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallkiosk.console.BuildConfig
import com.wallkiosk.console.data.update.ApkInstaller
import com.wallkiosk.console.data.update.DownloadProgress
import com.wallkiosk.console.data.update.LatestRelease
import com.wallkiosk.console.data.update.UpdateChecker
import com.wallkiosk.console.data.update.UpdateDownloader
import com.wallkiosk.console.data.update.Version
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val release: LatestRelease) : UpdateState
    data class Downloading(val progress: DownloadProgress) : UpdateState
    data class Ready(val file: File, val release: LatestRelease) : UpdateState
    data class Failed(val message: String, val release: LatestRelease? = null) : UpdateState
}

/**
 * 应用内更新的状态机。
 *
 * 单独一个 ViewModel 而不是塞进 ConsoleViewModel：更新和"改配置"没有关系，
 * 混在一起会让本来就长的那个类更难读。下载状态挂在这里也意味着
 * 从关于页切走再回来，进度还在。
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val checker = UpdateChecker()
    private val downloader = UpdateDownloader()

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    var dialogVisible by mutableStateOf(false)
        private set

    /** 一次性提示，比如"去系统设置开权限" */
    var notice by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun clearNotice() { notice = null }

    fun check(updateRepo: String) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        job?.cancel()
        job = viewModelScope.launch {
            state = UpdateState.Checking
            try {
                val release = checker.latest(updateRepo)
                if (Version.isNewer(release.tag, currentVersion)) {
                    state = UpdateState.Available(release)
                    dialogVisible = true
                } else {
                    state = UpdateState.UpToDate(currentVersion)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UpdateState.Failed(e.message ?: "检查更新失败")
            }
        }
    }

    fun download() {
        val release = (state as? UpdateState.Available)?.release ?: return
        val name = release.asset.name.takeIf { it.isNotBlank() } ?: "update.apk"

        job = viewModelScope.launch {
            state = UpdateState.Downloading(DownloadProgress(0L, release.asset.size))
            try {
                // 清旧包和建目录也算磁盘操作，一起放到 IO 上做
                val dest = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, UPDATE_DIR)
                    if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
                    dir.mkdirs()
                    File(dir, name)
                }

                // 进度回调是在 IO 线程上来的。Compose 的 snapshot state 写入是原子的，
                // 跨线程写只会有一次可见性延迟，不会读到半截数据。
                val file = downloader.download(release.asset.url, dest, release.asset.size) { p ->
                    state = UpdateState.Downloading(p)
                }

                // 解析 APK、算证书指纹都是磁盘 + 计算，不能留在主线程
                val inspection = withContext(Dispatchers.IO) {
                    ApkInstaller.inspect(getApplication<Application>(), file)
                }
                state = if (inspection.ok) {
                    UpdateState.Ready(file, release)
                } else {
                    UpdateState.Failed(inspection.reason ?: "安装包校验没通过", release)
                }
            } catch (e: CancellationException) {
                // 用户点了取消：退回"发现新版本"，还能重新下
                state = UpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                state = UpdateState.Failed(e.message ?: "下载失败", release)
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    fun retry() {
        val release = (state as? UpdateState.Failed)?.release ?: return
        state = UpdateState.Available(release)
        download()
    }

    fun install(context: Context) {
        val ready = state as? UpdateState.Ready ?: return

        if (!ApkInstaller.canInstall(context)) {
            notice = if (ApkInstaller.openInstallSettings(context)) {
                "在系统设置里允许「安装未知应用」，然后回来再点一次安装"
            } else {
                "打不开系统设置，请手动到「设置 → 应用 → 特殊权限」里允许安装"
            }
            return
        }

        if (ApkInstaller.install(context, ready.file)) {
            dialogVisible = false
            notice = "已交给系统安装器，按提示完成升级"
        } else {
            notice = "没有找到能处理 APK 的应用，用文件管理器手动装 ${ready.file.name} 也行"
        }
    }

    fun dismiss() {
        // 下载中不让关，避免"看起来取消了其实还在下"
        if (state is UpdateState.Downloading) return
        dialogVisible = false
        when (state) {
            is UpdateState.Ready, is UpdateState.Failed -> state = UpdateState.Idle
            // 有新版本时保留状态，关于页还能再点开
            else -> Unit
        }
    }

    fun openDialog() {
        when (state) {
            is UpdateState.Available, is UpdateState.Ready, is UpdateState.Failed ->
                dialogVisible = true
            else -> Unit
        }
    }

    private companion object {
        const val UPDATE_DIR = "updates"
    }
}
