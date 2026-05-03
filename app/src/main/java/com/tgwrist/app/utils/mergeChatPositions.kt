package com.tgwrist.app.utils

import org.drinkless.tdlib.TdApi

fun mergeChatPositions(
    oldPositions: Array<TdApi.ChatPosition>? = null,
    newPositions: Array<TdApi.ChatPosition>
): Array<TdApi.ChatPosition> {
    if (oldPositions == null) return newPositions

    val result = mutableListOf<TdApi.ChatPosition>()

    // 先放旧的
    result.addAll(oldPositions)

    // 遍历新的，替换同 list 的，或者追加
    for (newPos in newPositions) {
        val index = result.indexOfFirst { it.list == newPos.list }
        if (index >= 0) {
            result[index] = newPos // 替换
        } else {
            result.add(newPos) // 新增
        }
    }

    return result.toTypedArray()
}
