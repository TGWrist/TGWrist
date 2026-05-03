package com.tgwrist.app.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import com.tgwrist.app.data.SharedMessageInfoData
import com.tgwrist.app.data.SharedMessageInfoKey
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.getValue
import kotlin.collections.setValue

/**
 * ChatScreen 实例的唯一标识：chatId + instanceId
 * 用于区分同一chatId打开多次的情况（如：聊天A -> 点击链接跳转到聊天A）
 */
data class ChatScreenKey(
    val chatId: Long,
    val instanceId: Long
)

// 聊天滚动状态缓存（基于 item key 而非 index，新消息到达后仍能正确恢复位置）
data class ChatScrollState(
    val firstVisibleItemKey: String,
    val firstVisibleItemScrollOffset: Int,
    // 后备方案：保存 anchor item 在总 item 数中的位置比例
    // 当原始 key 找不到时（如消息被删除），根据此比例恢复到大概位置
    val fallbackIndexRatio: Float
)

// 定义数据中心：这里可以随意增加、修改你需要的参数
class GlobalAppState {
    // 使用 mutableStateOf 保证修改后自动触发 UI 刷新

    // 参数: compose全局导航控制器
    var navController: NavHostController? by mutableStateOf(null)

    // IMGView 显示图片url
    var imgViewUrl by mutableStateOf("")

    // tg 消息记录器
    var tgTextIdMap = mutableStateMapOf<Long, TdApi.FormattedText>()

    // 消息详情
    val sharedMessageInfo = mutableStateMapOf<SharedMessageInfoKey, SharedMessageInfoData>()

    // ChatScreen 实例计数器，用于生成唯一 instanceId
    private val chatScreenInstanceCounter = AtomicLong(0)

    /** 为新的 ChatScreen 实例分配一个唯一 ID */
    fun nextChatScreenInstanceId(): Long = chatScreenInstanceCounter.incrementAndGet()

    // 聊天页面滚动状态缓存 (ChatScreenKey -> scrollState)
    // 使用 ChatScreenKey 而非单纯的 chatId，避免同一 chatId 多次打开时覆盖
    val chatScrollStates = mutableStateMapOf<ChatScreenKey, ChatScrollState>()
}

// 创建一个 CompositionLocal，默认抛出错误（强制必须在上层提供）
val LocalGlobalAppState = staticCompositionLocalOf<GlobalAppState> {
    error("No GlobalAppState provided")
}
