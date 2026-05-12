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
import androidx.compose.material.icons.rounded.ChevronLeft
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
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import com.tgwrist.app.ui.TextInputChip
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.utils.setTdlibParameters
import org.drinkless.tdlib.TdApi
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Composable
internal fun Page3(
    passwordHint: String,
    onTestMode: Boolean,
    errorCallback: (String) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    var password by remember { mutableStateOf("") }
    var sendingRequest by remember { mutableStateOf(false) }

    // 字符串变量
    val errorRequestText = stringResource(R.string.Request_error)

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        edgeButton = {
            EdgeButton(
                onClick = {
                    if (password.isBlank()) {
                        onBack.invoke()
                    } else {
                        sendingRequest = true
                        if (onTestMode) {
                            testMode(
                                apiKey = password,
                                errorRequestText = errorRequestText,
                                errorCallback = errorCallback,
                                onSendingRequestChanged = { sendingRequest = it }
                            )
                        } else {
                            TgClient.send(TdApi.CheckAuthenticationPassword(password)) {
                                if (it is TdApi.Error) {
                                    val errorText = "$errorRequestText\ncode:${it.code}\n${it.message}"
                                    errorCallback.invoke(errorText)
                                    sendingRequest = false
                                    Log.e("TDLib", it.toString())
                                }
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
                if (password.isBlank()) {
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
                        text = stringResource(R.string.Password),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }
            item {
                // 密码输入框
                TextInputChip(
                    value = password,
                    title = stringResource(R.string.Enter_password),
                    placeholder = passwordHint,
                    isPassword = true,
                    onTextUpdated = { str ->
                        password = str
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

private const val TEST_SERVER_URL = "https://api.tgwrist.top/" // 测试后端地址
private const val POLL_INTERVAL_MS = 2000L
private const val MAX_POLL_ATTEMPTS = 60 // 最多轮询 60 次（约 2 分钟）

/**
 * 测试模式：通过 Python 后端自动完成手机号验证流程
 * @param apiKey 用户在密码框中输入的 API Key（后端鉴权用）
 * @param errorRequestText 错误提示前缀文本（来自 R.string.Request_error）
 * @param errorCallback 错误回调，用于显示错误弹窗
 * @param onSendingRequestChanged 控制 sendingRequest 状态
 */
private fun testMode(
    apiKey: String,
    errorRequestText: String,
    errorCallback: (String) -> Unit,
    onSendingRequestChanged: (Boolean) -> Unit
) {
    // TODO 代码能用但有Bug，记得改
    Thread {
        try {
            val baseUrl = TEST_SERVER_URL.trimEnd('/')

            // ===== 1. 获取初始 code 并存储 =====
            val codeJson = postToServer("$baseUrl/code", apiKey)
            if (codeJson.get("ok")?.asBoolean != true) {
                throw Exception("Server returned ok=false on /code")
            }
            val initialCode = codeJson.get("code")?.asString
                ?: throw Exception("Failed to get code from server")
            Log.d("TestMode", "Initial code: $initialCode")

            // ===== 2. 获取手机号 =====
            val accountJson = postToServer("$baseUrl/account", apiKey)
            if (accountJson.get("ok")?.asBoolean != true) {
                throw Exception("Server returned ok=false on /account")
            }
            val phone = accountJson.getAsJsonObject("account")?.get("phone")?.asString
                ?: throw Exception("Failed to get phone from server")
            val phoneNumber = "+$phone"
            Log.d("TestMode", "Phone: $phoneNumber")

            // ===== 3. SetAuthenticationPhoneNumber =====
            val phoneLatch = CountDownLatch(1)
            var phoneError: TdApi.Error? = null

            TgClient.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) {
                if (it is TdApi.Error) {
                    phoneError = it
                }
                phoneLatch.countDown()
            }
            phoneLatch.await(30, TimeUnit.SECONDS)

            if (phoneError != null) {
                val err = phoneError!!
                // 处理 setTdlibParameters 未初始化的情况（与 page1 一致）
                if (err.message == "Initialization parameters are needed: call setTdlibParameters first") {
                    TGWrist.context.setTdlibParameters(null)
                    val retryLatch = CountDownLatch(1)
                    var retryError: TdApi.Error? = null
                    TgClient.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) {
                        if (it is TdApi.Error) retryError = it
                        retryLatch.countDown()
                    }
                    retryLatch.await(30, TimeUnit.SECONDS)
                    if (retryError != null) {
                        val e = retryError!!
                        errorCallback("$errorRequestText\ncode:${e.code}\n${e.message}")
                        onSendingRequestChanged(false)
                        Log.e("TestMode", e.toString())
                        return@Thread
                    }
                } else {
                    errorCallback("$errorRequestText\ncode:${err.code}\n${err.message}")
                    onSendingRequestChanged(false)
                    Log.e("TestMode", err.toString())
                    return@Thread
                }
            }

            // ===== 4. 轮询 /code，等待 code 变化 =====
            var newCode = initialCode
            var attempts = 0
            while (newCode == initialCode && attempts < MAX_POLL_ATTEMPTS) {
                Thread.sleep(POLL_INTERVAL_MS)
                try {
                    val pollJson = postToServer("$baseUrl/code", apiKey)
                    newCode = pollJson.get("code")?.asString ?: initialCode
                } catch (e: Exception) {
                    Log.w("TestMode", "Poll #$attempts failed: ${e.message}")
                    // 网络波动时继续重试，不立即退出
                }
                attempts++
                Log.d("TestMode", "Poll #$attempts, code: $newCode")
            }

            if (newCode == initialCode) {
                errorCallback("Timeout: code did not change after $attempts attempts")
                onSendingRequestChanged(false)
                return@Thread
            }

            Log.d("TestMode", "New code received: $newCode")

            // ===== 5. CheckAuthenticationCode =====
            TgClient.send(TdApi.CheckAuthenticationCode(newCode)) {
                if (it is TdApi.Error) {
                    val errorText = "$errorRequestText\ncode:${it.code}\n${it.message}"
                    errorCallback(errorText)
                    onSendingRequestChanged(false)
                    Log.e("TestMode", it.toString())
                }
                // 成功时由 LoginScreen 的 AuthorizationStateReady 监听自动跳转
            }
        } catch (e: Exception) {
            Log.e("TestMode", "Error in testMode", e)
            errorCallback("$errorRequestText\n${e.message}")
            onSendingRequestChanged(false)
        }
    }.start()
}

/**
 * 向后端发送 POST 请求（携带 apikey）并解析 JSON 响应
 */
private fun postToServer(url: String, apiKey: String): JsonObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        doOutput = true
        connectTimeout = 10_000
        readTimeout = 10_000
    }
    try {
        val body = """{"apikey":"$apiKey"}"""
        connection.outputStream.use { os ->
            os.write(body.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val errorBody = try {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            throw Exception("HTTP $responseCode: $errorBody")
        }

        val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
        return Gson().fromJson(responseBody, JsonObject::class.java)
    } finally {
        connection.disconnect()
    }
}
