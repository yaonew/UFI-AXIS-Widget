package com.ufi_axis_widget.util

import android.content.Context
import com.ufi_axis_widget.worker.WifiWorker

/**
 * 一行状态摘要，供快捷设置磁贴与常驻通知共用。
 *
 * 与数据源无关：只读 `wifi_data` 这份公共缓存（由 [SPUtil.saveData] 写入），
 * 换数据源不需要改这里。
 *
 * 顺序上先报「为什么没数据」再报数据本身 —— 用户看到「已暂停刷新」比看到
 * 一份不知道多旧的数字有用得多。
 *
 * 显示哪些项由用户在「后台保活 → 实时数据显示项」里勾选，磁贴与通知共用同一份配置。
 */
object StatusSummary {

    /**
     * 一个可选显示项。
     *
     * [key] 只用于存 SP，与设备返回的字段名无关 —— 取值逻辑写在 [fieldValue] 里，
     * 因为同一个语义（如今日流量）在不同数据源下要走不同的回退链。
     */
    data class Field(val key: String, val label: String)

    /** 全部可选项，顺序即显示顺序 */
    val FIELDS = listOf(
        Field("net_type", "网络制式"),
        Field("signal", "信号"),
        Field("battery", "电量"),
        Field("daily", "今日流量"),
        Field("monthly", "本月流量"),
        Field("temp", "设备温度")
    )

    /** 用户勾选的显示项；未勾选（空集合）时按全部项显示 */
    fun selectedFields(context: Context): List<Field> {
        val picked = SPUtil.getStatusFields(context)
        if (picked.isEmpty()) return FIELDS
        return FIELDS.filter { it.key in picked }.ifEmpty { FIELDS }
    }

    /** 当前是否处于「正常采集中」状态（未被守卫拦下、也未判定离线） */
    fun isNormal(context: Context): Boolean =
        WifiGuard.evaluate(context).allowed && !WifiWorker.isWorkerStopped(context)

    /** 状态优先的一行摘要 */
    fun line(context: Context): String {
        val guard = WifiGuard.evaluate(context)
        if (!guard.allowed) return WifiGuard.blockedReason(guard)
        if (WifiWorker.isWorkerStopped(context)) return "设备离线"
        return dataLine(context)
    }

    /** 按用户勾选拼出的数据摘要，取不到值的项直接省略 */
    fun dataLine(context: Context): String {
        val parts = selectedFields(context)
            .map { fieldValue(context, it.key) }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "暂无数据" else parts.joinToString(" · ")
    }

    /**
     * 磁贴主标题：第一个有值的显示项。
     *
     * 数据必须落在**标题**上：部分 ROM（MIUI/HyperOS 等）不渲染第三方磁贴的副标题，
     * 只把数据写进 subtitle 的话用户看到的就是一个光秃秃的开关。
     * 暂停 / 离线时改报状态 —— 这时候「为什么没数据」比数字重要。
     */
    fun tileLabel(context: Context): String {
        if (!isNormal(context)) return line(context)
        val first = selectedFields(context)
            .asSequence()
            .map { fieldValue(context, it.key) }
            .firstOrNull { it.isNotEmpty() }
        return first ?: deviceTitle(context)
    }

    /**
     * 取单项显示文案，空字符串表示「当前数据源/固件没有这个值」。
     *
     * 今日流量必须回退读 [SPUtil.getDerivedDailyFlow]：只有月累计的数据源（goform）
     * 在 `daily_flow` 里存的是 `"--"`，只判空会让整条摘要退化成「只有网络制式」。
     */
    fun fieldValue(context: Context, key: String): String {
        val sp = SPUtil.getSp(context)
        fun clean(raw: String?): String {
            val v = raw?.trim().orEmpty()
            return if (v.isEmpty() || v == "--" || v == "N/A") "" else v
        }
        return when (key) {
            "net_type" -> clean(SPUtil.getAtNetType(context).ifEmpty { sp.getString("net_type", "") })
            "signal" -> clean(sp.getString("signal", ""))
            "battery" -> sp.getInt("battery_percent", -1).takeIf { it >= 0 }?.let { "电量 $it%" } ?: ""
            "daily" -> {
                val v = clean(sp.getString("daily_flow", "")).ifEmpty {
                    clean(SPUtil.getDerivedDailyFlow(context))
                }
                if (v.isEmpty()) "" else "今日 $v"
            }
            "monthly" -> clean(sp.getString("flow", "")).let { if (it.isEmpty()) "" else "本月 $it" }
            "temp" -> clean(sp.getString("temp", ""))
            else -> ""
        }
    }

    /** 设备标题：优先型号，没有就退回通用文案 */
    fun deviceTitle(context: Context): String {
        val sp = SPUtil.getSp(context)
        val model = (sp.getString("device_model", "") ?: "")
            .ifEmpty { sp.getString("model", "") ?: "" }
        return model.ifEmpty { "设备状态" }
    }
}
