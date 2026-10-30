package com.wallkiosk.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wallkiosk.console.data.ConfError
import com.wallkiosk.console.data.HostConfig
import com.wallkiosk.console.data.ScreenEntry
import com.wallkiosk.console.data.ScreensConf
import com.wallkiosk.console.ui.theme.LocalPalette
import com.wallkiosk.console.ui.theme.NeuButton
import com.wallkiosk.console.ui.theme.NeuChip
import com.wallkiosk.console.ui.theme.NeuSurface
import com.wallkiosk.console.ui.theme.NeuText
import com.wallkiosk.console.ui.theme.NeuTopBar
import com.wallkiosk.console.ui.theme.NeuType
import kotlin.math.roundToInt

/** 正在编辑哪一屏：targetIface 为 null 表示新增 */
private data class EditTarget(val targetIface: String?, val entry: ScreenEntry?)

@Composable
fun HostDetailScreen(
    host: HostConfig,
    busy: String?,
    onBack: () -> Unit,
    onEditScreen: (String?, ScreenEntry?) -> Unit,
    onSetText: (String) -> Unit,
    onCommit: (String) -> Unit,
) {
    val palette = LocalPalette.current

    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var showCommit by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        NeuTopBar(
            title = host.name,
            subtitle = host.path,
            onBack = onBack,
            actions = {
                NeuButton("原文", { showRaw = true }, compact = true)
                NeuButton(
                    text = if (busy == "commit") "提交中" else "提交",
                    onClick = { showCommit = true },
                    compact = true,
                    primary = true,
                    enabled = host.dirty && host.errors.isEmpty() && busy == null,
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (host.isDefault) {
                item {
                    NeuSurface {
                        NeuText(
                            "这是默认配置：没有单独 config/hosts/<主机名>.conf 的主机都用它。" +
                                "屏数不同的主机建议各自建一份。",
                            style = NeuType.tiny,
                            color = palette.textDim,
                        )
                    }
                }
            }

            if (host.errors.isNotEmpty()) {
                item { ErrorCard(host.errors) }
            }

            itemsIndexed(host.entries) { _, entry ->
                ScreenCard(entry) { editing = EditTarget(entry.iface, entry) }
            }

            item {
                NeuButton(
                    text = "+ 添加屏幕",
                    onClick = { editing = EditTarget(null, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    val target = editing
    if (target != null) {
        EditScreenDialog(
            initial = target.entry,
            isNew = target.targetIface == null,
            validate = { entry ->
                ScreensConf.validate(ScreensConf.applyEdit(host.text, target.targetIface, entry))
            },
            onDismiss = { editing = null },
            onSave = { entry ->
                editing = null
                onEditScreen(target.targetIface, entry)
            },
            onDelete = {
                editing = null
                onEditScreen(target.targetIface, null)
            },
        )
    }

    if (showRaw) {
        RawTextDialog(
            path = host.path,
            text = host.text,
            onDismiss = { showRaw = false },
            onSave = { newText ->
                showRaw = false
                onSetText(newText)
            },
        )
    }

    if (showCommit) {
        CommitDialog(
            defaultMessage = "配置: 更新 ${host.path}",
            summary = "提交 ${host.path}（${host.entries.size} 块屏）",
            busy = busy == "commit",
            onDismiss = { showCommit = false },
            onCommit = { msg ->
                showCommit = false
                onCommit(msg)
            },
        )
    }
}

@Composable
private fun ScreenCard(entry: ScreenEntry, onEdit: () -> Unit) {
    val palette = LocalPalette.current

    NeuSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeuChip(entry.iface.ifBlank { "(空)" })
                NeuText(
                    text = zoomLabel(entry.zoom) + " · " + refreshLabel(entry.refresh),
                    style = NeuType.tiny,
                    color = palette.textDim,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    maxLines = 1,
                )
                NeuButton("编辑", onEdit, compact = true)
            }
            NeuText(
                text = entry.url.ifBlank { "(没有地址)" },
                style = NeuType.mono,
                color = if (entry.url.isBlank()) palette.danger else palette.text,
            )
        }
    }
}

/** 配置里写的是缩放系数（1.0 / 0.9），给现场看百分比更直观 */
private fun zoomLabel(zoom: String): String {
    if (zoom.isBlank()) return "100%"
    val value = zoom.toDoubleOrNull() ?: return zoom
    return "${(value * 100).roundToInt()}%"
}

private fun refreshLabel(refresh: String): String =
    if (refresh.isBlank() || refresh == "0") "不重启" else "每 $refresh 分钟重启"

@Composable
private fun ErrorCard(errors: List<ConfError>) {
    val palette = LocalPalette.current

    NeuSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NeuText(
                "这个文件现在有 ${errors.size} 个问题，wall.sh 会拒绝应用",
                style = NeuType.tiny,
                color = palette.danger,
            )
            errors.forEach { error ->
                NeuText(
                    "· ${error.iface}：${error.message}",
                    style = NeuType.tiny,
                    color = palette.danger,
                )
            }
        }
    }
}
