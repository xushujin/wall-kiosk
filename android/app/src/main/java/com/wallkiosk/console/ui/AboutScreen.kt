package com.wallkiosk.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wallkiosk.console.BuildConfig
import com.wallkiosk.console.ui.theme.LocalPalette
import com.wallkiosk.console.ui.theme.NeuSurface
import com.wallkiosk.console.ui.theme.NeuText
import com.wallkiosk.console.ui.theme.NeuTopBar
import com.wallkiosk.console.ui.theme.NeuType

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val palette = LocalPalette.current

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
                        "版本 ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                        style = NeuType.small,
                        color = palette.textDim,
                    )
                }
            }

            Section("技术栈") {
                KeyValue("语言", "Kotlin ${KotlinVersion.CURRENT}")
                KeyValue("界面", "Jetpack Compose，自研拟态组件")
                KeyValue("网络", "HttpURLConnection（无第三方网络库）")
                KeyValue("存储", "本地文件 settings.conf，带显式 schema 迁移")
                KeyValue("依赖", "无第三方 UI 库、无 DI 框架")
            }

            Section("支持范围") {
                KeyValue("仓库", "GitHub / GitHub Enterprise / Gitea / GitLab")
                KeyValue("协议", "HTTPS REST API + 访问令牌")
                KeyValue("系统", "Android 8.0（API 26）及以上")
                KeyValue(
                    "不支持",
                    "只开了 SSH 的自建裸仓库；运行状态（屏上到底显示了什么）要看主机的 wall.sh --check",
                )
            }

            Section("权限说明") {
                KeyValue("INTERNET", "只用来访问你在设置里填的那个 API 地址，没有别的域名")
                KeyValue(
                    "本地存储",
                    "设置文件 settings.conf，包含仓库地址和访问令牌。令牌是明文，建议用只读或按需的细粒度令牌",
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
