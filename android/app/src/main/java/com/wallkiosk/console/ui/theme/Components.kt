package com.wallkiosk.console.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/* ================================================================== */
/* 拟态阴影                                                            */
/* ================================================================== */

/**
 * 双色阴影：左上打光、右下投影。
 *
 * 为什么不用 Paint.setShadowLayer：那个在硬件加速画布上只对文字生效，
 * 画路径会什么都不出。这里改成多层偏移叠加来近似模糊，纯 Canvas 操作，
 * 行为在各版本上一致。
 */
private fun DrawScope.paintSoftShadow(path: Path, color: Color, dx: Float, dy: Float) {
    val steps = NeuDims.shadowSteps
    for (i in 1..steps) {
        val t = i / steps.toFloat()
        val alpha = (0.16f * (1f - t) + 0.015f).coerceIn(0f, 1f)
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(dx * t, dy * t)
            canvas.drawPath(path, Paint().apply { this.color = color.copy(alpha = alpha) })
            canvas.restore()
        }
    }
}

/**
 * 给任意形状加拟态外观。
 * @param raised true 凸起，false 凹陷（输入框、按下的按钮用它）
 */
fun Modifier.neu(
    shape: Shape,
    fill: Color,
    light: Color,
    dark: Color,
    raised: Boolean = true,
    offset: Dp = NeuDims.shadowOffset,
    blur: Dp = NeuDims.shadowBlur,
): Modifier = this.drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path()
    when (outline) {
        is Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is Outline.Rectangle -> path.addRect(outline.rect)
        is Outline.Generic -> path.addPath(outline.path)
    }

    val reach = offset.toPx() + blur.toPx() * 0.5f
    val dir = if (raised) 1f else -1f

    paintSoftShadow(path, light, -reach * dir, -reach * dir)
    paintSoftShadow(path, dark, reach * dir, reach * dir)

    drawIntoCanvas { canvas -> canvas.drawPath(path, Paint().apply { color = fill }) }
}

/* ================================================================== */
/* 基础组件                                                            */
/* ================================================================== */

@Composable
fun NeuText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = NeuType.body,
    color: Color = LocalPalette.current.text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        overflow = overflow,
        maxLines = maxLines,
    )
}

@Composable
fun NeuSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NeuDims.corner),
    raised: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .neu(shape, p.background, p.light, p.dark, raised)
            .clip(shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun NeuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    compact: Boolean = false,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val shape = RoundedCornerShape(if (compact) NeuDims.cornerSmall else 14.dp)
    val fill = if (primary && enabled) p.accent else p.background
    val contentColor = when {
        !enabled -> p.textDim.copy(alpha = 0.45f)
        primary -> p.onAccent
        else -> p.text
    }

    Box(
        modifier
            .neu(shape, fill, p.light, p.dark, raised = !pressed && enabled)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(
                horizontal = if (compact) 12.dp else 18.dp,
                vertical = if (compact) 7.dp else 11.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        NeuText(
            text = text,
            style = if (compact) NeuType.small else NeuType.bodyStrong,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun NeuChip(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .neu(RoundedCornerShape(8.dp), p.background, p.light, p.dark, raised = false)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        NeuText(text, style = NeuType.monoSmall, maxLines = 1)
    }
}

@Composable
fun NeuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(NeuDims.cornerSmall)

    Column(modifier) {
        NeuText(
            text = label,
            style = NeuType.tiny,
            color = p.textDim,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = NeuType.body.copy(color = p.text),
            cursorBrush = SolidColor(p.accent),
            visualTransformation = if (isPassword) PasswordVisualTransformation()
            else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .neu(shape, p.background, p.light, p.dark, raised = false)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        NeuText(placeholder, color = p.textDim.copy(alpha = 0.7f))
                    }
                    inner()
                }
            },
        )
    }
}

/** 一排等宽的分段选择，用来切主题 */
@Composable
fun NeuSegmented(
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val active = id == selectedId
            val shape = RoundedCornerShape(NeuDims.cornerSmall)
            Box(
                Modifier
                    .weight(1f)
                    .neu(
                        shape = shape,
                        fill = if (active) p.accent else p.background,
                        light = p.light,
                        dark = p.dark,
                        raised = !active,
                    )
                    .clip(shape)
                    .clickable { onSelect(id) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                NeuText(
                    text = label,
                    style = NeuType.small,
                    color = if (active) p.onAccent else p.textDim,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun NeuTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.background)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                NeuButton("←", onBack, compact = true)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                NeuText(title, style = NeuType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    NeuText(
                        text = subtitle,
                        style = NeuType.monoSmall,
                        color = p.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

@Composable
fun NeuDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dismissText: String = "取消",
    extraAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) {
            NeuSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeuText(title, style = NeuType.title)

                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (extraAction != null) extraAction()
                        Spacer(Modifier.weight(1f))
                        NeuButton(dismissText, onDismiss, compact = true)
                        if (onConfirm != null) {
                            NeuButton(
                                text = confirmText,
                                onClick = onConfirm,
                                compact = true,
                                primary = true,
                                enabled = confirmEnabled,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NeuSnackbar(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current

    LaunchedEffect(message) {
        delay(4000)
        onDismiss()
    }

    Box(modifier.fillMaxWidth().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        NeuSurface(
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            NeuText(
                text = message,
                style = NeuType.small,
                color = if (isError) p.danger else p.text,
            )
        }
    }
}

/**
 * 拟态进度条：轨道是凹的，填充用强调色。
 *
 * 不做补间动画 —— 下载进度本身就是连续变化的，再叠一层动画会让它看起来
 * 比实际慢半拍，反而不像"真的在动"。
 */
@Composable
fun NeuProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(height / 2)
    val value = fraction.coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .neu(shape, p.background, p.light, p.dark, raised = false)
            .clip(shape)
    ) {
        if (value > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(value)
                    .fillMaxHeight()
                    .background(p.accent)
            )
        }
    }
}
