package com.tgwrist.app.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

@Composable
fun TGWristTheme(
    content: @Composable () -> Unit
) {
    /**
     * Empty theme to customize for your app.
     * See: https://developer.android.com/jetpack/compose/designsystems/custom
     */
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = dynamicColorScheme(context) ?: wearColorScheme, // 应用这个蓝色的配色方案
        typography = MaterialTheme.typography,
        content = content
    )
}