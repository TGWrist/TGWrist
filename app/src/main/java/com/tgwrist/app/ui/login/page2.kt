package com.tgwrist.app.ui.login

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.TextInputChip
import com.tgwrist.app.utils.generateQrCodeBitmap
import org.drinkless.tdlib.TdApi

@Composable
internal fun Page2(
    isQrMode: Boolean,
    qrLink: String,
    errorCallback: (String) -> Unit,
    onBack: () -> Unit
) {
    if (isQrMode) {
        Page2QrContent(link = qrLink, onBack = onBack)
    } else {
        Page2CodeContent(errorCallback = errorCallback, onBack = onBack)
    }
}

/**
 * 二维码登录内容。
 *
 * [link] 来自 TDLib 的 AuthorizationStateWaitOtherDeviceConfirmation，
 * 是一个会频繁刷新的 tg://login?token=... 链接，需实时重绘二维码。
 * 用户用另一台已登录的 Telegram 设备扫描即可完成登录。
 */
@Composable
private fun Page2QrContent(
    link: String,
    onBack: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()

    // 链接变化时重新生成二维码位图
    val qrBitmap = remember(link) {
        if (link.isBlank()) null else generateQrCodeBitmap(link, 360)
    }

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        edgeButton = {
            EdgeButton(
                onClick = { onBack.invoke() },
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDDFFD)),
                modifier = Modifier.scrollable(
                    listState,
                    orientation = Orientation.Vertical,
                    reverseDirection = true,
                    overscrollEffect = overscroll,
                ),
            ) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "back")
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscroll,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            item {
                ListHeader {
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.QR_login),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // 二维码（白底卡片，避免被深色主题影响识别）
            item {
                Box(
                    modifier = Modifier
                        .size(168.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.QR_login),
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.QR_login_loading),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = Color.Black
                        )
                    }
                }
            }
            // 扫码提示
            item {
                Text(
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    text = if (qrBitmap != null) {
                        stringResource(R.string.QR_login_tip)
                    } else {
                        stringResource(R.string.QR_login_loading)
                    },
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 验证码输入内容（手机号登录流程的第二步）。
 */
@Composable
private fun Page2CodeContent(
    errorCallback: (String) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    var code by remember { mutableStateOf("") }
    var sendingRequest by remember { mutableStateOf(false) }

    // 字符串变量
    val errorRequestText = stringResource(R.string.Request_error)

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        edgeButton = {
            EdgeButton(
                onClick = {
                    if (code.isBlank()) {
                        onBack.invoke()
                    } else {
                        sendingRequest = true
                        TgClient.send(TdApi.CheckAuthenticationCode(code)) {
                            if (it is TdApi.Error) {
                                val errorText = "$errorRequestText\ncode:${it.code}\n${it.message}"
                                errorCallback.invoke(errorText)
                                sendingRequest = false
                                Log.e("TDLib", it.toString())
                            }
                        }
                    }
                },
                buttonSize = EdgeButtonSize.Medium,
                enabled = !sendingRequest,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDDFFD)),
                modifier =
                    // 如果用户开始从EdgeButton滚动
                    Modifier.scrollable(
                        listState,
                        orientation = Orientation.Vertical,
                        reverseDirection = true,
                        // 应对EdgeButton应用超滚动效果以适当调整滚动行为
                        overscrollEffect = overscroll,
                    ),
            ) {
                if (code.isBlank()) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "back")
                } else {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "go")
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscroll,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            item {
                ListHeader {
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Login_code),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }
            item {
                // 验证码输入框
                TextInputChip(
                    value = code,
                    title = stringResource(R.string.Enter_login_code),
                    boardType = KeyboardType.Number,
                    onTextUpdated = { str ->
                        code = str
                    },
                    transformationSpec = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .padding(5.dp)
                )
            }
        }
    }
}
