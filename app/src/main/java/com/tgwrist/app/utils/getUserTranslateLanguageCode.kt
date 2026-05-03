package com.tgwrist.app.utils

import java.util.Locale

private val supportedTranslateLanguages = setOf(
    "af", "sq", "am", "ar", "hy", "az", "eu", "be", "bn", "bs", "bg", "ca",
    "ceb", "zh-CN", "zh", "zh-Hans", "zh-TW", "zh-Hant", "co", "hr", "cs",
    "da", "nl", "en", "eo", "et", "fi", "fr", "fy", "gl", "ka", "de", "el",
    "gu", "ht", "ha", "haw", "he", "iw", "hi", "hmn", "hu", "is", "ig",
    "id", "in", "ga", "it", "ja", "jv", "kn", "kk", "km", "rw", "ko",
    "ku", "ky", "lo", "la", "lv", "lt", "lb", "mk", "mg", "ms", "ml",
    "mt", "mi", "mr", "mn", "my", "ne", "no", "ny", "or", "ps", "fa",
    "pl", "pt", "pa", "ro", "ru", "sm", "gd", "sr", "st", "sn", "sd",
    "si", "sk", "sl", "so", "es", "su", "sw", "sv", "tl", "tg", "ta",
    "tt", "te", "th", "tr", "tk", "uk", "ur", "ug", "uz", "vi", "cy",
    "xh", "yi", "ji", "yo", "zu"
)

/**
 * 获取当前用户系统语言对应的翻译目标语言代码。
 *
 * 如果当前系统语言不在支持列表中，则返回英语 "en"。
 */
fun getUserTranslateLanguageCode(locale: Locale = Locale.getDefault()): String {
    val language = locale.language
    val country = locale.country
    val script = locale.script

    // 中文需要特殊处理，因为列表里同时存在 zh / zh-CN / zh-TW / zh-Hans / zh-Hant
    if (language == "zh") {
        return when {
            script.equals("Hans", ignoreCase = true) -> "zh-Hans"
            script.equals("Hant", ignoreCase = true) -> "zh-Hant"

            country.equals("CN", ignoreCase = true) ||
                    country.equals("SG", ignoreCase = true) -> "zh-CN"

            country.equals("TW", ignoreCase = true) ||
                    country.equals("HK", ignoreCase = true) ||
                    country.equals("MO", ignoreCase = true) -> "zh-TW"

            else -> "zh"
        }
    }

    // Android / Java 里部分旧语言代码可能会被自动转换
    val normalizedLanguage = when (language) {
        "iw" -> "he" // Hebrew
        "in" -> "id" // Indonesian
        "ji" -> "yi" // Yiddish
        else -> language
    }

    if (normalizedLanguage in supportedTranslateLanguages) {
        return normalizedLanguage
    }

    // 如果原始 language 刚好在支持列表中，也允许返回
    if (language in supportedTranslateLanguages) {
        return language
    }

    return "en"
}
