package com.tgwrist.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.PagerScaffoldDefaults
import com.tgwrist.app.data.UserInfoEvent
import com.tgwrist.app.ui.StatusTimeText
import com.tgwrist.app.runtime.ActiveUserSwitch
import com.tgwrist.app.runtime.GlobalEventBus
import com.tgwrist.app.runtime.TgClient

const val MAIN_LIST = "MAIN"
const val ARCHIVE_LIST = "ARCHIVE"
@Composable
fun SplashMainScreen() {
    val pagerState = rememberPagerState(initialPage = 1) { 4 }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        GlobalEventBus.subscribe<UserInfoEvent>(
            scope = this,
            lifecycleOwner = lifecycleOwner
        ) { event ->
            // 处理用户切换事件
            if (event.message == ActiveUserSwitch) {
                TgClient.reInit()
            }
        }
    }


    AppScaffold(timeText = { StatusTimeText() }) {
        HorizontalPagerScaffold(pagerState = pagerState) {
            HorizontalPager(
                state = pagerState,
                flingBehavior =
                    PagerScaffoldDefaults.snapWithSpringFlingBehavior(state = pagerState),
                rotaryScrollableBehavior = null,
            ) { page ->
                when(page) {
                    0 -> {
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            Page0()
                        }
                    }
                    1 -> {
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            Page1()
                        }
                    }
                    2 -> {
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            Page2()
                        }
                    }
                    3 -> {
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            Page3()
                        }
                    }
                }
            }
        }
    }
}
