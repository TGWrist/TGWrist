package com.tgwrist.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.data.SettingItem
import com.tgwrist.app.utils.Config
import com.tgwrist.app.utils.LocalGlobalAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SplashSettingsScreen(index: Int = 0) {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val appState = LocalGlobalAppState.current
    val navController = appState.navController
    val context = LocalContext.current

    // 版本计数器：递增时触发 settings 列表重新构建
    var revision by remember { mutableIntStateOf(0) }

    // 权限请求结果回调
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 用户同意权限后，先设置状态再注册 FCM
            Config.isOpenNotification = true
            registerFcmAndEnableNotification()
        }
        // 无论结果如何，重新构建列表以刷新 UI
        revision++
    }

    // 根据 revision 变化重新构建设置项列表
    val storageFolderSizes by produceState(initialValue = emptyMap(), index, context, revision) {
        value = if (index == 2) {
            withContext(Dispatchers.IO) {
                loadStorageFolderSizes(context)
            }
        } else {
            emptyMap()
        }
    }

    val settings = remember(index, revision, storageFolderSizes) {
        buildSettingItems(
            index = index,
            permissionLauncher = permissionLauncher,
            context = context,
            storageFolderSizes = storageFolderSizes
        )
    }

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
            items(settings.size) { index ->
                when (val item = settings[index]) {
                    is SettingItem.Title -> {
                        ListHeader {
                            Text(
                                //使用预设标题样式
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                text = stringResource(item.titleRes),
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                            )
                        }
                    }
                    is SettingItem.SmallTitle -> {
                        Text(
                            text = stringResource(item.titleRes),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is SettingItem.Click -> {
                        FilledTonalButton(
                            onClick = {
                                item.onClick()
                                if (item.rebuildAfterAction) revision++
                            },
                            transformation = SurfaceTransformation(transformationSpec),
                            icon = item.icon,
                            secondaryLabel = item.descriptionRes?.let { description ->
                                {
                                    Text(
                                        text = description,
                                        maxLines = 10
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        ) {
                            Text(stringResource(item.titleRes))
                        }
                    }

                    is SettingItem.Switch -> {
                        RadioButton(
                            selected = item.isSelected,
                            onSelect = {
                                item.onCheckedChange(!item.isSelected)
                                if (item.rebuildAfterAction) revision++
                            },
                            transformation = SurfaceTransformation(transformationSpec),
                            icon = item.icon,
                            enabled = true,
                            secondaryLabel = item.descriptionRes?.let { description ->
                                {
                                    Text(
                                        text = description,
                                        maxLines = 10
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        ) {
                            Text(stringResource(item.titleRes))
                        }
                    }

                    is SettingItem.ClickAndOpenPage -> {
                        FilledTonalButton(
                            onClick = {
                                item.onClick()
                                if (item.rebuildAfterAction) revision++
                                navController?.navigate(item.pageRoute)
                            },
                            transformation = SurfaceTransformation(transformationSpec),
                            icon = item.icon,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        ) {
                            Text(stringResource(item.titleRes))
                        }
                    }

                    else -> {}
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
