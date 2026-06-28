package com.tgwrist.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import com.tgwrist.app.runtime.CALL_STATE_NONE
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.Config.connectionState
import com.tgwrist.app.runtime.TgCallManager

@Composable
fun StatusTimeText(text: String? = null) {
    //val style = TimeTextDefaults.timeTextStyle()
    val connectionState by connectionState.collectAsState()
    val isCalling by TgCallManager.callState.collectAsState()
    TimeText {time ->
        timeTextCurvedText(text ?: time)
        //timeTextSeparator(style)
        val color = when (connectionState) {
            Config.ConnectionState.Ready ->  null
            Config.ConnectionState.Connecting -> Color(0xFFFD4C4C)
            Config.ConnectionState.ConnectingToProxy -> Color(0xFFFD4C4C)
            Config.ConnectionState.Updating -> Color(0xFF568AFD)
            Config.ConnectionState.WaitingForNetwork -> Color(0xFFFD4C4C)
            else -> if (isCalling != CALL_STATE_NONE) Color(0xFF4CAF50) else Color(0xFFFD4C4C)
        }
        // 绘制圆点
        color?.let {
            curvedComposable {
                Box(
                    modifier = Modifier
                        .padding(start = 7.dp)
                        .size(7.dp) // 设置圆点的大小
                        .background(color = color, shape = CircleShape) // 设置背景和圆形裁剪
                )
            }
        }

    }
}
