/*
 * Copyright (c) 2024-2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.tgwrist.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard

/**
 * 一个为 Wear OS 设计的可复用文本输入框 Composable。
 *
 * 它显示为一个 Chip，点击后会启动系统全屏输入法（键盘、语音等）。
 * 当用户完成输入后，会通过回调函数返回结果。
 *
 * @param placeholder 当 value 为空时，在 Chip 上显示的占位文本。
 * @param onTextUpdated 用户完成输入后的回调函数，参数为输入的新文本。
 * @param modifier Compose 修饰符。
 */
@Composable
fun TextInputChip(
    modifier: Modifier = Modifier,
    value: String,
    title: String = "",
    placeholder: String = "",
    isPassword: Boolean = false,
    boardType: KeyboardType = KeyboardType.Text,
    onTextUpdated: (String) -> Unit,
    onLongClick: () -> Unit = {},
    transformationSpec: SurfaceTransformation,
) {
    var text by remember(value) { mutableStateOf(value) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val displayText = when {
        text.isEmpty() -> placeholder
        isPassword -> "•".repeat(text.length)
        else -> text
    }

    // ===== 隐藏输入框（关键）=====
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .size(1.dp)              // 不占布局
            .alpha(0f)               // 不可见
            .focusRequester(focusRequester),
        singleLine = true,
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType =
                if (boardType == KeyboardType.Text)
                if (isPassword) KeyboardType.Password else KeyboardType.Text
                else boardType
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboardController?.hide()
                onTextUpdated(text)
            }
        )
    )

    val onClick: () -> Unit = {
        // 关键一行：请求焦点 → Wear OS IME 全屏接管
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // ===== UI 展示 =====
    if (title.isEmpty()) {
        FilledTonalButton(
            onClick = onClick,
            onLongClick = onLongClick,
            transformation = transformationSpec,
            modifier = modifier
        ) {
            Text(
                text = displayText,
                modifier = Modifier.fillMaxWidth(),
                textAlign = if (text.isEmpty()) TextAlign.Center else TextAlign.Start
            )
        }
    } else {
        TitleCard(
            modifier = modifier,
            title = {
                Text(title, fontSize = 12.sp, color = Color(0xFFC9C3CF))
            },
            onClick = onClick,
            onLongClick = onLongClick,
            transformation = transformationSpec,
            colors = CardDefaults.cardColors(),
        ) {
            Text(
                text = displayText,
                fontSize = 18.sp,
                color = Color.White,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
