package com.tgwrist.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap

/**
 * 根据聊天标题的第一个字母和颜色 ID 生成一个默认聊天图标的 Bitmap
 *
 * @param context 用于访问资源和显示指标
 * @param title 聊天标题
 * @param colorId 用于获取背景颜色
 * @return 生成的 Bitmap
 */
fun generateChatTitleIconBitmap(
    context: Context,
    title: String,
    colorId: Int
): Bitmap {
    val density = context.resources.displayMetrics.density

    // Telegram 标准尺寸参考
    //val sizeDp = 50f // 建议稍微大一点以保证清晰度，或者保持你原来的 35f
    //val textSizeSp = 22f // 字体大小通常约为图片大小的 40%-45%
    val sizeDp = 128f
    val textSizeSp = 56f

    val sizePx = (sizeDp * density).toInt()
    val textSizePx = (textSizeSp * density).toInt()

    // 创建 Bitmap
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)

    // --- 1. 绘制圆形背景 ---
    val circlePaint = Paint().apply {
        color = colorId
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val centerX = sizePx / 2f
    val centerY = sizePx / 2f
    val radius = sizePx / 2f

    canvas.drawCircle(centerX, centerY, radius, circlePaint)

    // --- 2. 提取缩写 (核心逻辑变化) ---
    val initials = getTelegramInitials(title)

    // --- 3. 绘制文本 ---
    val textPaint = Paint().apply {
        color = Color.White.toArgb() // Telegram 文字总是白色
        textSize = textSizePx.toFloat()
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        // 关键点：使用 Medium 字体，复刻原生质感
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // 计算基线位置以垂直居中
    val fontMetrics = textPaint.fontMetrics
    // 公式：centerY - (descent + ascent) / 2
    val textBaseLineY = centerY - (fontMetrics.descent + fontMetrics.ascent) / 2f

    canvas.drawText(initials, centerX, textBaseLineY, textPaint)

    return bitmap
}

/**
 * 完美复刻 Telegram 的缩写提取逻辑
 * 1. "Abc Def" -> "AD"
 * 2. "Abc Def Ghi" -> "AG" (取首尾)
 * 3. "Abc" -> "A"
 * 4. "  Abc   " -> "A" (去除空格)
 * 5. "张三" -> "张" (无空格视为一个词)
 * 6. "张 三" -> "张三"
 */
private fun getTelegramInitials(title: String): String {
    if (title.isBlank()) return "" // 或者返回 "?"

    // 1. 清理多余空格并分割
    // split("\\s+".toRegex()) 会按任意空白字符分割，并忽略连续空格
    val words = title.trim().split("\\s+".toRegex())

    if (words.isEmpty()) return ""

    // 2. 提取逻辑
    val firstChar = getFirstGrapheme(words.first())

    val result = if (words.size > 1) {
        val lastChar = getFirstGrapheme(words.last())
        "$firstChar$lastChar"
    } else {
        firstChar
    }

    // 3. 强制大写
    return result.uppercase()
}

/**
 * 安全地获取字符串的第一个“字”（处理 Emoji 和 Surrogate Pairs）
 * 虽然通常取 substring(0,1) 就够了，但为了健壮性（防止切断 Emoji），这里稍微严谨一点
 */
private fun getFirstGrapheme(str: String): String {
    if (str.isEmpty()) return ""
    // 获取第一个码点（Code Point）的长度，兼容 Emoji
    val firstCodePoint = str.codePointAt(0)
    val charCount = Character.charCount(firstCodePoint)
    return str.substring(0, charCount)
}
