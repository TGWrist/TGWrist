package com.tgwrist.app.data

/*
* type 只能为 "Image" 、 "Video" 或 "Document" 中的一个
* path 既可以是本地绝对路径，也可以是 content:// URI 字符串
* （MediaPicker 选出的会是 content URI，发送前在 IO 线程解析为本地路径）
*/
data class MediaData (
    val type: String,
    val path: String
)
