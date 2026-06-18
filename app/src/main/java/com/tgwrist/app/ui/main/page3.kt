package com.tgwrist.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
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
import com.tgwrist.app.data.UserInfo
import com.tgwrist.app.runtime.TdLibInitManage
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.runtime.UserManager
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.ElegantRadioButtonItem
import com.tgwrist.app.utils.LocalGlobalAppState
import org.drinkless.tdlib.TdApi

@Composable
internal fun Page3() {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()

    val appState = LocalGlobalAppState.current
    val navController = appState.navController

    val users by UserManager.users.collectAsState()

    var wantDeleteAccount by remember { mutableStateOf<UserInfo?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    AlertDialog(
        visible = showDialog,
        onDismissRequest = {
            showDialog = false
            wantDeleteAccount = null
        },
        icon = {
            Icon(
                Icons.Rounded.Info,
                modifier = Modifier.size(32.dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.Confirm)) },
        text = { Text(stringResource(R.string.Confirm_delete_account, wantDeleteAccount?.userName ?: "")) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    wantDeleteAccount?.let { wda ->
                        if (UserManager.getActiveUser()?.userId == wda.userId) {
                            TgClient.send(TdApi.LogOut())
                        } else {
                            UserManager.removeUser(wda.userId)
                            TGWrist.context.filesDir.listFiles()?.find { it.name == wda.userId.toString() && it.isDirectory }?.deleteRecursively()
                        }
                    }
                    wantDeleteAccount = null
                    showDialog = false
                }
            )
        }
    )

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
            item {
                ListHeader {
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Account),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }
            items(items = users, key = { item -> item.userId }) { item ->
                ElegantRadioButtonItem(
                    label = {
                        Text(
                            item.userName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = item.isActive,
                    onSelect = {
                        if (!item.isActive) {
                            UserManager.switchActiveUser(item.userId)
                        }
                    },
                    onLongClick = {
                        wantDeleteAccount = item
                        showDialog = true
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }
            item {
                FilledTonalButton(
                    onClick = {
                        //TgClient.close()
                        TdLibInitManage.isPageOnLogin.value = true
                        TdLibInitManage.needReInitOnDispose.value = true
                        TgClient.reInit()
                        navController?.navigate(Destinations.LOGIN)
                    },
                    label = { Text(stringResource(R.string.Add_account)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                )
            }
            item {
                FilledTonalButton(
                    onClick = {
                        wantDeleteAccount = UserManager.getActiveUser()
                        showDialog = true
                    },
                    label = { Text(stringResource(R.string.Logout)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                )
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
