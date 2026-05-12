package com.tgwrist.app.utils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgwrist.app.runtime.TdLibInitManage
import com.tgwrist.app.runtime.TgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    // 内部使用的可变状态，下划线开头是命名规范
    private val _isLoading = MutableStateFlow(true)

    // 对外（给 Activity 或 Compose）暴露的只读状态
    // 这样可以防止外部代码不小心修改了状态，保证数据的单向流动
    val isLoading = _isLoading.asStateFlow()
    val mainPage1FristScrollTop = MutableStateFlow(false)

    init {
        // ViewModel 被创建时，立刻开始执行初始化任务
        performInitialization()
    }

    private fun performInitialization() {
        // viewModelScope 是系统提供的专门给 ViewModel 用的协程作用域
        // 当 ViewModel 被销毁时，这里的协程会自动取消，绝对不会内存泄漏
        viewModelScope.launch {
            try {
                viewModelScope.launch(Dispatchers.IO) {
                    // 初始化 TdLib 管理器
                    TdLibInitManage.init()
                    // 初始化 TDLib 客户端
                    TgClient.close()
                    TgClient.init()
                }
            } catch (e: Exception) {
                // 如果发生了异常，可以在这里记录日志
                e.printStackTrace()
            } finally {
                // ⚠️ 划重点：使用 finally 块！
                // 无论初始化是成功还是崩溃，最终都必须把 isLoading 设为 false，
                // 保证启动页一定会被放行，绝对不会卡死用户！
                _isLoading.value = false
            }
        }
    }
}
