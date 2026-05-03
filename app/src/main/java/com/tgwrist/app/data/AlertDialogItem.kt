package com.tgwrist.app.data

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.AlertDialogDefaults

data class AlertDialogItem(
    val onDismissRequest: () -> Unit = {},

    val confirmButton: () -> Unit = {},

    val title: @Composable () -> Unit = {},

    val modifier: Modifier = Modifier,

    val icon: (@Composable () -> Unit)? = null,

    val text: (@Composable () -> Unit)? = null,

    val verticalArrangement: Arrangement.Vertical =
        AlertDialogDefaults.VerticalArrangement,

    val contentPadding: PaddingValues? = null,

    val properties: DialogProperties = DialogProperties(),

    val content: (ScalingLazyListScope.() -> Unit)? = null,
)
