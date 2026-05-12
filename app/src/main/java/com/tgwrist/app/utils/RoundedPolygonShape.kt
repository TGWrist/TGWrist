package com.tgwrist.app.utils

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

class RoundedPolygonShape(
    private val polygon: RoundedPolygon
) : Shape {
    private val matrix = android.graphics.Matrix()
    private var path = android.graphics.Path()

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        path.rewind()
        // 将归一化的 RoundedPolygon 生成为 Android Path
        path = polygon.toPath(path)

        matrix.reset()
        // 因为多边形半径为 1，所以我们需要把它放大到 Size 的一半
        val halfWidth = size.width / 2f
        val halfHeight = size.height / 2f

        // 1. 缩放
        matrix.postScale(halfWidth, halfHeight)
        // 2. 将原点(0,0)平移到组件的中心点
        matrix.postTranslate(halfWidth, halfHeight)

        // 应用矩阵变换
        path.transform(matrix)

        // 转换为 Compose Path 输出
        return Outline.Generic(path.asComposePath())
    }
}
