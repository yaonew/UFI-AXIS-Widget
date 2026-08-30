package com.ufi_axis_widget.util

import java.util.Locale

/*
 * 设备数据的公共格式化层。
 *
 * WifiEntity 中的 `flow`/`temp`/`batteryCurrent`/`batteryVoltage`/`internalStorage`
 * 等字段存的是已渲染字符串，UI 直接展示。任何数据源都必须复用这里的函数，
 * 否则同一份数据在不同源下会显示成不同格式。
 *
 * 这些函数原先是 `WifiCrawlUfiTools` 的私有方法。声明为同包顶层函数后，
 * `WifiCrawlUfiTools` 内部的调用点无需任何改动即可解析到这里。
 */

/** 流量格式化: Bytes → GB */
fun formatFlow(bytes: Long): String {
    if (bytes <= 0) return "0.00 GB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.2f GB", gb)
}

/** 温度格式化: 原始值 → ℃（>1000 视为千分度，需 /1000） */
fun formatTemp(raw: Double): String {
    if (raw <= 0) return "--"
    val celsius = if (raw > 1000) raw / 1000.0 else raw
    return String.format(Locale.getDefault(), "%.1f℃", celsius)
}

/** 电流格式化: µA → mA (微安转毫安) */
fun formatCurrent(currentUa: Int): String {
    if (currentUa < 0) return "--"
    val ma = currentUa / 1000.0
    return String.format(Locale.getDefault(), "%.0fmA", ma)
}

/** 电压格式化: µV → V (微伏转伏) */
fun formatVoltage(voltageUv: Int): String {
    if (voltageUv < 0) return "--"
    val v = voltageUv / 1_000_000.0
    return String.format(Locale.getDefault(), "%.2fV", v)
}

/**
 * 电压格式化: 已经是伏的浮点值 → "3.85V"。
 *
 * UFI-AXIS core 的 `battery.voltage` 直接报伏，不像 sysfs 那样报微伏，
 * 所以单独一个重载，避免调用方各自拼 `String.format` 拼出不同格式。
 */
fun formatVoltage(volts: Double): String =
    if (volts <= 0) "--" else String.format(Locale.getDefault(), "%.2fV", volts)

/** 存储格式化: Bytes → "已用 / 总量 GB" */
fun formatStorage(total: Long, used: Long): String {
    if (total <= 0) return "--"
    val usedGb = used / (1024.0 * 1024.0 * 1024.0)
    val totalGb = total / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f / %.1f GB", usedGb, totalGb)
}

/** 信号格式化: dBm 数值 → "-95dBm"，无数据返回 "--" */
fun formatSignal(rsrp: Int): String = if (rsrp == 0 || rsrp < -200) "--" else "${rsrp}dBm"

/**
 * 电量格式化: 百分比 → "85%"。
 *
 * 刻意不拼「充电中」后缀：充电态由电池图标内部的闪电（`ic_battery_charging`）表达，
 * 后缀会让文案长度随充电状态跳变，小尺寸组件里直接把同行内容挤到换行。
 * 充电位仍然单独存在 `WifiEntity.batteryCharging`，需要文字描述的地方（电池详情弹窗）
 * 自己按电流分档写「充电中 / 涓流充电 / 未充电」。
 *
 * @param percent 负值表示设备没报电量
 */
fun formatBattery(percent: Int): String = if (percent < 0) "--" else "$percent%"

/** 百分比格式化: 0..100 → "45.0%"，负值返回 "--" */
fun formatPercent(value: Double): String =
    if (value < 0) "--" else String.format(Locale.getDefault(), "%.1f%%", value)
