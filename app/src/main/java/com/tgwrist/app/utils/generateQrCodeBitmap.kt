package com.tgwrist.app.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 把文本（如 TDLib 提供的 tg://login?token=... 链接）编码成二维码 [Bitmap]。
 *
 * 在 Wear OS 小屏上扫描，纠错等级用 [ErrorCorrectionLevel.M] 已足够，
 * 同时尽量缩小留白（margin = 1），把可视模块做大、方便对侧设备识别。
 *
 * @param content 待编码内容
 * @param sizePx  生成位图的边长（像素，正方形）
 * @return 编码成功返回正方形 [Bitmap]，内容为空或编码失败返回 null
 */
fun generateQrCodeBitmap(content: String, sizePx: Int): Bitmap? {
    if (content.isBlank() || sizePx <= 0) return null

    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (_: Exception) {
        null
    }
}
