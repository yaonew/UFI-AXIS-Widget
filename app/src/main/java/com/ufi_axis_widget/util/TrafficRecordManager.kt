package com.ufi_axis_widget.util

import android.content.Context
import com.ufi_axis_widget.db.AppDatabase
import com.ufi_axis_widget.db.TrafficDao
import com.ufi_axis_widget.db.TrafficRecord
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

import java.util.Locale

/**
 * 流量记录管理器。
 *
 * 负责在每次成功获取 WiFi 数据后，记录当前日流量和月流量到 Room。
 * 每日记录以 (配置档, "yyyy-MM-dd") 为 key，使用 REPLACE 策略确保每台设备每天只保留最新累计值。
 * 开启每小时记录后，额外以 "yyyy-MM-dd-HH" 为 key 存储每小时累计值。
 * 差值计算在显示层完成，存储层始终保存设备 API 报告的原始累计值。
 *
 * 所有读写都限定在**当前配置档**内 —— 换设备等于换一套完全独立的流量曲线，
 * 混在一起会互相覆盖，账期用量和用完预测也会跟着错。
 */
object TrafficRecordManager {

    private const val TAG = "TrafficRecordManager"

    /** 每日记录保留窗口：一年，够画满全部月度视图 */
    private const val DAILY_RETENTION_DAYS = 366

    /**
     * 每小时记录保留窗口。
     *
     * 每小时记录只服务于「近期小时曲线」，一天写 24 条，按年保留会让表膨胀到
     * 每日记录的 24 倍，而没有任何界面会去看半年前的某个小时。
     */
    private const val HOURLY_RETENTION_DAYS = 7

    /** 后台清理用的独立作用域：删档清库不该被页面生命周期打断 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 单日结清累加器，仅在数据源没有日流量字段时使用。
     *
     * [KEY_ACC_DATE] 是累加器归属的日期，变化即换日清零；
     * [KEY_ACC_LAST_MONTHLY] 是上一次采样看到的月累计，用来算增量；
     * [KEY_ACC_USAGE] 是今天已累加的用量。
     */
    private const val KEY_ACC_DATE = "traffic_derive_acc_date"
    private const val KEY_ACC_LAST_MONTHLY = "traffic_derive_acc_last_monthly"
    private const val KEY_ACC_USAGE = "traffic_derive_acc_usage"

    /**
     * 累加器互斥锁。
     *
     * 累加器是「读—算—写」，而 WifiWorker 周期采集、NotificationMonitor 轮询、
     * AlarmReceiver 一次性检查三条链路都会进来。两次交叠执行会读到同一个
     * lastMonthly，于是同一段增量被累加两次（或后写者覆盖前写者，丢掉一段），
     * 今日流量直接偏高或偏低，还会拿这个错值去撞阈值告警。
     */
    private val accLock = Any()

    /**
     * 清空当前配置档的单日结清累加器。
     *
     * 换设备时必须调用：新设备的月累计基线与旧设备毫无关系，
     * 不清会把两台设备的差值算成一个巨大的跳变。
     */
    fun resetDeriveAccumulator(context: Context) {
        // 与 [deriveDailyUsage] 共用一把锁并同样用 commit()：否则「换档清零」
        // 可能被一个正在进行中的累加覆盖回去，新设备继承旧设备的基线
        synchronized(accLock) {
            SPUtil.getSp(context).edit()
                .remove(KEY_ACC_DATE)
                .remove(KEY_ACC_LAST_MONTHLY)
                .remove(KEY_ACC_USAGE)
                .commit()
        }
    }

    /** 日期格式化器（线程安全，仅用于格式化取当前日期） */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val hourlyDateFormat = SimpleDateFormat("yyyy-MM-dd-HH", Locale.getDefault())

    @Volatile
    private var dao: TrafficDao? = null

    fun initDatabase(context: Context) {
        if (dao == null) {
            synchronized(this) {
                if (dao == null) {
                    dao = AppDatabase.getInstance(context).trafficDao()
                }
            }
        }
    }

