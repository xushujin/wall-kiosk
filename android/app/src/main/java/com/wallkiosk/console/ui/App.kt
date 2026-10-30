package com.wallkiosk.console.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallkiosk.console.ui.theme.LocalPalette
import com.wallkiosk.console.ui.theme.NeuSnackbar
import com.wallkiosk.console.ui.theme.NeuTheme

@Composable
fun WallKioskApp(
    vm: ConsoleViewModel = viewModel(),
    updateVm: UpdateViewModel = viewModel(),
) {
    // 主题从设置里读，整棵树共用一份 —— 切换后全局立刻生效
    NeuTheme(themeId = vm.settings.themeId) {
        val palette = LocalPalette.current
        val context = LocalContext.current

        val canGoBack = vm.route == Route.DETAIL ||
            vm.route == Route.ABOUT ||
            (vm.route == Route.SETTINGS && vm.settings.repo != null)
        BackHandler(enabled = canGoBack) { vm.back() }

        Box(Modifier.fillMaxSize().background(palette.background)) {
            val host = vm.openedHost

            when {
                vm.route == Route.DETAIL && host != null -> HostDetailScreen(
                    host = host,
                    busy = vm.busy,
                    onBack = { vm.back() },
                    onEditScreen = { iface, entry -> vm.applyScreenEdit(iface, entry) },
                    onSetText = { vm.updateText(host.path, it) },
                    onCommit = { vm.commit(it) },
                )

                vm.route == Route.SETTINGS -> SettingsScreen(
                    settings = vm.settings,
                    loading = vm.loading,
                    readOnly = vm.readOnlySettings,
                    onThemeChange = { vm.setTheme(it) },
                    onSave = { vm.saveSettings(it) },
                    onAbout = { vm.goAbout() },
                    onBack = { vm.back() },
                )

                vm.route == Route.ABOUT -> AboutScreen(
                    updateSource = vm.settings.updateRepo,
                    updateState = updateVm.state,
                    onCheckUpdate = { updateVm.check(vm.settings.updateRepo) },
                    onOpenDialog = { updateVm.openDialog() },
                    onBack = { vm.back() },
                )

                else -> HostListScreen(
                    hosts = vm.hosts,
                    loading = vm.loading,
                    onOpen = { vm.open(it) },
                    onReload = { vm.reload() },
                    onSettings = { vm.goSettings() },
                    onCreate = { name, first -> vm.createHost(name, first) },
                    hostExists = { vm.hostExists(it) },
                )
            }

            if (updateVm.dialogVisible) {
                UpdateDialog(
                    state = updateVm.state,
                    currentVersion = updateVm.currentVersion,
                    onDownload = { updateVm.download() },
                    onCancelDownload = { updateVm.cancelDownload() },
                    onRetry = { updateVm.retry() },
                    onInstall = { updateVm.install(context) },
                    onDismiss = { updateVm.dismiss() },
                )
            }

            val banner = vm.error ?: vm.message ?: updateVm.notice
            if (banner != null) {
                NeuSnackbar(
                    message = banner,
                    isError = vm.error != null,
                    onDismiss = {
                        vm.clearError()
                        vm.clearMessage()
                        updateVm.clearNotice()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 18.dp),
                )
            }
        }
    }
}
