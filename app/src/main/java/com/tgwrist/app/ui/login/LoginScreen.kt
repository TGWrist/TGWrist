package com.tgwrist.app.ui.login

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TdLibInitManage
import com.tgwrist.app.runtime.TdLibInitManage.isPageOnLoginAndNeedReInitTG
import com.tgwrist.app.runtime.TdLibInitManage.needReInitOnDispose
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.runtime.UserManager
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

@SuppressLint("LocalContextConfigurationRead")
@Composable
fun SplashLoginScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val userInfo = UserManager.getActiveUser()
    val pagerState = rememberPagerState(initialPage = if (userInfo == null) 0 else 1) { 5 }
    var passwordHint by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var showDialogText by remember { mutableStateOf("") }
    val mainScreenNeedHandleTdlibLogin by TdLibInitManage.isPageOnLogin.collectAsState()
    var onTestMode by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        TdLibInitManage.isPageOnLogin.value = true

        onDispose {
            TdLibInitManage.isPageOnLogin.value = false
            if (needReInitOnDispose.value) {
                needReInitOnDispose.value = false
                TgClient.reInit()
            }
        }
    }

    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateAuthorizationState::class.java, lifecycleOwner) { update ->
            if (mainScreenNeedHandleTdlibLogin) {
                when(val state = update.authorizationState) {
                    is TdApi.AuthorizationStateWaitPhoneNumber -> {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    }

                    is TdApi.AuthorizationStateWaitCode -> {
                        if (!onTestMode) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(2)
                            }
                        }
                    }

                    is TdApi.AuthorizationStateWaitPassword -> {
                        passwordHint = state.passwordHint
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(3)
                        }
                    }

                    is TdApi.AuthorizationStateReady -> {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(4)
                        }
                    }
                }
            }
        }
    }

    AppScaffold {
        AlertDialog(
            visible = showDialog,
            onDismissRequest = { showDialog = false },
            icon = {
                Icon(
                    Icons.Rounded.Info,
                    modifier = Modifier.size(32.dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(stringResource(R.string.Info)) },
            text = { Text(showDialogText) },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(
                    onClick = {
                        showDialog = false
                    }
                )
            }
        )
        // 分页
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when  (page) {
                0 -> {
                    AnimatedPage(pageIndex = page, pagerState = pagerState) {
                        Page0 {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        }
                    }
                }
                1 -> {
                    AnimatedPage(pageIndex = page, pagerState = pagerState) {
                        Page1(
                            errorCallback = {
                                showDialogText = it
                                showDialog = true
                            },
                            onTestMode = {
                                onTestMode = true
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(3)
                                }
                            }
                        )
                    }
                }
                2 -> {
                    AnimatedPage(pageIndex = page, pagerState = pagerState) {
                        Page2(errorCallback = {
                            showDialogText = it
                            showDialog = true
                        },onBack = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                            TgClient.send(TdApi.LogOut())
                            isPageOnLoginAndNeedReInitTG.value = true
                        })
                    }
                }
                3 -> {
                    AnimatedPage(pageIndex = page, pagerState = pagerState) {
                        Page3(
                            passwordHint = passwordHint,
                            onTestMode = onTestMode,
                            errorCallback = {
                                showDialogText = it
                                showDialog = true
                            },
                            onBack = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                                TgClient.send(TdApi.LogOut())
                                isPageOnLoginAndNeedReInitTG.value = true
                            }
                        )
                    }
                }
                4 -> {
                    AnimatedPage(pageIndex = page, pagerState = pagerState) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center // 居中对齐
                        ) {
                            Text(
                                text = stringResource(R.string.Login_successful),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
