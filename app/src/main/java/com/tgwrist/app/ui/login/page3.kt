package com.tgwrist.app.ui.login

import android.os.Handler
import android.os.Looper
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
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.TextInputChip
import com.tgwrist.app.utils.setTdlibParameters
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.drinkless.tdlib.TdApi
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

private const val TEST_SERVER_URL = "https://api.tgwrist.top/"
private const val POLL_INTERVAL_MS = 2000L
private const val MAX_POLL_ATTEMPTS = 60

// 1. 全局单例的 OkHttpClient，移除 DoH，但保留了现代 TLS 规范和针对 Wear OS 的超时优化
private val okHttpClient by lazy {
    OkHttpClient.Builder()
        // 核心：强制开启 TLS 1.2+ 的现代密码套件，规范化握手，彻底告别 HttpURLConnection 的 SNI 缺陷
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        // Wear OS 的蓝牙网络代理容易延迟，给足 15 秒的握手和响应时间
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

/**
 * 辅助方法：确保回调在主线程（UI线程）执行，防止 Compose 状态更新引发崩溃
 */
private fun runOnMainThread(action: () -> Unit) {
    Handler(Looper.getMainLooper()).post(action)
}

/**
 * 测试模式：通过 Python 后端自动完成手机号验证流程
 */
private fun testMode(
    apiKey: String,
    errorRequestText: String,
    errorCallback: (String) -> Unit,
    onSendingRequestChanged: (Boolean) -> Unit
) {
    Thread {
        try {
            val baseUrl = TEST_SERVER_URL.trimEnd('/')

            // ===== 1. 获取初始 code =====
            val codeJson = postToServer("$baseUrl/code", apiKey)
            if (codeJson.get("ok")?.asBoolean != true) throw Exception("Server returned ok=false on /code")
            val initialCode = codeJson.get("code")?.asString ?: throw Exception("Failed to get code from server")
            Log.d("TestMode", "Initial code: $initialCode")

            // ===== 2. 获取手机号 =====
            val accountJson = postToServer("$baseUrl/account", apiKey)
            if (accountJson.get("ok")?.asBoolean != true) throw Exception("Server returned ok=false on /account")
            val phone = accountJson.getAsJsonObject("account")?.get("phone")?.asString ?: throw Exception("Failed to get phone from server")
            val phoneNumber = "+$phone"
            Log.d("TestMode", "Phone: $phoneNumber")

            // ===== 3. SetAuthenticationPhoneNumber =====
            val phoneLatch = CountDownLatch(1)
            var phoneError: TdApi.Error? = null

            TgClient.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) {
                if (it is TdApi.Error) phoneError = it
                phoneLatch.countDown()
            }
            phoneLatch.await(30, TimeUnit.SECONDS)

            if (phoneError != null) {
                val err = phoneError!!
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
                        runOnMainThread {
                            errorCallback("$errorRequestText\ncode:${retryError!!.code}\n${retryError!!.message}")
                            onSendingRequestChanged(false)
                        }
                        return@Thread
                    }
                } else {
                    runOnMainThread {
                        errorCallback("$errorRequestText\ncode:${err.code}\n${err.message}")
                        onSendingRequestChanged(false)
                    }
                    return@Thread
                }
            }

            // ===== 4. 轮询 /code =====
            var newCode = initialCode
            var attempts = 0
            while (newCode == initialCode && attempts < MAX_POLL_ATTEMPTS) {
                Thread.sleep(POLL_INTERVAL_MS)
                try {
                    val pollJson = postToServer("$baseUrl/code", apiKey)
                    newCode = pollJson.get("code")?.asString ?: initialCode
                } catch (e: Exception) {
                    Log.w("TestMode", "Poll #$attempts failed: ${e.message}")
                }
                attempts++
            }

            if (newCode == initialCode) {
                runOnMainThread {
                    errorCallback("Timeout: code did not change after $attempts attempts")
                    onSendingRequestChanged(false)
                }
                return@Thread
            }

            Log.d("TestMode", "New code received: $newCode")

            // ===== 5. CheckAuthenticationCode =====
            TgClient.send(TdApi.CheckAuthenticationCode(newCode)) {
                if (it is TdApi.Error) {
                    runOnMainThread {
                        errorCallback("$errorRequestText\ncode:${it.code}\n${it.message}")
                        onSendingRequestChanged(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TestMode", "Error in testMode", e)
            runOnMainThread {
                errorCallback("$errorRequestText\n${e.message}")
                onSendingRequestChanged(false)
            }
        }
    }.start()
}

/**
 * 使用纯净版 OkHttp 进行 POST 请求
 */
private fun postToServer(url: String, apiKey: String): JsonObject {
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val body = """{"apikey":"$apiKey"}""".toRequestBody(jsonMediaType)

    val request = Request.Builder()
        .url(url)
        .post(body)
        .build()

    okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw Exception("HTTP ${response.code}: $errorBody")
        }
        val responseBody = response.body?.string() ?: "{}"
        return Gson().fromJson(responseBody, JsonObject::class.java)
    }
}
