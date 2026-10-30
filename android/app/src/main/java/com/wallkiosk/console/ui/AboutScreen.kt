package com.wallkiosk.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wallkiosk.console.BuildConfig
import com.wallkiosk.console.ui.theme.LocalPalette
import com.wallkiosk.console.ui.theme.NeuButton
import com.wallkiosk.console.ui.theme.NeuSurface
import com.wallkiosk.console.ui.theme.NeuText
import com.wallkiosk.console.ui.theme.NeuTopBar
import com.wallkiosk.console.ui.theme.NeuType

@Composable
fun AboutScreen(
    updateSource: String,
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onOpenDialog: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val currentVersion = BuildConfig.VERSION_NAME

    Column(Modifier.fillMaxSize()) {
        NeuTopBar(title = "关于", onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NeuSurface {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeuText("大屏配置台", style = NeuType.title)
                    NeuText("wall-kiosk console", style = NeuType.monoSmall, color = palette.textDim)
                    NeuText(
                        "版本 $currentVersion（build ${BuildConfig.VERSION_CODE}）",
                        style = NeuType.small,
                        color = palette.textDim,
                    )
                }
            }

            Section("应用更新") {
                KeyValue("更新源", updateSource)
                KeyValue("状态", updateStateText(updateState, currentVersion))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeuButton(
                        text = if (updateState is UpdateState.Checking) "检查中…" else "检查更新",
                        onClick = onCheckUpdate,
                        enabled = updateState !is UpdateState.Checking &&
                            updateState !is UpdateState.Downloading,
                        primary = true,
                        compact = true,
                    )
                    if (updateState is UpdateState.Available ||
                        updateState is UpdateState.Ready ||
                        updateState is UpdateState.Failed
                    ) {
                        NeuButton("查看", onOpenDialog, compact = true)
                    }
                }

                NeuText(
                    "只在点按钮时联网查一次，不会后台自动请求。",
                    style = NeuType.tiny,
                    color = palette.textDim,
                )
            }

            Section("技术栈") {
                KeyValue("语言", "Kotlin ${KotlinVersion.CURRENT}")
                KeyValue("界面", "Jetpack Compose，自研拟态组件")
                KeyValue("网络", "HttpURLConnection（无第三方网络库）")
                KeyValue("存储", "本地文件 settings.conf，带显式 schema 迁移")
                KeyValue("依赖", "无第三方 UI 库、无 DI 框架")
            }

            Section("支持范围") {
                KeyValue("仓库", "GitHub / Gitee / Gitea / GitHub Enterprise / GitLab")
                KeyValue("协议", "HTTPS REST API + 访问令牌")
                KeyValue("系统", "Android 8.0（API 26）及以上")
                KeyValue(
                    "不支持",
                    "只开了 SSH 的自建裸仓库；运行状态（屏上到底显示了什么）要看主机的 wall.sh --check",
                )
            }

            Section("权限说明") {
                KeyValue("INTERNET", "访问你填的配置仓库 API，以及检查更新时的发布页接口")
                KeyValue(
                    "REQUEST_INSTALL_PACKAGES",
                    "只用于安装本应用自己的新版本。系统仍会弹确认框，且要你在设置里单独允许",
                )
                KeyValue(
                    "本地存储",
                    "设置文件 settings.conf（含仓库地址和访问令牌，明文），以及缓存的更新包",
                )
                KeyValue("其它", "不申请通讯录、位置、相机、存储卡等任何权限")
            }

            NeuText(
                "这个 App 只改配置仓库里的 screens.conf，不连大屏主机、不发指令。",
                style = NeuType.tiny,
                color = palette.textDim,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun updateStateText(state: UpdateState, currentVersion: String): String = when (state) {
    is UpdateState.Idle -> "还没检查过"
    is UpdateState.Checking -> "正在检查…"
    is UpdateState.UpToDate -> "已是最新版本 $currentVersion"
    is UpdateState.Available -> "发现新版本 ${state.release.tag}"
    is UpdateState.Downloading -> "正在下载 ${(state.progress.fraction * 100).toInt()}%"
    is UpdateState.Ready -> "${state.release.tag} 已下载，待安装"
    is UpdateState.Failed -> state.message
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    NeuSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NeuText(title, style = NeuType.bodyStrong)
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NeuText(key, style = NeuType.tiny, color = palette.textDim)
        NeuText(value, style = NeuType.small)
    }
}
