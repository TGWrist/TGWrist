package com.tgwrist.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.data.ProxyKind
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.ui.TextInputChip
import com.tgwrist.app.utils.LocalGlobalAppState

@Composable
fun AddProxyScreen() {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val appState = LocalGlobalAppState.current
    val navController = appState.navController
    val context = LocalContext.current

    var type by remember { mutableStateOf(ProxyKind.SOCKS5) }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var httpOnly by remember { mutableStateOf(false) }

    val invalidServerMsg = stringResource(R.string.Proxy_invalid_server)
    val invalidPortMsg = stringResource(R.string.Proxy_invalid_port)
    val invalidSecretMsg = stringResource(R.string.Proxy_invalid_secret)

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        edgeButton = {
            EdgeButton(
                onClick = {
                    val portNumber = port.toIntOrNull()
                    when {
                        server.isBlank() ->
                            Toast.makeText(context, invalidServerMsg, Toast.LENGTH_SHORT).show()
                        portNumber == null || portNumber !in 1..65535 ->
                            Toast.makeText(context, invalidPortMsg, Toast.LENGTH_SHORT).show()
                        type == ProxyKind.MTPROTO && secret.isBlank() ->
                            Toast.makeText(context, invalidSecretMsg, Toast.LENGTH_SHORT).show()
                        else -> {
                            Config.addProxy(
                                server = server.trim(),
                                port = portNumber,
                                type = type,
                                username = username.trim(),
                                password = password,
                                secret = secret.trim(),
                                httpOnly = httpOnly,
                                setActive = true
                            )
                            navController?.popBackStack()
                        }
                    }
                },
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDDFFD)),
                modifier = Modifier.scrollable(
                    listState,
                    orientation = Orientation.Vertical,
                    reverseDirection = true,
                    overscrollEffect = overscroll,
                ),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "save")
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
                        text = stringResource(R.string.Proxy_add),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 代理类型选择
            item {
                Text(
                    text = stringResource(R.string.Proxy_type),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            items(PROXY_KINDS.size) { i ->
                val kind = PROXY_KINDS[i]
                RadioButton(
                    selected = type == kind,
                    onSelect = { type = kind },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(stringResource(kind.titleRes))
                }
            }

            // 服务器
            item {
                TextInputChip(
                    value = server,
                    title = stringResource(R.string.Proxy_server),
                    onTextUpdated = { server = it },
                    transformationSpec = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }

            // 端口
            item {
                TextInputChip(
                    value = port,
                    title = stringResource(R.string.Proxy_port),
                    boardType = KeyboardType.Number,
                    onTextUpdated = { port = it },
                    transformationSpec = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }

            // SOCKS5 / HTTP：用户名 + 密码
            if (type == ProxyKind.SOCKS5 || type == ProxyKind.HTTP) {
                item {
                    TextInputChip(
                        value = username,
                        title = stringResource(R.string.Proxy_username),
                        onTextUpdated = { username = it },
                        transformationSpec = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
                item {
                    TextInputChip(
                        value = password,
                        title = stringResource(R.string.Proxy_password),
                        isPassword = true,
                        onTextUpdated = { password = it },
                        transformationSpec = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // HTTP：仅 HTTP 开关
            if (type == ProxyKind.HTTP) {
                item {
                    RadioButton(
                        selected = httpOnly,
                        onSelect = { httpOnly = !httpOnly },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    ) {
                        Text(stringResource(R.string.Proxy_http_only))
                    }
                }
            }

            // MTProto：secret
            if (type == ProxyKind.MTPROTO) {
                item {
                    TextInputChip(
                        value = secret,
                        title = stringResource(R.string.Proxy_secret),
                        onTextUpdated = { secret = it },
                        transformationSpec = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }
        }
    }
}

private val ProxyKind.titleRes: Int
    get() = when (this) {
        ProxyKind.SOCKS5 -> R.string.Proxy_type_socks5
        ProxyKind.HTTP -> R.string.Proxy_type_http
        ProxyKind.MTPROTO -> R.string.Proxy_type_mtproto
    }

private val PROXY_KINDS = listOf(ProxyKind.SOCKS5, ProxyKind.HTTP, ProxyKind.MTPROTO)
