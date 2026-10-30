package com.wallkiosk.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wallkiosk.console.data.AppSettings
import com.wallkiosk.console.data.Provider
import com.wallkiosk.console.data.RepoConfig
import com.wallkiosk.console.ui.theme.LocalPalette
import com.wallkiosk.console.ui.theme.NeuButton
import com.wallkiosk.console.ui.theme.NeuSegmented
import com.wallkiosk.console.ui.theme.NeuSurface
import com.wallkiosk.console.ui.theme.NeuText
import com.wallkiosk.console.ui.theme.NeuTextField
import com.wallkiosk.console.ui.theme.NeuTopBar
import com.wallkiosk.console.ui.theme.NeuType
import com.wallkiosk.console.ui.theme.Palettes

@Composable
fun SettingsScreen(
    settings: AppSettings,
    loading: Boolean,
    readOnly: Boolean,
    onThemeChange: (String) -> Unit,
    onSave: (AppSettings) -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val existing = settings.repo

    var provider by remember { mutableStateOf(existing?.provider ?: Provider.GITHUB) }
    var baseUrl by remember {
        mutableStateOf(existing?.baseUrl ?: Provider.GITHUB.defaultBaseUrl)
    }
    var repo by remember { mutableStateOf(existing?.repo.orEmpty()) }
    var branch by remember { mutableStateOf(existing?.branch ?: "main") }
    var token by remember { mutableStateOf(existing?.token.orEmpty()) }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        NeuTopBar(
            title = "设置",
            subtitle = existing?.repo ?: "还没连接仓库",
            onBack = if (existing != null) onBack else null,
            actions = { NeuButton("关于", onAbout, compact = true) },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            NeuSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeuText("外观", style = NeuType.bodyStrong)
                    NeuText(
                        "四套配色，选完立刻生效，全局共用一套。",
                        style = NeuType.tiny,
                        color = palette.textDim,
                    )
                    NeuSegmented(
                        options = Palettes.all.map { it.id to it.label },
                        selectedId = settings.themeId,
                        onSelect = onThemeChange,
                    )
                }
            }

            if (readOnly) {
                NeuSurface {
                    NeuText(
                        "设置文件来自更高版本的 App，当前版本只读，不会覆盖它。升级 App 后再改。",
                        style = NeuType.tiny,
                        color = palette.danger,
                    )
                }
            }

            NeuSurface {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    NeuText("配置仓库", style = NeuType.bodyStrong)
                    NeuText(
                        "填的是放 screens.conf 的那个仓库，不是本程序仓库。只读权限能看，改需要写权限。",
                        style = NeuType.tiny,
                        color = palette.textDim,
                    )

                    NeuSegmented(
                        options = Provider.values().map { it.name to it.label },
                        selectedId = provider.name,
                        onSelect = { id ->
                            val next = Provider.valueOf(id)
                            // 换平台时，地址还是旧平台默认值就跟着换掉
                            if (baseUrl.isBlank() || baseUrl == provider.defaultBaseUrl) {
                                baseUrl = next.defaultBaseUrl
                            }
                            provider = next
                        },
                    )

                    NeuTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = "API 地址",
                        placeholder = "https://api.github.com",
                        keyboardType = KeyboardType.Uri,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NeuText(apiHint(provider), style = NeuType.tiny, color = palette.textDim)

                    NeuTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        label = "仓库",
                        placeholder = if (provider == Provider.GITLAB) "group/project" else "owner/repo",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    NeuTextField(
                        value = branch,
                        onValueChange = { branch = it },
                        label = "分支",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    NeuTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = "访问令牌",
                        placeholder = "ghp_... / glpat-...",
                        isPassword = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NeuText(
                        "只存在本机，只会发往上面这个 API 地址。GitHub 要 Contents: Read and write；" +
                            "GitLab 要 api 或 write_repository。",
                        style = NeuType.tiny,
                        color = palette.textDim,
                    )

                    if (localError != null) {
                        NeuText(localError.orEmpty(), style = NeuType.tiny, color = palette.danger)
                    }

                    NeuButton(
                        text = when {
                            loading -> "连接中…"
                            existing == null -> "连接"
                            else -> "保存"
                        },
                        onClick = {
                            val config = RepoConfig(
                                provider = provider,
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                repo = repo.trim().trim('/'),
                                branch = branch.trim().ifBlank { "main" },
                                token = token.trim(),
                            )
                            if (!config.usable) {
                                localError = "API 地址、仓库、令牌都要填"
                            } else {
                                localError = null
                                onSave(settings.copy(repo = config))
                            }
                        },
                        enabled = !loading && !readOnly,
                        primary = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun apiHint(provider: Provider): String = when (provider) {
    Provider.GITHUB -> "GitHub 填 https://api.github.com；GitHub Enterprise 填 https://<域名>/api/v3"
    Provider.GITEA -> "填 https://<域名>/api/v1（Gitea 默认）"
    Provider.GITLAB -> "填 https://<域名>/api/v4"
}