    private fun getDao(): TrafficDao =
        dao ?: throw IllegalStateException("TrafficRecordManager not initialized. Call initDatabase() first.")

    /**
     * 记录流量数据。
     *
     * 始终保存一条每日记录（累计值），开启每小时记录时额外保存一条每小时记录（累计值）。
     * 存储层不做差值计算，差值在显示时由上层计算。
     *
     * @param context Context
     * @param dailyRawBytes 日流量字节数（设备 API 报告的当日累计值）
     * @param monthlyRawBytes 月流量字节数（设备 API 报告的当月累计值）
     * @param deriveDaily 数据源没有日流量字段（如 goform 只有月累计）时传 true，
     *                    此时忽略 [dailyRawBytes]，改用 [deriveDailyUsage] 由月累计跨天做差推导
     */
    suspend fun saveRecord(
        context: Context,
        dailyRawBytes: Long,
        monthlyRawBytes: Long,
        deriveDaily: Boolean = false,
    ) {
        // 检查总开关
        if (!SPUtil.getTrafficRecordEnabled(context)) {
            DebugLogger.logApi(TAG, "Traffic recording disabled by master switch")
            return
        }
        withContext(Dispatchers.IO) {
            val hourlyEnabled = SPUtil.getTrafficHourlyRecordEnabled(context)
            val now = Date()
            val ts = System.currentTimeMillis()

            // 始终保存每日记录（累计值）— SimpleDateFormat 非线程安全，需 synchronized
            val dailyKey = synchronized(dateFormat) { dateFormat.format(now) }
            val effectiveDaily =
                if (deriveDaily) deriveDailyUsage(context, dailyKey, monthlyRawBytes)
                else dailyRawBytes
            // 标记写入者：不同源的日用量语义不同（上报值 vs 本地推算值），
            // 不记下来事后无法区分曲线上的台阶是换源还是真的用量突变
            val sourceId = DeviceDataSourceRegistry.current(context).type.id
            // 归属配置档：在 IO 块里现取，保证写入的是「此刻」的当前档
            val profileId = DeviceProfiles.activeId(context)

            val dailyRecord = TrafficRecord(
                profileId = profileId,
                dateKey = dailyKey,
                timestamp = ts,
                dailyRawBytes = effectiveDaily,
                monthlyRawBytes = monthlyRawBytes,
                recordType = "daily",
                source = sourceId
            )
            getDao().upsert(dailyRecord)
            DebugLogger.logApi(
                TAG,
                "Daily saved: $dailyKey daily=$effectiveDaily(derived=$deriveDaily) " +
                    "monthly=$monthlyRawBytes source=$sourceId profile=$profileId"
            )

            // 开启每小时记录时，额外保存每小时记录（累计值）
            if (hourlyEnabled) {
                val hourlyKey = synchronized(hourlyDateFormat) { hourlyDateFormat.format(now) }
                val hourlyRecord = TrafficRecord(
                    profileId = profileId,
                    dateKey = hourlyKey,
                    timestamp = ts,
                    // 推导值在一天内单调递增，displayer 的「相邻小时做差」照样成立
                    dailyRawBytes = effectiveDaily,
                    monthlyRawBytes = monthlyRawBytes,
                    recordType = "hourly",
                    source = sourceId
                )
                getDao().upsert(hourlyRecord)
                DebugLogger.logApi(TAG, "Hourly saved: $hourlyKey daily=$effectiveDaily monthly=$monthlyRawBytes")
            }

            // 超过上限时清理最旧的记录
            cleanupIfNeeded(profileId)
        }
    }

