package com.tgwrist.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SurfaceTransformation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ElegantRadioButtonItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    label: @Composable RowScope.() -> Unit,
    transformation: SurfaceTransformation,
    secondaryLabel: (@Composable RowScope.() -> Unit)? = null,
    icon: @Composable (BoxScope.() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val viewConfig = LocalViewConfiguration.current
    var isLongPress by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        var pressJob: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    isLongPress = false
                    pressJob = launch {
                        delay(viewConfig.longPressTimeoutMillis.milliseconds)
                        isLongPress = true
                        //println("触发长按事件")
                        onLongClick.invoke()
                    }
                }
                is PressInteraction.Release -> {
                    pressJob?.cancel()
                }
                is PressInteraction.Cancel -> {
                    pressJob?.cancel()
                    isLongPress = false
                }
            }
        }
    }

    RadioButton(
        label = label,
        secondaryLabel = secondaryLabel,
        icon = icon,
        selected = selected,
        onSelect = {
            if (!isLongPress) {
                //println("触发普通点击")
                onSelect.invoke()
            }
            // ✨ 修复 2：极速同步重置！
            // 无论刚才是否拦截了点击，只要手指抬起了，立刻把长按标记洗白。
            // 绝对保证用户的下一次快速点击进来时，面对的是干净的状态。
            isLongPress = false
        },
        interactionSource = interactionSource,
        transformation = transformation,
        modifier = modifier
    )
}
