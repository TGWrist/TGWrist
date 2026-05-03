package com.tgwrist.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tgwrist.app.utils.LocalGlobalAppState

@Composable
fun TestScreen() {
    // 进入方法：关于页面点击6次软件图标，再长按软件图标

    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val context = LocalContext.current
    val appState = LocalGlobalAppState.current
    val navController = appState.navController

    var selected1 by remember { mutableStateOf(false) }
    var selected2 by remember { mutableStateOf(false) }
    var selected3 by remember { mutableStateOf(false) }

    val selecting = selected1 || selected2 || selected3

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
                item {
                    Text(
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        text = "Test Page",
                        color = Color(0xFFAAAAAA),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }

                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        onClick = {
                            if (selecting) {
                                selected1 = !selected1
                            } else {
                                // 普通点击逻辑
                            }
                        },
                        onLongClick = {
                            selected1 = true
                        },
                        onLongClickLabel = "Select",
                        appName = {
                            Text(if (selected1) "Selected" else "TG Wrist")
                        },
                        title = {
                            Text("Test title 1")
                        },
                        content = {
                            Text("Long press this card to select it.")
                        },
                        appImage = {
                            Icon(
                                imageVector = if (selected1) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.Chat
                                },
                                contentDescription = null,
                                modifier = Modifier.size(CardDefaults.AppImageSize)
                            )
                        },
                        time = {
                            Text("12:30")
                        },
                        colors = if (selected1) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                appNameColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                timeColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            CardDefaults.cardColors()
                        },
                        border = if (selected1) {
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            null
                        }
                    )
                }

                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        onClick = {
                            if (selecting) {
                                selected2 = !selected2
                            } else {
                                // 普通点击逻辑
                            }
                        },
                        onLongClick = {
                            selected2 = true
                        },
                        onLongClickLabel = "Select",
                        appName = {
                            Text(if (selected2) "Selected" else "TG Wrist")
                        },
                        title = {
                            Text("Test title 2")
                        },
                        content = {
                            Text("When selecting, tap this card to toggle it.")
                        },
                        appImage = {
                            Icon(
                                imageVector = if (selected2) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.Chat
                                },
                                contentDescription = null,
                                modifier = Modifier.size(CardDefaults.AppImageSize)
                            )
                        },
                        time = {
                            Text("12:31")
                        },
                        colors = if (selected2) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                appNameColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                timeColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            CardDefaults.cardColors()
                        },
                        border = if (selected2) {
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            null
                        }
                    )
                }

                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        onClick = {
                            if (selecting) {
                                selected3 = !selected3
                            } else {
                                // 普通点击逻辑
                            }
                        },
                        onLongClick = {
                            selected3 = true
                        },
                        onLongClickLabel = "Select",
                        appName = {
                            Text(if (selected3) "Selected" else "TG Wrist")
                        },
                        title = {
                            Text("Test title 3")
                        },
                        content = {
                            Text("If all cards are unselected, selection mode exits automatically.")
                        },
                        appImage = {
                            Icon(
                                imageVector = if (selected3) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.Chat
                                },
                                contentDescription = null,
                                modifier = Modifier.size(CardDefaults.AppImageSize)
                            )
                        },
                        time = {
                            Text("12:32")
                        },
                        colors = if (selected3) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                appNameColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                timeColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            CardDefaults.cardColors()
                        },
                        border = if (selected3) {
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            null
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
