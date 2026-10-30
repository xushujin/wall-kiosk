package com.wallkiosk.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wallkiosk.console.data.HostConfig
import com.wallkiosk.console.data.ScreenEntry
import com.wallkiosk.console.ui.theme.LocalPalette
import com.wallkiosk.console.ui.theme.NeuButton
import com.wallkiosk.console.ui.theme.NeuSurface
import com.wallkiosk.console.ui.theme.NeuText
import com.wallkiosk.console.ui.theme.NeuTopBar
import com.wallkiosk.console.ui.theme.NeuType

@Composable
fun HostListScreen(
    hosts: List<HostConfig>,
    loading: Boolean,
    onOpen: (String) -> Unit,
    onReload: () -> Unit,
    onSettings: () -> Unit,
    onCreate: (String, ScreenEntry) -> Unit,
    hostExists: (String) -> Boolean,
) {
    var showNew by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        NeuTopBar(
            title = "主机",
            subtitle = if (hosts.isEmpty()) "还没有配置" else "${hosts.size} 台",
            actions = {
                NeuButton(
                    text = if (loading) "…" else "刷新",
                    onClick = onReload,
                    compact = true,
                    enabled = !loading,
                )
                NeuButton("设置", onSettings, compact = true)
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(hosts, key = { it.path }) { host ->
                HostCard(host) { onOpen(host.path) }
            }

            item {
                NeuButton(
                    text = "+ 新建主机",
                    onClick = { showNew = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showNew) {
        NewHostDialog(
            hostExists = hostExists,
            onDismiss = { showNew = false },
            onCreate = { name, first ->
                showNew = false
                onCreate(name, first)
            },
        )
    }
}

@Composable
private fun HostCard(host: HostConfig, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val errorCount = host.errors.size

    NeuSurface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeuText(
                    text = host.name,
                    style = NeuType.bodyStrong,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (host.dirty) {
                    Box(Modifier.size(8.dp).background(palette.accent, CircleShape))
                }
            }
            NeuText(
                text = host.path,
                style = NeuType.monoSmall,
                color = palette.textDim,
                maxLines = 1,
            )
            NeuText(
                text = "${host.entries.size} 块屏" +
                    if (errorCount > 0) " · $errorCount 个问题" else "",
                style = NeuType.tiny,
                color = if (errorCount > 0) palette.danger else palette.textDim,
            )
        }
    }
}