    /**
     * 由月累计推导今日用量，不写库。
     *
     * 给通知链路用 —— 它拿到的 `dailyFlowStr` 是数据源直给的，只有月累计的源那里是 `"--"`，
     * 走这里换成推导值就能让「今日流量」阈值正常工作，且不依赖流量记录总开关。
     */
    suspend fun currentDailyUsageBytes(context: Context, monthlyRawBytes: Long): Long {
        val todayKey = synchronized(dateFormat) { dateFormat.format(Date()) }
        return withContext(Dispatchers.IO) {
            deriveDailyUsage(context, todayKey, monthlyRawBytes)
        }
    }

    /**
     * 由月累计推导今日用量 —— **单日结清**。
     *
     * 不跨天做差，而是在一天内做「相邻两次采样的月累计增量」累加：
     *
     * ```
     * 今日用量 += max(0, 本次月累计 − 上次月累计)
     * ```
     *
     * 换日时（[KEY_ACC_DATE] 变化）累加器清零、上次值重挂为当前月累计。所以昨天 22:00
     * 断开、今天 08:00 恢复的情况下：昨天结清于 22:00 那次采样，今天从 08:00 起算，
     * 中间这 10 小时的流量两天都不计 —— 这是「单日结清」的定义，不是 bug。
     *
     * 计数器归零（翻月、设备清计数）时本次月累计会小于上次，此时把本次值整体当作增量，
     * 因为归零后计数是从 0 重新长起来的。
     *
     * 相比「拿昨天最后一条记录当基线」的做法：断档若干天后恢复不会把整个缺口堆到恢复那天，
     * 那些天本来就没有记录，流量历史里显示为空即可。
     */
    private fun deriveDailyUsage(
        context: Context,
        todayKey: String,
        monthlyRawBytes: Long,
    ): Long {
        // 必须走 SPUtil.getSp：累加器是「按配置档隔离」的键，直接开裸 prefs
        // 会让多个设备共用一份基线，换档后日用量直接算错
        val sp = SPUtil.getSp(context)

        // 整段读—算—写必须互斥，见 [accLock]。
        // 写入用 commit() 而不是 apply()：apply() 是异步落盘，锁一放开
        // 下一个线程可能仍读到旧值，等于白加锁。
        val usage = synchronized(accLock) {
            // 换日 → 清零累加器，把当前月累计作为今天的起点
            if (sp.getString(KEY_ACC_DATE, null) != todayKey) {
                sp.edit()
                    .putString(KEY_ACC_DATE, todayKey)
                    .putLong(KEY_ACC_LAST_MONTHLY, monthlyRawBytes)
                    .putLong(KEY_ACC_USAGE, 0L)
                    .commit()
                DebugLogger.logApi(TAG, "deriveDailyUsage: 进入新的一天 $todayKey，起点月累计=$monthlyRawBytes")
            }

            val lastMonthly = sp.getLong(KEY_ACC_LAST_MONTHLY, monthlyRawBytes)
            val prevUsage = sp.getLong(KEY_ACC_USAGE, 0L)
            val delta = if (monthlyRawBytes < lastMonthly) {
                // 归零：新计数从 0 长到当前值，这段全是本次增量
                DebugLogger.logApi(TAG, "deriveDailyUsage: 月累计归零（$monthlyRawBytes < $lastMonthly）")
                monthlyRawBytes
            } else {
                monthlyRawBytes - lastMonthly
            }
            val next = prevUsage + delta

            sp.edit()
                .putLong(KEY_ACC_LAST_MONTHLY, monthlyRawBytes)
                .putLong(KEY_ACC_USAGE, next)
                .commit()
            DebugLogger.logApi(
                TAG,
                "deriveDailyUsage: $todayKey 月累计=$monthlyRawBytes 上次=$lastMonthly " +
                    "增量=$delta 今日累计=$next"
            )
            next
        }

        // 缓存给通知链路用：checkDailyFlow 有 5 个入口，都拿不到推导值，
        // 统一在那里回退读这个键
        SPUtil.setDerivedDailyFlow(context, formatFlow(usage))
        return usage
    }

