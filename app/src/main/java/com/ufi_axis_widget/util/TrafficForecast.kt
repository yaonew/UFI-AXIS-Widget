package com.ufi_axis_widget.util

import android.content.Context
import com.ufi_axis_widget.db.AppDatabase
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 套餐额度与「预计用完」推算。
 *
 * ## 为什么不用设备的 `monthly_rx_bytes`
 *
 * 设备侧的月累计是按**自然月**统计的（1 号归零）。而运营商账期常常是 5 号、15 号，
 * 两者根本不对齐 —— 直接拿设备月累计和套餐额度比，账期一过就全错。
 * 所以本模块只用本地 `traffic_records` 的每日记录，按账期区间求和。
 *
 * 代价是：本地没有记录的日子（应用被卸载、流量记录关闭、设备长期离线）不计入，
 * 已用量会偏低。这是数据可得性的边界，不是算法缺陷，UI 上要说明。
 *
 * ## 预测算法
 *
 * 近 [AVG_WINDOW_DAYS] 天的日用量均值做线性外推。刻意不做异常值剔除或加权：
 * 用户需要的是「大概哪天用完」，一个能一句话解释清楚的数字比一个更"准"
 * 但说不清怎么来的数字有用。
 */
object TrafficForecast {

    private const val TAG = "TrafficForecast"

    /** 均速统计窗口（天） */
    private const val AVG_WINDOW_DAYS = 7

    /**
     * 预测上限（天）。超过这个数就不给日期了。
     *
     * 十年之后的耗尽日对用户没有任何信息量，只会显得程序算错了；
     * 同时它也是 `toInt()` 的溢出护栏 —— 均速只有几 KB 时商能到上亿。
     */
    private const val MAX_FORECAST_DAYS = 3650L

    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayFormat = SimpleDateFormat("M月d日", Locale.getDefault())

    /**
     * 一次推算的完整结果。
     *
     * @param quotaBytes      额度，0 表示用户未设置
     * @param usedBytes       本账期已用
     * @param cycleStartKey   本账期起始日（yyyy-MM-dd）
     * @param avgDailyBytes   近 N 天日均用量，0 表示没有足够记录
     * @param exhaustDateText 预计用完日期文案，空串表示无法预测
     * @param derivedDaily    日用量是否来自本地推算（当前数据源无日流量字段）
     */
    data class Result(
        val quotaBytes: Long,
        val usedBytes: Long,
        val cycleStartKey: String,
        val avgDailyBytes: Long,
        val exhaustDateText: String,
        val derivedDaily: Boolean,
    ) {
        val hasQuota: Boolean get() = quotaBytes > 0

        /** 已用占比（0..1，可能超过 1）。未设额度时返回 0 */
        val usedRatio: Float
            get() = if (quotaBytes <= 0) 0f else usedBytes.toFloat() / quotaBytes.toFloat()

        val remainingBytes: Long get() = (quotaBytes - usedBytes).coerceAtLeast(0L)
    }

    /** 本账期起始日的 dateKey。今天若已过账期日则取本月，否则取上月 */
    fun cycleStartKey(billingDay: Int, now: Date = Date()): String {
        val cal = Calendar.getInstance().apply { time = now }
        val day = billingDay.coerceIn(1, 28)
        if (cal.get(Calendar.DAY_OF_MONTH) < day) {
            cal.add(Calendar.MONTH, -1)
        }
        cal.set(Calendar.DAY_OF_MONTH, day)
        return synchronized(dateKeyFormat) { dateKeyFormat.format(cal.time) }
    }

    suspend fun compute(context: Context): Result = withContext(Dispatchers.IO) {
        val quota = SPUtil.getTrafficQuotaBytes(context)
        val billingDay = SPUtil.getTrafficBillingDay(context)
        val startKey = cycleStartKey(billingDay)
        val derived = !DeviceDataSourceRegistry.currentCapabilities(context).dailyTraffic

        val dao = AppDatabase.getInstance(context).trafficDao()
        // 只算当前档的记录：混上别台设备的日用量会把已用量和均速一起推高
        val profileId = DeviceProfiles.activeId(context)
        val used = try {
            dao.sumDailySince(profileId, startKey) ?: 0L
        } catch (e: Exception) {
            DebugLogger.w(TAG, "sumDailySince 失败: ${e.message}")
            0L
        }
        val recent = try {
            dao.recentDailyBytes(profileId, AVG_WINDOW_DAYS)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "recentDailyBytes 失败: ${e.message}")
            emptyList()
        }
        // 用实际条数而不是固定 7 做分母：只攒了 2 天记录时除以 7 会把均速压到 2/7，
        // 预测出一个荒谬的乐观日期
        val avg = if (recent.isEmpty()) 0L else recent.sum() / recent.size

        val exhaustText = when {
            quota <= 0 -> ""
            avg <= 0L -> ""
            used >= quota -> "额度已用尽"
            else -> {
                // 先夹紧再降 Int：均速极低（只用了几 KB）时 (quota-used)/avg 能有上亿天，
                // 直接 toInt() 会溢出成负数，Calendar 就把耗尽日算到过去
                val daysLeft = ((quota - used) / avg).coerceIn(0L, MAX_FORECAST_DAYS).toInt()
                if (daysLeft >= MAX_FORECAST_DAYS) {
                    "用量过低，暂不可预测"
                } else {
                    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, daysLeft) }
                    val date = synchronized(displayFormat) { displayFormat.format(cal.time) }
                    "按近 ${recent.size} 天均速，预计 $date 用完"
                }
            }
        }

        Result(
            quotaBytes = quota,
            usedBytes = used,
            cycleStartKey = startKey,
            avgDailyBytes = avg,
            exhaustDateText = exhaustText,
            derivedDaily = derived,
        )
    }
}
