package com.tgwrist.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.FilledTonalButton
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
import com.tgwrist.app.data.ProxyInfo
import com.tgwrist.app.data.ProxyKind
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.ElegantRadioButtonItem
import com.tgwrist.app.ui.StatusTimeText
import com.tgwrist.app.utils.LocalGlobalAppState

@Composable
fun NetworkSettingsScreen() {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val appState = LocalGlobalAppState.current
    val navController = appState.navController

    val proxies by Config.proxiesFlow.collectAsState()
    val activeProxyId by Config.activeProxyIdFlow.collectAsState()

    // 待删除的代理（非空时弹出确认对话框）
    var pendingDelete by remember { mutableStateOf<ProxyInfo?>(null) }

    AppScaffold(timeText = { StatusTimeText() }) {
        ScreenScaffold(
            scrollState = listState,
            overscrollEffect = overscroll,
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
                            text = stringResource(R.string.Proxy_settings),
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 直连选项
                item {
                    RadioButton(
                        selected = activeProxyId == null,
                        onSelect = { Config.setActiveProxy(null) },
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
                        secondaryLabel = {
                            Text(stringResource(R.string.Proxy_direct_connection_desc))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    ) {
                        Text(stringResource(R.string.Proxy_direct_connection))
                    }
                }

                // 没有代理时的提示
                if (proxies.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.Proxy_none_hint),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 代理列表（长按删除）
                items(proxies.size) { i ->
                    val proxy = proxies[i]
                    ElegantRadioButtonItem(
                        selected = activeProxyId == proxy.id,
                        onSelect = { Config.setActiveProxy(proxy.id) },
                        onLongClick = { pendingDelete = proxy },
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(Icons.Rounded.VpnKey, contentDescription = null) },
                        label = {
                            Text(proxy.type.displayName)
                        },
                        secondaryLabel = {
                            Text("${proxy.server}:${proxy.port}")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }

                // 添加代理
                item {
                    FilledTonalButton(
                        onClick = { navController?.navigate(Destinations.ADD_PROXY) },
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    ) {
                        Text(stringResource(R.string.Proxy_add))
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // 删除确认对话框
    val toDelete = pendingDelete
    AlertDialog(
        visible = toDelete != null,
        onDismissRequest = { pendingDelete = null },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    toDelete?.let { Config.removeProxy(it.id) }
                    pendingDelete = null
                }
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(
                onClick = { pendingDelete = null }
            )
        },
        title = { Text(stringResource(R.string.Proxy_delete_confirm_title)) },
        text = { Text(stringResource(R.string.Proxy_delete_confirm_body)) }
    )
}

private val ProxyKind.displayName: String
    get() = when (this) {
        ProxyKind.SOCKS5 -> "SOCKS5"
        ProxyKind.HTTP -> "HTTP"
        ProxyKind.MTPROTO -> "MTProto"
    }
