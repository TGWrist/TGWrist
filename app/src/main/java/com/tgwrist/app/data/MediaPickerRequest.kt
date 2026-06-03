package com.tgwrist.app.data

import android.net.Uri

/** 选择器允许的媒体类型 */
enum class MediaPickerType {
    IMAGE_ONLY,
    VIDEO_ONLY,
    IMAGE_AND_VIDEO,
}

/**
 * 跨页面调用媒体选择器的请求体。
 *
 * 用法（参考 TestScreen 的跨页面传参模式）：在 GlobalAppState 中暂存一个
 * [MediaPickerRequest]，再 navigate 到 [com.tgwrist.app.ui.Destinations.MEDIA_PICKER]，
 * MediaPickerScreen 完成后通过 [onResult] 回传结果再 popBackStack。
 *
 * @param type        允许选择的媒体类型
 * @param multiSelect 是否允许多选；为 false 时点选即返回
 * @param maxCount    多选模式下的上限（含），<=0 表示不限
 * @param preselected 多选模式下已选中的 Uri 列表，进入页面时自动勾选；
 *                    单选模式忽略此字段
 * @param onResult    用户确认后的回调；用户直接返回则回调空列表
 */
data class MediaPickerRequest(
    val type: MediaPickerType = MediaPickerType.IMAGE_AND_VIDEO,
    val multiSelect: Boolean = false,
    val maxCount: Int = 0,
    val preselected: List<Uri> = emptyList(),
    val onResult: (List<MediaPickerItem>) -> Unit,
)

/** 单条媒体条目 */
data class MediaPickerItem(
    val uri: Uri,
    val isVideo: Boolean,
    /** 视频时长（毫秒），图片为 0 */
    val durationMs: Long = 0L,
    /** MediaStore 入库时间戳，单位秒；用于稳定排序 */
    val dateAdded: Long = 0L,
)
