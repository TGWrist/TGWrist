package com.tgwrist.app.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * 全局事件总线（基于 SharedFlow）
 */
object GlobalEventBus {

    private val _events = MutableSharedFlow<Any>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events = _events.asSharedFlow()

    //private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun send(event: Any) {
        // 直接发射，如果缓冲区未满（或配置了DROP_OLDEST），必定成功并立即按顺序入队
        _events.tryEmit(event)
    }

    /**
     * 订阅事件（支持生命周期 / 非生命周期两种模式）
     */
    inline fun <reified T> subscribe(
        scope: CoroutineScope,
        lifecycleOwner: LifecycleOwner? = null,
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
        crossinline onEvent: (T) -> Unit
    ): Job {
        return scope.launch {
            if (lifecycleOwner != null) {
                lifecycleOwner.repeatOnLifecycle(minActiveState) {
                    events
                        .filterIsInstance<T>()
                        .collect { onEvent(it) }
                }
            } else {
                events
                    .filterIsInstance<T>()
                    .collect { onEvent(it) }
            }
        }
    }
}