    /** 查询最近 N 天的每日记录（按日期降序） */
    suspend fun getRecent(context: Context, limit: Int = 31): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getRecent(profileOf(context), limit) }

    /** 查询最近 N 个月的流量记录（按月聚合，按月份降序） */
    suspend fun getMonthly(context: Context, limit: Int = 12): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getMonthlyRecords(profileOf(context), limit) }

    /** 分页查询每日记录 */
    suspend fun getRecentPaged(context: Context, limit: Int, offset: Int): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getRecentPaged(profileOf(context), limit, offset) }

    /** 分页查询每小时记录 */
    suspend fun getHourlyPaged(context: Context, limit: Int, offset: Int): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getHourlyPaged(profileOf(context), limit, offset) }

    /** 每小时记录总数 */
    suspend fun getHourlyCount(context: Context): Int =
        withContext(Dispatchers.IO) { getDao().getHourlyCount(profileOf(context)) }

    /** 分页查询月聚合记录 */
    suspend fun getMonthlyPaged(context: Context, limit: Int, offset: Int): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getMonthlyPaged(profileOf(context), limit, offset) }

    /** 获取去重的月数 */
    suspend fun getMonthlyCount(context: Context): Int =
        withContext(Dispatchers.IO) { getDao().getMonthlyCount(profileOf(context)) }

    /** 观察最近 N 天的每日记录（Flow） */
    fun observeRecent(context: Context, limit: Int = 31): Flow<List<TrafficRecord>> =
        getDao().observeRecent(profileOf(context), limit)

    /** 获取某一天的记录 */
    suspend fun getByDateKey(context: Context, dateKey: String): TrafficRecord? =
        withContext(Dispatchers.IO) { getDao().getByDateKey(profileOf(context), dateKey) }

    /** 获取每日记录总数 */
    suspend fun getCount(context: Context): Int =
        withContext(Dispatchers.IO) { getDao().getDailyCount(profileOf(context)) }

    /**
     * 删除某个配置档的全部流量记录。
     *
     * 配置档被删掉后这些记录再也没有入口能看到，留着就是孤儿数据。
     *
     * 刻意不挂在调用方的 lifecycleScope 上：删档后页面可能马上被关掉，
     * 作用域一取消删除就半途而废，下次再也没人会来清它。
     */
    fun clearProfile(profileId: String) {
        cleanupScope.launch {
            try {
                getDao().clearProfile(profileId)
                DebugLogger.logApi(TAG, "已清除配置档 $profileId 的流量记录")
            } catch (e: Exception) {
                DebugLogger.e(TAG, "清除配置档 $profileId 流量记录失败: ${e.message}")
            }
        }
    }

    /** 当前配置档 id，所有查询的隔离维度 */
    private fun profileOf(context: Context): String = DeviceProfiles.activeId(context)

    /**
     * 删除超出保留窗口的旧记录（仅限 [profileId] 档，各设备各算）。
     *
     * 按**日期**而不是按条数裁剪：条数方案要求「分母的类型口径」和「定位 cutoff 的
     * 类型口径」严格一致，一旦 daily 与 hourly 混在同一张表里就极易算错 ——
     * 开了每小时记录后条数被迅速抬高，cutoff 会落到很近的日期，把根本没过期的
     * 每日曲线一起删光。日期阈值没有这个耦合，两种类型各自独立裁剪。
     */
    private suspend fun cleanupIfNeeded(profileId: String) {
        try {
            getDao().deleteOlderThan(profileId, "daily", dateKeyDaysAgo(DAILY_RETENTION_DAYS))
            getDao().deleteOlderThan(profileId, "hourly", dateKeyDaysAgo(HOURLY_RETENTION_DAYS))
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Cleanup failed: ${e.message}")
        }
    }

    /** [days] 天前的 dateKey，用作保留窗口下界 */
    private fun dateKeyDaysAgo(days: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
        return synchronized(dateFormat) { dateFormat.format(cal.time) }
    }
}
