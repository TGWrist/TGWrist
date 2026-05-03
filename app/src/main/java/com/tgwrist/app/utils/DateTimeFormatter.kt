package com.tgwrist.app.utils

import android.content.Context
import android.text.format.DateFormat
import com.tgwrist.app.R
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.text.format.DateFormat as AndroidDateFormat

/** 日期，例如：2026/2/8 或 Feb 8, 2026 或 08.02.2026 传入毫秒 */
fun date(context: Context, millis: Long): String {
    val formatter = DateFormat.getDateFormat(context)
    return formatter.format(Date(millis))
}

/** 时间，例如：21:43 或 9:43 PM（自动 12/24 小时制） 传入毫秒 */
fun time(context: Context, millis: Long): String {
    val formatter = DateFormat.getTimeFormat(context)
    return formatter.format(Date(millis))
}

/** 日期+时间 传入毫秒 */
fun dateTimeUserPref(context: Context, millis: Long): String {
    val d = date(context, millis)
    val t = time(context, millis)
    return "$d $t"
}

// 传入秒，类型Long
fun isSameDay(sec1: Long, sec2: Long): Boolean {
    val zone = ZoneId.systemDefault()

    val d1 = Instant.ofEpochSecond(sec1)
        .atZone(zone)
        .toLocalDate()

    val d2 = Instant.ofEpochSecond(sec2)
        .atZone(zone)
        .toLocalDate()

    return d1 == d2
}

fun formatTimestampToDateAndTime(unixTimestamp: Long): String {
    val date = Date(unixTimestamp)
    val now = Calendar.getInstance()
    val calendar = Calendar.getInstance().apply { time = date }

    // 获取当前和日期的时间组成部分
    val currentYear = now.get(Calendar.YEAR)
    val currentMonth = now.get(Calendar.MONTH)
    val currentDay = now.get(Calendar.DAY_OF_MONTH)

    val timestampYear = calendar.get(Calendar.YEAR)
    val timestampMonth = calendar.get(Calendar.MONTH)
    val timestampDay = calendar.get(Calendar.DAY_OF_MONTH)

    // 根据日期差异选择格式模板
    val skeleton = when {
        timestampYear != currentYear -> "yMMMdHHmm"    // 跨年：显示完整日期+时间
        timestampMonth != currentMonth -> "MMMdHHmm"   // 同年跨月：月日+时间
        timestampDay != currentDay -> "dHHmm"          // 同月跨天：日期+时间
        else -> "HHmm"                                 // 同天：仅时间
    }

    // 获取本地化最佳格式模板
    val pattern = DateFormat.getBestDateTimePattern(
        Locale.getDefault(),
        skeleton
    )

    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

/**
 * 格式化消息时间（用于会话）：
 * - timestampMillis: epoch 毫秒
 * - 返回已本地化的字符串，规则见函数说明
 */
fun Context.formatChatTimestamp(timestampMillis: Long): String {
    if (timestampMillis == 0L) return ""

    val locale: Locale = Locale.getDefault()
    val zone: ZoneId = ZoneId.systemDefault()

    val msgZdt: ZonedDateTime = Instant.ofEpochMilli(timestampMillis).atZone(zone)
    val nowZdt: ZonedDateTime = ZonedDateTime.now(zone)

    val duration = Duration.between(msgZdt, nowZdt)
    val absSeconds = kotlin.math.abs(duration.seconds)
    val absHours = kotlin.math.abs(duration.toHours())
    val daysBetween = kotlin.math.abs(duration.toDays())

    if (absSeconds < 20) {
        return getString(R.string.just_now)
    }

    // 1) 24 小时以内 -> 显示 24 小时制的时间（使用本地最佳 pattern "Hm"）
    if (absHours < 24) {
        // 强制使用 24 小时样式（"Hm" skeleton）
        val pattern = AndroidDateFormat.getBestDateTimePattern(locale, "Hm")
        val fmt = DateTimeFormatter.ofPattern(pattern, locale)
        return msgZdt.format(fmt)
    }

    // 2) 超过 24 小时且在 7 天内 -> 显示星期（完整本地化名称，"EEEE"）
    if (daysBetween <= 7) {
        // 使用本地化的星期全名（例如 "Monday" / "星期一"）
        //val pattern = AndroidDateFormat.getBestDateTimePattern(locale, "EEEE")
        //val fmt = DateTimeFormatter.ofPattern(pattern, locale)
        // 后来想想还是用月份和日期吧，在手表上这样会比较方便
        val pattern = AndroidDateFormat.getBestDateTimePattern(locale, "Md")
        val fmt = DateTimeFormatter.ofPattern(pattern, locale)
        return msgZdt.format(fmt)
    }

    // 3) 超过 7 天但在同一年 -> 显示 month/day（无年份），按照本地惯例
    val sameYear = msgZdt.year == nowZdt.year
    if (sameYear) {
        // skeleton "Md" -> 本地化的月/日表示（不会包含年份）
        val pattern = AndroidDateFormat.getBestDateTimePattern(locale, "Md")
        val fmt = DateTimeFormatter.ofPattern(pattern, locale)
        return msgZdt.format(fmt)
    }

    // 4) 不同年份 -> 显示带年份的完整日期（例如 2024/10/2 或 2/10/2024）
    val pattern = AndroidDateFormat.getBestDateTimePattern(locale, "yMd")
    val fmt = DateTimeFormatter.ofPattern(pattern, locale)
    return msgZdt.format(fmt)
}
