package com.ufi_axis_widget.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 流量记录 DAO。
 *
 * 查询按 recordType 过滤，分离每日和每小时记录。
 * 所有查询都必须带 `profileId` —— 流量记录按配置档隔离，漏掉这个条件
 * 就会把别的设备的曲线混进来（见 [TrafficRecord.profileId]）。
 */
@Dao
interface TrafficDao {

    /** 插入或替换：同一档同一 dateKey 只保留一条最新记录 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TrafficRecord)

    // ── 每日记录查询（recordType = 'daily'）──

    /** 查询最近 N 条每日记录（按日期降序） */
    @Query("SELECT * FROM traffic_records WHERE profileId = :profileId AND recordType = 'daily' ORDER BY dateKey DESC LIMIT :limit")
    suspend fun getRecent(profileId: String, limit: Int): List<TrafficRecord>

    /** 查询每日记录（Flow 观察） */
    @Query("SELECT * FROM traffic_records WHERE profileId = :profileId AND recordType = 'daily' ORDER BY dateKey DESC LIMIT :limit")
    fun observeRecent(profileId: String, limit: Int): Flow<List<TrafficRecord>>

    /** 分页查询每日记录 */
    @Query("SELECT * FROM traffic_records WHERE profileId = :profileId AND recordType = 'daily' ORDER BY dateKey DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecentPaged(profileId: String, limit: Int, offset: Int): List<TrafficRecord>

    // ── 每小时记录查询（recordType = 'hourly'）──

    /** 分页查询每小时记录 */
    @Query("SELECT * FROM traffic_records WHERE profileId = :profileId AND recordType = 'hourly' ORDER BY dateKey DESC LIMIT :limit OFFSET :offset")
    suspend fun getHourlyPaged(profileId: String, limit: Int, offset: Int): List<TrafficRecord>

    /** 每小时记录总数 */
    @Query("SELECT COUNT(*) FROM traffic_records WHERE profileId = :profileId AND recordType = 'hourly'")
    suspend fun getHourlyCount(profileId: String): Int

    // ── 通用查询 ──

    /** 按 dateKey 查询单条记录 */
    @Query("SELECT * FROM traffic_records WHERE profileId = :profileId AND dateKey = :dateKey")
    suspend fun getByDateKey(profileId: String, dateKey: String): TrafficRecord?

    /** 获取每日记录数量 */
    @Query("SELECT COUNT(*) FROM traffic_records WHERE profileId = :profileId AND recordType = 'daily'")
    suspend fun getDailyCount(profileId: String): Int

    /**
     * 删除某一类型中早于 [dateKey] 的记录。
     *
     * 必须带 recordType：daily 与 hourly 的保留窗口不一样（一年 vs 一周），
     * 不区分类型的删除会让「按天数保留」在开了每小时记录后连带删掉每日曲线。
     *
     * hourly 的 key 形如 `yyyy-MM-dd-HH`，与 `yyyy-MM-dd` 做字典序比较时
     * 前者更大，所以传入某天的 daily key 会完整保留那天的全部小时记录。
     */
    @Query("DELETE FROM traffic_records WHERE profileId = :profileId AND recordType = :recordType AND dateKey < :dateKey")
    suspend fun deleteOlderThan(profileId: String, recordType: String, dateKey: String)

    // ── 月度聚合查询（基于每日记录）──

    /** 分页查询月聚合记录（按月份降序） */
    @Query("""
        SELECT t.id, t.profileId, t.dateKey, t.timestamp, t.dailyRawBytes, t.monthlyRawBytes, t.recordType, t.source
        FROM traffic_records t
        INNER JOIN (
            SELECT MAX(dateKey) AS max_dateKey
            FROM traffic_records
            WHERE profileId = :profileId AND recordType = 'daily'
            GROUP BY substr(dateKey, 1, 7)
        ) sub ON t.dateKey = sub.max_dateKey
        WHERE t.profileId = :profileId AND t.recordType = 'daily'
        ORDER BY t.dateKey DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMonthlyPaged(profileId: String, limit: Int, offset: Int): List<TrafficRecord>

    /** 获取去重的月数（基于每日记录） */
    @Query("SELECT COUNT(DISTINCT substr(dateKey, 1, 7)) FROM traffic_records WHERE profileId = :profileId AND recordType = 'daily'")
    suspend fun getMonthlyCount(profileId: String): Int

    /** 按月聚合查询：按 yyyy-MM 分组，取当月最后一条每日记录 */
    @Query("""
        SELECT t.id, t.profileId, t.dateKey, t.timestamp, t.dailyRawBytes, t.monthlyRawBytes, t.recordType, t.source
        FROM traffic_records t
        INNER JOIN (
            SELECT MAX(dateKey) AS max_dateKey
            FROM traffic_records
            WHERE profileId = :profileId AND recordType = 'daily'
            GROUP BY substr(dateKey, 1, 7)
        ) sub ON t.dateKey = sub.max_dateKey
        WHERE t.profileId = :profileId AND t.recordType = 'daily'
        ORDER BY t.dateKey DESC
        LIMIT :limit
    """)
    suspend fun getMonthlyRecords(profileId: String, limit: Int): List<TrafficRecord>

    // ── 账期聚合（供套餐额度与用完预测使用）──
    //
    // 刻意不用设备的 monthly_rx/tx_bytes：那是**自然月**统计，而运营商账期常常是
    // 5 号、15 号起算，两者根本不对齐。用本地 daily 记录按账期区间求和才是对的。

    /** 账期内已用字节数（含 [cycleStartKey] 当天）。无记录返回 null */
    @Query("""
        SELECT SUM(dailyRawBytes) FROM traffic_records
        WHERE profileId = :profileId AND recordType = 'daily' AND dateKey >= :cycleStartKey
    """)
    suspend fun sumDailySince(profileId: String, cycleStartKey: String): Long?

    /** 最近 N 条每日用量（用于算均速），按日期降序 */
    @Query("""
        SELECT dailyRawBytes FROM traffic_records
        WHERE profileId = :profileId AND recordType = 'daily' ORDER BY dateKey DESC LIMIT :limit
    """)
    suspend fun recentDailyBytes(profileId: String, limit: Int): List<Long>

    /** 删除某个配置档的全部记录（配置档被删除时调用） */
    @Query("DELETE FROM traffic_records WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)

    /** 删除全部记录（所有配置档） */
    @Query("DELETE FROM traffic_records")
    suspend fun clearAll()
}
