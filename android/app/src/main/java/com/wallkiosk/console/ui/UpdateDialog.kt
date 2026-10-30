package com.wallkiosk.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wallkiosk.console.data.update.DownloadProgress
import com.wallkiosk.console.data.update.LatestRelease
import com.wallkiosk.console.data.update.ReleaseNotes
import com.wallkiosk.console.ui.theme.LocalPalette
import com.wallkiosk.console.ui.theme.NeuDialog
import com.wallkiosk.console.ui.theme.NeuProgressBar
import com.wallkiosk.console.ui.theme.NeuText
import com.wallkiosk.console.ui.theme.NeuType
import java.util.Locale

@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> NeuDialog(
            title = "发现新版本",
            onDismiss = onDismiss,
            confirmText = "下载",
            onConfirm = onDownload,
            dismissText = "稍后",
        ) {
            AvailableBody(state.release, currentVersion)
        }

        is UpdateState.Downloading -> NeuDialog(
            title = "正在下载",
            onDismiss = onCancelDownload,
            dismissText = "取消",
        ) {
            DownloadingBody(state.progress)
        }

        is UpdateState.Ready -> NeuDialog(
            title = "下载完成",
            onDismiss = onDismiss,
            confirmText = "安装",
            onConfirm = onInstall,
            dismissText = "稍后",
        ) {
            ReadyBody(state.release)
        }

        is UpdateState.Failed -> NeuDialog(
            title = "更新没走通",
            onDismiss = onDismiss,
            confirmText = if (state.release != null) "重试" else "",
            onConfirm = if (state.release != null) onRetry else null,
            dismissText = "关闭",
        ) {
            FailedBody(state.message)
        }

        else -> Unit
    }
}

@Composable
private fun AvailableBody(release: LatestRelease, currentVersion: String) {
    val palette = LocalPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuText(release.tag, style = NeuType.mono)
            NeuText("当前 $currentVersion", style = NeuType.tiny, color = palette.textDim)
        }

        NeuText(
            "${release.source} · ${formatSize(release.asset.size)}",
            style = NeuType.tiny,
            color = palette.textDim,
        )

        if (release.notes.isNotBlank()) {
            // Release 说明是 Markdown，这里是纯文本控件，先降级一次
            NeuText(ReleaseNotes.plain(release.notes), style = NeuType.small)
        }

        NeuText(
            "下载完会先核对包名和签名，跟当前版本对不上就不让装。",
            style = NeuType.tiny,
            color = palette.textDim,
        )
    }
}

@Composable
private fun DownloadingBody(progress: DownloadProgress) {
    val palette = LocalPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NeuProgressBar(fraction = progress.fraction)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuText(
                if (progress.known) "${(progress.fraction * 100).toInt()}%" else "下载中",
                style = NeuType.mono,
                color = palette.accent,
            )
            NeuText(
                if (progress.known) {
                    "${formatSize(progress.downloaded)} / ${formatSize(progress.total)}"
                } else {
                    formatSize(progress.downloaded)
                },
                style = NeuType.monoSmall,
                color = palette.textDim,
            )
        }
    }
}

@Composable
private fun ReadyBody(release: LatestRelease) {
    val palette = LocalPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NeuText("${release.tag} 已下载，校验通过。", style = NeuType.small)
        NeuText(
            "点安装后系统还会再确认一次，装完回到桌面重新打开就是新版本。",
            style = NeuType.tiny,
            color = palette.textDim,
        )
    }
}

@Composable
private fun FailedBody(message: String) {
    val palette = LocalPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NeuText(message, style = NeuType.small, color = palette.danger)
        NeuText(
            "GitHub 未登录时每小时只给 60 次接口调用，被限流就等一会儿再试；" +
                "国内网络也可以到设置里把更新源换成 Gitee 镜像。",
            style = NeuType.tiny,
            color = palette.textDim,
        )
    }
}

/** 字节数转成人看的大小。0 表示服务器没给长度，不是"空文件"。 */
internal fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "未知大小"
    if (bytes < 1024L) return "$bytes B"

    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    // 用 kotlin 的扩展而不是 java.lang.String.format 的静态调用：
    // 后者在 Kotlin 里对映射类型不一定能解析，这里不留这个不确定性
    return "%.1f %s".format(Locale.US, value, units[index])
}
