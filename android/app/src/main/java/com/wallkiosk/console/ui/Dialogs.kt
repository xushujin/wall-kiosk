package com.wallkiosk.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wallkiosk.console.data.ConfError
import com.wallkiosk.console.data.ScreenEntry
import com.wallkiosk.console.data.ScreensConf
import com.wallkiosk.console.ui.theme.LocalPalette
import com.wallkiosk.console.ui.theme.NeuButton
import com.wallkiosk.console.ui.theme.NeuDialog
import com.wallkiosk.console.ui.theme.NeuText
import com.wallkiosk.console.ui.theme.NeuTextField
import com.wallkiosk.console.ui.theme.NeuType

/**
 * 编辑/新增/删除一屏。
 * @param validate 传 null 表示删除；返回空列表代表这个改动合法
 */
@Composable
fun EditScreenDialog(
    initial: ScreenEntry?,
    isNew: Boolean,
    validate: (ScreenEntry?) -> List<ConfError>,
    onDismiss: () -> Unit,
    onSave: (ScreenEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var iface by remember { mutableStateOf(initial?.iface.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var zoom by remember { mutableStateOf(initial?.zoom.orEmpty()) }
    var refresh by remember { mutableStateOf(initial?.refresh.orEmpty()) }
    var errs by remember { mutableStateOf<List<ConfError>>(emptyList()) }

    val deleteAction: (@Composable () -> Unit)? = if (isNew) {
        null
    } else {
        {
            NeuButton(
                text = "删除",
                onClick = {
                    val problems = validate(null)
                    if (problems.isEmpty()) onDelete() else errs = problems
                },
                compact = true,
            )
        }
    }

    NeuDialog(
        title = if (isNew) "添加屏幕" else "编辑 ${initial?.iface.orEmpty()}",
        onDismiss = onDismiss,
        confirmText = "确定",
        onConfirm = {
            val entry = ScreenEntry(iface.trim(), url.trim(), zoom.trim(), refresh.trim())
            val problems = validate(entry)
            if (problems.isEmpty()) onSave(entry) else errs = problems
        },
        extraAction = deleteAction,
    ) {
        NeuTextField(
            value = iface,
            onValueChange = { iface = it },
            label = "接口名",
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("xrandr 里 connected 后面那个名字，别写索引")

        NeuTextField(
            value = url,
            onValueChange = { url = it },
            label = "页面地址",
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NeuTextField(
                value = zoom,
                onValueChange = { zoom = it },
                label = "缩放",
                modifier = Modifier.fillMaxWidth(),
            )
            NeuTextField(
                value = refresh,
                onValueChange = { refresh = it },
                label = "定时重启（分钟）",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Hint("缩放留空=100%，0.9=90%。")
        Hint(
            "定时重启：浏览器跑满这么多分钟就自动关掉重开，防止看板挂久了内存越吃越多。" +
                "0 或不填=不重启。重开那一下屏幕会黑几秒，不是故障。"
        )

        ErrorList(errs)
    }
}

@Composable
fun RawTextDialog(
    path: String,
    text: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(text) }
    var errs by remember { mutableStateOf<List<ConfError>>(emptyList()) }

    NeuDialog(
        title = "原始文本",
        onDismiss = onDismiss,
        confirmText = "确定",
        onConfirm = {
            val problems = ScreensConf.validate(value)
            if (problems.isEmpty()) onSave(value) else errs = problems
        },
    ) {
        Hint(path)
        NeuTextField(
            value = value,
            onValueChange = {
                value = it
                errs = emptyList()
            },
            label = "文件内容",
            singleLine = false,
            minLines = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("注释和空行会原样保留。保存前会做一次和 wall.sh 相同的校验。")
        ErrorList(errs)
    }
}

@Composable
fun CommitDialog(
    defaultMessage: String,
    summary: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    var message by remember { mutableStateOf(defaultMessage) }

    NeuDialog(
        title = "提交到仓库",
        onDismiss = onDismiss,
        confirmText = if (busy) "提交中…" else "提交",
        onConfirm = { onCommit(message.trim().ifBlank { defaultMessage }) },
        confirmEnabled = !busy,
    ) {
        NeuTextField(
            value = message,
            onValueChange = { message = it },
            label = "提交说明",
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(summary)
    }
}

@Composable
fun NewHostDialog(
    hostExists: (String) -> Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, ScreenEntry) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var iface by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    val namePattern = remember { Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$") }

    NeuDialog(
        title = "新建主机",
        onDismiss = onDismiss,
        confirmText = "创建",
        onConfirm = {
            val hostName = name.trim()
            val first = ScreenEntry(iface.trim(), url.trim(), "", "")
            when {
                !namePattern.matches(hostName) ->
                    err = "主机名只能用字母、数字、点、下划线、连字符，且不能以符号开头"

                hostExists(hostName) ->
                    err = "这个主机已经有一份配置了"

                else -> {
                    val problems = ScreensConf.validate(ScreensConf.newHostTemplate(hostName, first))
                    if (problems.isEmpty()) {
                        onCreate(hostName, first)
                    } else {
                        err = problems.joinToString("；") { "${it.iface}：${it.message}" }
                    }
                }
            }
        },
    ) {
        Hint(
            "会创建 config/hosts/<主机名>.conf。主机名必须和那台机器上 hostname 的输出完全一致，" +
                "否则它不会用这个文件。"
        )
        NeuTextField(
            value = name,
            onValueChange = { name = it; err = null },
            label = "主机名",
            modifier = Modifier.fillMaxWidth(),
        )
        NeuTextField(
            value = iface,
            onValueChange = { iface = it; err = null },
            label = "第一块屏的接口名",
            modifier = Modifier.fillMaxWidth(),
        )
        NeuTextField(
            value = url,
            onValueChange = { url = it; err = null },
            label = "页面地址",
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("第一块屏必须一起填：空的配置文件会让那台主机启动时报「无可用配置」。")

        val message = err
        if (message != null) {
            NeuText(message, style = NeuType.tiny, color = LocalPalette.current.danger)
        }
    }
}

@Composable
private fun Hint(text: String) {
    NeuText(text, style = NeuType.tiny, color = LocalPalette.current.textDim)
}

@Composable
private fun ErrorList(errors: List<ConfError>) {
    val palette = LocalPalette.current
    errors.forEach { error ->
        NeuText(
            "· ${error.iface}：${error.message}",
            style = NeuType.tiny,
            color = palette.danger,
        )
    }
}
