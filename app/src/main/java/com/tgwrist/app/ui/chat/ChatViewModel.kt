package com.tgwrist.app.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import com.tgwrist.app.data.MediaData

/**
 * 单个聊天页（[ChatScreen]）的 UI 状态容器。
 *
 * 通过 NavBackStackEntry 作为 ViewModelStoreOwner 时，每个聊天导航条目都会得到一份独立的
 * [ChatViewModel]，跟随该聊天页一起销毁，不会跨聊天泄漏。
 */
class ChatViewModel(val chatId: Long) : ViewModel() {

    /** 当前用户在 Page1 选中、待发送的媒体列表。 */
    val mediaChose: SnapshotStateList<MediaData> = mutableStateListOf()
}
