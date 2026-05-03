package com.tgwrist.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.tgwrist.app.R
import com.tgwrist.app.ui.message.info.handleUrlNavigation
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.getAppVersion
import com.tgwrist.app.utils.openInBrowser

@Composable
fun AboutScreen() {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val context = LocalContext.current
    val appState = LocalGlobalAppState.current
    val navController = appState.navController
    var testEntryTapCount by rememberSaveable { mutableIntStateOf(0) }

    val appIconImageDrawable = remember {
        context.packageManager.getApplicationIcon(context.packageName)
    }

    AppScaffold(timeText = { StatusTimeText() }) {
        ScreenScaffold(
            overscrollEffect = overscroll,
            scrollState = listState,
            modifier = Modifier.fillMaxSize()
        ) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                overscrollEffect = overscroll,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 应用图标
                item {
                    Box(
                        modifier = Modifier
                            .size(55.dp)
                            .clip(CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        testEntryTapCount += 1
                                    },
                                    onLongPress = {
                                        if (testEntryTapCount >= 6) {
                                            navController?.navigate(Destinations.TEST)
                                        }
                                    }
                                )
                            }
                    ) {
                        Image(
                            painter = rememberDrawablePainter(appIconImageDrawable),
                            contentDescription = null,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(CircleShape)
                                .align(Alignment.Center)
                        )
                    }
                }

                // 应用名称 + 版本 + 发布者
                item {
                    ListHeader {
                        Text(
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            text = stringResource(id = R.string.app_name) + "\n" +
                                    stringResource(id = R.string.Version) + " ${context.getAppVersion()}",
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Text(
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        text = "${stringResource(id = R.string.Releases_by)} GREYELASTIC GOOD HAND",
                        color = Color(0xFFAAAAAA),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }

                // 官方频道
                item {
                    TitleCard(
                        title = { Text(stringResource(R.string.official_channel)) },
                        onClick = {
                            navController?.let {
                                handleUrlNavigation("https://t.me/tgwrist", context, it)
                            }
                        }
                    ) {
                        Text(
                            text = "t.me/tgwrist",
                            color = Color(0xFF64B5F6)
                        )
                    }
                }

                // 官方群组
                item {
                    TitleCard(
                        title = { Text(stringResource(R.string.official_group)) },
                        onClick = {
                            navController?.let {
                                handleUrlNavigation("https://t.me/TGwristChat", context, it)
                            }
                        }
                    ) {
                        Text(
                            text = "t.me/TGwristChat",
                            color = Color(0xFF64B5F6)
                        )
                    }
                }

                // 开源代码
                item {
                    TitleCard(
                        title = { Text(stringResource(R.string.source_code)) },
                        onClick = {
                            openInBrowser(context, "https://github.com/TGWrist/TGWrist")
                        }
                    ) {
                        Text(
                            text = "github.com/TGWrist/TGWrist",
                            color = Color(0xFF64B5F6)
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
