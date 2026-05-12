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
import androidx.compose.runtime.Composable
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
import com.tgwrist.app.ui.TextInputChip
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.utils.setTdlibParameters
import org.drinkless.tdlib.TdApi

@Composable
internal fun Page1(
    errorCallback: (String) -> Unit,
    onTestMode: () -> Unit
) {
    //val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    var phoneNumber by remember { mutableStateOf("+") }
    var sendingRequest by remember { mutableStateOf(false) }

    // 字符串变量
    val errorRequestText = stringResource(R.string.Request_error)
    val invalidPhoneNumber = stringResource(R.string.Invalid_phone_number)

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
                        TgClient.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) {
                            if (it is TdApi.Error) {
                                if (it.message == "Initialization parameters are needed: call setTdlibParameters first") {
                                    TGWrist.context.setTdlibParameters(null)
                                    TgClient.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) { it1 ->
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
        }
    }
}
