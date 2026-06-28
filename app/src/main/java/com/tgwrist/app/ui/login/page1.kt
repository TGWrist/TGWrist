package com.tgwrist.app.ui.login

import android.util.Log
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.TextInputChip
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.setTdlibParameters
import org.drinkless.tdlib.TdApi
import kotlin.time.Duration.Companion.milliseconds

/**
 * 规范化手机号。
 * 不论用户是否输入了加号、是否输入了空格等非数字字符，
 * 或是输入了 ➕ 这类表情加号，最终都统一为 "+" 加纯数字的格式。
 * 例如输入 "➕1 775 442 2228"、"1 775-442-2228" 等都会得到 "+17754422228"。
 */
private fun normalizePhoneNumber(input: String): String {
    val digits = input.filter { it.isDigit() }
    return "+$digits"
}

@Composable
internal fun Page1(
    errorCallback: (String) -> Unit,
    onTestMode: () -> Unit,
    onQrLogin: () -> Unit
) {
    //val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val navController = LocalGlobalAppState.current.navController
    var phoneNumber by remember { mutableStateOf("") }
    var sendingRequest by remember { mutableStateOf(false) }
    var showNetworkOption by remember { mutableStateOf(false) }

    // 字符串变量
    val errorRequestText = stringResource(R.string.Request_error)
    val invalidPhoneNumber = stringResource(R.string.Invalid_phone_number)

    // 点击EdgeButton后计时5秒，如果仍停留在本页且请求未完成，则展开网络设置选项
    LaunchedEffect(sendingRequest) {
        if (sendingRequest) {
            kotlinx.coroutines.delay(5000.milliseconds)
            if (sendingRequest) {
                showNetworkOption = true
            }
        } else {
            showNetworkOption = false
        }
    }

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        edgeButton = {
            EdgeButton(
                onClick = {
                    sendingRequest = true
                    if (phoneNumber == "GoogleTest") {
                        // 谷歌测试模式
                        onTestMode.invoke()
                    } else {
                        val normalizedPhoneNumber = normalizePhoneNumber(phoneNumber)
                        TgClient.send(TdApi.SetAuthenticationPhoneNumber(normalizedPhoneNumber, null)) {
                            if (it is TdApi.Error) {
                                if (it.message == "Initialization parameters are needed: call setTdlibParameters first") {
                                    TGWrist.context.setTdlibParameters(null)
                                    TgClient.send(TdApi.SetAuthenticationPhoneNumber(normalizedPhoneNumber, null)) { it1 ->
                                        if (it1 is TdApi.Error) {
                                            val errorText = "$errorRequestText\ncode:${it1.code}\n${it1.message}"
                                            errorCallback.invoke(errorText)
                                            sendingRequest = false
                                            Log.e("TDLib", it.toString())
                                        }
                                    }
                                } else {
                                    val errorText = if (it.message == "PHONE_NUMBER_INVALID") "$errorRequestText\ncode:${it.code}\n${it.message}\n${invalidPhoneNumber}"
                                    else "$errorRequestText\ncode:${it.code}\n${it.message}"
                                    errorCallback.invoke(errorText)
                                    sendingRequest = false
                                    Log.e("TDLib", it.toString())
                                }
                            }
                        }
                    }
                },
                buttonSize = EdgeButtonSize.Medium,
                enabled = !sendingRequest && phoneNumber.isNotBlank(),
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
                Icon(Icons.Rounded.ChevronRight, contentDescription = "go")
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
                        text = stringResource(R.string.Login),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }
            // 手机号输入提示
            item {
                Text(
                    text = stringResource(R.string.Phone_number_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                // 手机号输入框
                TextInputChip(
                    value = phoneNumber,
                    title = stringResource(R.string.Phone_Number),
                    onTextUpdated = { str ->
                        phoneNumber = str
                    },
                    transformationSpec = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .padding(5.dp)
                )
            }
            // 二维码登录入口
            if (!sendingRequest) {
                item {
                    FilledTonalButton(
                        onClick = {
                            onQrLogin()
                            TgClient.send(TdApi.RequestQrCodeAuthentication(LongArray(0))) {
                                if (it is TdApi.Error) {
                                    if (it.message == "Initialization parameters are needed: call setTdlibParameters first") {
                                        TGWrist.context.setTdlibParameters(null)
                                        TgClient.send(TdApi.RequestQrCodeAuthentication(LongArray(0))) { it1 ->
                                            if (it1 is TdApi.Error) {
                                                errorCallback.invoke("$errorRequestText\ncode:${it1.code}\n${it1.message}")
                                                Log.e("TDLib", it1.toString())
                                            }
                                        }
                                    } else {
                                        errorCallback.invoke("$errorRequestText\ncode:${it.code}\n${it.message}")
                                        Log.e("TDLib", it.toString())
                                    }
                                }
                            }
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    ) {
                        Text(stringResource(R.string.Login_with_QR))
                    }
                }
            }
            if (sendingRequest) {
                item {
                    Text(
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Phone_Number_Tip),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }
            if (showNetworkOption) {
                // 网络可能有问题的提示
                item {
                    Text(
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Login_network_issue_tip),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
                // 网络设置
                item {
                    FilledTonalButton(
                        onClick = {
                            navController?.navigate(Destinations.NETWORK)
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = {
                            Icon(Icons.Rounded.Wifi, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    ) {
                        Text(stringResource(R.string.Network_settings))
                    }
                }
            }
        }
    }
}
