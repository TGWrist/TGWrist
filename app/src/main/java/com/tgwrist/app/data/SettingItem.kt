package com.tgwrist.app.data

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

sealed class SettingItem {
    /** 操作后是否需要重新构建设置项列表 */
    open val rebuildAfterAction: Boolean get() = false

    data class Title(
        @param:StringRes val titleRes: Int
    ) : SettingItem()

    data class SmallTitle(
        @param:StringRes val titleRes: Int
    ) : SettingItem()

    data class Click(
        @param:StringRes val titleRes: Int,
        val onClick: () -> Unit,
        val icon: (@Composable BoxScope.() -> Unit)? = null,
        val color: Color = Color(0xFF332E3C),
        val descriptionRes: String? = null,
        override val rebuildAfterAction: Boolean = false
    ) : SettingItem()

    data class Switch(
        @param:StringRes val titleRes: Int,
        val isSelected: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val icon: (@Composable BoxScope.() -> Unit)? = null,
        val descriptionRes: String? = null,
        override val rebuildAfterAction: Boolean = false
    ) : SettingItem()

    data class ClickAndOpenPage(
        @param:StringRes val titleRes: Int,
        val onClick: () -> Unit = {},
        val icon: (@Composable BoxScope.() -> Unit)? = null,
        val pageRoute: String,
        val color: Color = Color(0xFF332E3C),
        override val rebuildAfterAction: Boolean = false
    ) : SettingItem()

    data class None(val itemName: String = "None") : SettingItem()
}
