package com.tgwrist.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Text
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.github.panpf.zoomimage.zoom.ScalesCalculator
import com.tgwrist.app.R
import com.tgwrist.app.utils.LocalGlobalAppState
import kotlin.math.sqrt

@Composable
fun IMGViewScreen(url: String?) {
    val appState = LocalGlobalAppState.current

    // 1. 逻辑去重：先确定最终要显示的 URL
    // 如果传入的 url 有值则用传入的，否则用全局 appState 中的
    val targetUrl = if (!url.isNullOrBlank()) url else appState.imgViewUrl

    // 2. 修复 Bug：使用 rememberUpdatedState 确保在 onDispose 时拿到的是最新的 url
    // 如果不加这行，DisposableEffect(Unit) 里的闭包会永远捕获第一次组合时的 url 值
    val currentUrl by rememberUpdatedState(url)

    DisposableEffect(Unit) {
        onDispose {
            // 退出作用域时要执行的代码
            // 逻辑修正：直接判断 currentUrl 是否为空，并直接读取 appState 的最新状态
            if (currentUrl.isNullOrBlank()) {
                if (appState.imgViewUrl.isNotBlank()) {
                    appState.imgViewUrl = ""
                }
            }
        }
    }

    // 如果算出来的 url 也是空的，就不渲染内容（或者你可以加上 else 显示个占位图）
    if (targetUrl.isBlank()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = stringResource(R.string.error_no_image_path))
        }
    }

    // ZoomImage 的缩放状态
    val zoomState = rememberCoilZoomState()

    LaunchedEffect(zoomState) {
        // 最小状态完整显示
        zoomState.zoomable.setContentScale(ContentScale.Fit)

        // 居中
        zoomState.zoomable.setAlignment(Alignment.Center)

        // 启用三步缩放
        zoomState.zoomable.setThreeStepScale(true)

        // Wear OS 圆屏：让最小显示效果接近圆内切正方形
        zoomState.zoomable.setContainerWhitespaceMultiple(
            ((1f - 1f / sqrt(2f)) / 2f)
        )

        // 可选：双击和缩放更稳定一点
        zoomState.zoomable.setScalesCalculator(ScalesCalculator.Fixed)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CoilZoomAsyncImage(
            model = targetUrl,
            contentDescription = "IMG",
            modifier = Modifier.fillMaxSize(),
            zoomState = zoomState,
            scrollBar = null,
            alignment = Alignment.Center,      // 显式指定居中
            contentScale = ContentScale.Fit,   // 显式指定最小状态完整显示
            filterQuality = FilterQuality.Medium,// 提高缩放时的显示质量
        )
    }
}
