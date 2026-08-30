package com.ufi_axis_widget.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ufi_axis_widget.util.DeviceProfiles

/**
 * 流量使用记录 Room 实体。
 *
 * 每天记录一次日流量和月流量，通过 ([profileId], [dateKey]) 去重，
 * 每台设备每天只保留一条当日最新记录。
 * 开启每小时记录后，额外以 "yyyy-MM-dd-HH" 为 key 记录每小时累计值。
 */
@Entity(
    tableName = "traffic_records",
    indices = [
        Index(value = ["profileId", "dateKey"], unique = true),
        Index(value = ["recordType"])
    ]
)
data class TrafficRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * 所属配置档 id（[com.ufi_axis_widget.util.DeviceProfiles.activeId]）。
     *
     * 为什么需要：多台设备的流量曲线本来就是互不相干的两组数据，混在一张表里
     * 只会互相覆盖 —— 同一天两台设备各写一条，旧的唯一索引在 [dateKey] 单列上，
     * 后写的直接把前一台的记录顶掉，账期用量和预测也全跟着错。
     *
     * v6 之前的历史记录一律归到默认档（`"default"`），和 `DeviceProfiles`
     * 「老数据天然属于默认档」的约定一致，不需要额外迁移动作。
     */
    val profileId: String = DeviceProfiles.DEFAULT_ID,
    /** 日期键，格式 "yyyy-MM-dd"（daily）或 "yyyy-MM-dd-HH"（hourly） */
    val dateKey: String,
    /** 记录时间戳（毫秒） */
    val timestamp: Long,
    /** 日流量原始字节数（设备 API 报告的当日累计值） */
    val dailyRawBytes: Long,
    /** 月流量原始字节数（设备 API 报告的当月累计值） */
    val monthlyRawBytes: Long,
    /** 记录类型："daily" 或 "hourly"，用于查询过滤 */
    val recordType: String = "daily",
    /**
     * 写入这条记录的数据源 id（[com.ufi_axis_widget.util.DataSourceType.id]），
     * 空串表示未知（v5 之前的历史记录）。
     *
     * 为什么需要：不同数据源的日用量语义不同 —— UFI-TOOLS 是设备直接上报的当日值，
     * goform 只有月累计、日用量是本地按「单日结清」推算的。两者混在同一条曲线里，
     * 切换数据源后会出现无法解释的台阶，而且事后完全无法区分是哪一种。
     *
     * 注意唯一索引是 ([profileId], [dateKey])，不含本字段：同一台设备同一天内切换
     * 数据源时后写的会覆盖前一条。这是有意为之的取舍 —— 再把 source 加进唯一索引
     * 要连带改掉全部查询，而这个场景是低频的；有了本字段至少能看出「今天这条来自谁」。
     */
    val source: String = ""
)