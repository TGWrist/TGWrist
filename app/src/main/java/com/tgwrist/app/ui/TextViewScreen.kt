package com.tgwrist.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.tgwrist.app.ui.message.info.MessageTextView
import com.tgwrist.app.utils.LocalGlobalAppState
import org.drinkless.tdlib.TdApi

@Composable
fun TextViewScreen(
    text: String?,
    textId: Long?,
) {
    val appState = LocalGlobalAppState.current
    val navController = appState.navController ?: return
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    // 只有当最大滚动值 > 0 时，才认为需要显示滚动条
    val isScrollable = scrollState.maxValue > 0
    var tgText by remember(textId) { mutableStateOf<TdApi.FormattedText?>(null) }

    LaunchedEffect(text, textId) {
        if (text != null) return@LaunchedEffect

        if (textId == null) {
            navController.popBackStack()
            return@LaunchedEffect
        }

        tgText = appState.tgTextIdMap[textId]
        if (tgText == null) {
            navController.popBackStack()
        } else {
            appState.tgTextIdMap.remove(textId) // 取出后立即移除，避免占用内存
        }
    }

    AppScaffold(timeText = { StatusTimeText() }) {
        ScreenScaffold(
            scrollState = scrollState,
            scrollIndicator = {
                // 只有在可以滚动时才显示指示器
                if (isScrollable) {
                    ScrollIndicator(state = scrollState)
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .requestFocusOnHierarchyActive()
                    .rotaryScrollable(
                        RotaryScrollableDefaults.behavior(scrollableState = scrollState),
                        focusRequester
                    )
                    .verticalScroll(scrollState)
                    .padding(horizontal = 10.dp, vertical = 20.dp)
                    .padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    text != null -> {
                        SelectionContainer {
                            Text(text)
                        }
                    }
                    tgText != null -> {
                        MessageTextView(
                            text = tgText?.text.orEmpty(),
                            entities = tgText?.entities,
                            modifier = Modifier,
                            navController = navController,
                        )
                    }
                }
            }
        }
    }
}
