package com.ufi_axis_widget.util.source

import android.content.Context
import com.ufi_axis_widget.util.DataSourceType
import com.ufi_axis_widget.util.DeviceCapabilities
import com.ufi_axis_widget.util.NotificationBaseInfo
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.WifiEntity

/**
 * 设备数据源抽象。
 *
 * 上层（ViewModel / Worker / Monitor / 小组件）只依赖这个接口，不再直接引用
 * 任何具体采集实现。新增一种采集方式 = 新增一个实现 + 在
 * [DeviceDataSourceRegistry.of] 里登记一行。
 *
 * 实现约定：
 * - 所有方法都是挂起函数，实现内部自行切到 IO 调度器；
 * - 失败返回 null 而非抛异常，错误原因写入 [lastError]；
 * - 填充 [WifiEntity] 时必须复用 `DeviceFormat.kt` 的格式化函数；
 * - 自己拿不到的字段填空字符串 / 空集合 / -1，并在 [capabilities] 中声明为
 *   不支持，UI 会据此显示「暂无数据」而不是显示一个假的 0。
 */
interface DeviceDataSource {

    /** 数据源类型标识 */
    val type: DataSourceType

    /** 本数据源能提供哪些维度的数据，供 UI 决定降级展示 */
    val capabilities: DeviceCapabilities

    /** 最近一次错误描述（供调试日志与主界面错误提示使用） */
    val lastError: String

    /** 最近一次原始响应（脱敏后写入调试日志；不支持的实现返回空串） */
    val lastRawResponse: String

    /**
     * 获取完整设备状态。
     *
     * @param quickStart 冷启动快速模式：跳过为提高读数准确性而插入的等待
     */
    suspend fun getWifiData(context: Context, quickStart: Boolean = false): WifiEntity?

    /**
     * 轻量获取通知阈值所需字段，用于高频后台轮询。
     * 开销应显著低于 [getWifiData]。
     */
    suspend fun fetchNotificationBaseInfo(context: Context): NotificationBaseInfo?

    /**
     * 探测应使用的协议，返回 `"https"` / `"http"`，无法确定返回 null。
     * 不需要协议探测的实现可直接返回固定值。
     */
    suspend fun probeProtocol(context: Context): String?

    /**
     * 心跳检测（TCP ping）应连的端口。
     *
     * 必须由数据源自报：不同源的服务端口不同（UFI-TOOLS 默认 2333，
     * goform 走设备 Web 后台端口），若统一用 `device_address` 解析出的端口，
     * 非 UFI-TOOLS 源会 ping 到一个关闭的端口而被误判为设备离线。
     */
    fun probePort(context: Context): Int
}

/**
 * 数据源注册表。
 *
 * 唯一的实现解析入口。调用方一律用 [current] 拿当前生效的数据源，
 * 不要缓存返回值——用户在设置里切换数据源后应当立即生效。
 */
object DeviceDataSourceRegistry {

    /** 按类型取实现（各实现都是无状态单例或自带会话缓存的单例） */
    fun of(type: DataSourceType): DeviceDataSource = when (type) {
        DataSourceType.UFI_TOOLS -> UfiToolsDataSource
        DataSourceType.GOFORM -> GoformDataSource
        DataSourceType.UFI_AXIS -> UfiAxisDataSource
    }

    /** 取用户当前选择的数据源 */
    fun current(context: Context): DeviceDataSource = of(SPUtil.getDataSourceType(context))

    /** 当前数据源的能力集合（UI 降级判断用的快捷入口） */
    fun currentCapabilities(context: Context): DeviceCapabilities = current(context).capabilities
}
