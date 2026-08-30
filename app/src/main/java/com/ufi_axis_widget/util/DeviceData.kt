package com.ufi_axis_widget.util

/*
 * 设备数据的公共模型层。
 *
 * 这些类原先声明在 WifiCrawlUfiTools 文件末尾，与 UFI-TOOLS 的采集实现混在一起。
 * 独立出来后，任何数据源（UFI-TOOLS / goform 直连 / 自制接口）都填充同一组模型，
 * UI 层无需感知数据来自哪个源。
 *
 * 与模型保持在同一个包（`com.ufi_axis_widget.util`），因此搬迁不会引起任何 import 变化。
 *
 * 注意：WifiEntity 中 `flow`/`signal`/`temp`/`cpu`/`mem`/`battery` 等字段存的是
 * **已格式化字符串**（如 `"0.00 GB"`、`"-95dBm"`、`"60.6℃"`）。新数据源必须复用
 * DeviceFormat 中的格式化函数，否则 UI 显示会不一致。
 */

/** 数据源类型。持久化时以 [id] 存入 SharedPreferences，不要改动已有 id 字符串。 */
enum class DataSourceType(val id: String, val displayName: String) {
    /** 经 UFI-TOOLS HTTP 服务采集（kano 签名 + token），可拿到全部字段 */
    UFI_TOOLS("ufi_tools", "UFI-TOOLS"),

    /** 直连设备原生 goform 接口（SHA-256 挑战登录 + session cookie），无 CPU/内存/温度 */
    GOFORM("goform", "Goform 直连"),

    /** 经设备上的 UFI-AXIS core 服务采集（配对换 Bearer Token，字段已归一化） */
    UFI_AXIS("ufi_axis", "UFI-AXIS");

    companion object {
        fun fromId(id: String?): DataSourceType =
            entries.firstOrNull { it.id == id } ?: UFI_TOOLS
    }
}

/**
 * 数据源能力声明。
 *
 * UI 用它决定某个面板是显示数据还是显示「暂无数据」，避免为每个源写死分支判断。
 * 新增数据源时只需给出自己的能力集合。
 */
data class DeviceCapabilities(
    /** CPU 占用率与各核心频率 */
    val cpu: Boolean,
    /** 内存 / Swap 用量 */
    val memory: Boolean,
    /** 温度（含各模块温度列表） */
    val temperature: Boolean,
    /** 内部 / 外部存储用量 */
    val storage: Boolean,
    /** 电池电流与电压明细（电量百分比属于基础能力，不在此列） */
    val batteryDetail: Boolean,
    /** AT 指令透传得到的详细网络参数（rsrp/sinr/band/pci/earfcn 等） */
    val atNetwork: Boolean,
    /** 日流量统计（goform 只有月累计，无当日值） */
    val dailyTraffic: Boolean,
) {
    companion object {
        /** UFI-TOOLS：全能力 */
        val UFI_TOOLS = DeviceCapabilities(
            cpu = true, memory = true, temperature = true, storage = true,
            batteryDetail = true, atNetwork = true, dailyTraffic = true,
        )

        /**
         * goform 直连：有网络 / SIM / 流量 / 电量，没有设备本体的性能与存储数据。
         *
         * 依据 UFI-AXIS `core/goform` 的完整 cmd 清单核实——goform 协议里
         * 不存在温度、CPU 占用、内存用量、存储用量、电池电流/电压的任何字段，
         * 也没有当日流量（只有 `monthly_rx_bytes` / `monthly_tx_bytes` 月累计）。
         *
         * 网络明细能力为 true：goform 虽然没有 AT 透传，但
         * `lte_rsrp` / `Lte_snr` / `lte_rsrq` / `Lte_pci` / `Lte_fcn` / `Lte_bands` /
         * `cell_id` / `network_provider` 足以填充 [AtSignalInfo] 的主要字段。
         */
        val GOFORM = DeviceCapabilities(
            cpu = false, memory = false, temperature = false, storage = false,
            batteryDetail = false, atNetwork = true, dailyTraffic = false,
        )

        /**
         * UFI-AXIS core：全能力。
         *
         * core 自己就跑在设备上，`/api/system` 下各接口直接读 Android 侧的 CPU / 内存 /
         * 温度 / 存储，`/api/network/signal` 给的信号明细比 AT 透传还整齐
         * （频段已拼成 `n78` / `B3`，NR 优先 LTE 兜底由服务端派生）。
         */
        val UFI_AXIS = DeviceCapabilities(
            cpu = true, memory = true, temperature = true, storage = true,
            batteryDetail = true, atNetwork = true, dailyTraffic = true,
        )

        fun of(type: DataSourceType): DeviceCapabilities = when (type) {
            DataSourceType.UFI_TOOLS -> UFI_TOOLS
            DataSourceType.GOFORM -> GOFORM
            DataSourceType.UFI_AXIS -> UFI_AXIS
        }
    }
}

data class WifiEntity(
    val model: String,
    val flow: String,
    val dailyFlow: String,
    val signal: String,
    val temp: String,
    val battery: String,
    val batteryPercent: Int,        // 原始电池百分比（-1 为无数据）
    /**
     * 是否正在充电。
     *
     * 由各数据源自己判定（UFI-TOOLS 看充电电流、goform 看 `battery_charging`、
     * UFI-AXIS 看 `is_charging`），而不是让 UI 去猜 —— 小组件原先靠解析
     * `batteryCurrent` 的 mA 文本反推，只有 UFI-TOOLS 有这个字段，
     * 另外两个源永远判成「未充电」。
     */
    val batteryCharging: Boolean,

    val cpu: String,
    val mem: String,
    val netType: String,
    // === 采集接口自身的版本信息（UFI-TOOLS 为 /api/baseDeviceInfo 的 app_ver）===
    val appVer: String,           // 接口版本号
    val appVerCode: String,       // 接口构建代码 (如 20260601)
    val batteryCurrent: String,   // 电池电流 (mA)
    val batteryVoltage: String,   // 电池电压 (V)
    val internalStorage: String,  // 内部存储 已用/总容量 (格式化)
    val internalAvailableStorage: Long,  // 内部存储可用 (Bytes)
    val internalTotalStorage: Long,      // 内部存储总量 (Bytes)
    val internalUsedStorage: Long,       // 内部存储已用 (Bytes)
    val externalTotalStorage: Long,      // 外部存储总量 (Bytes)，0 表示无外部存储
    val externalUsedStorage: Long,       // 外部存储已用 (Bytes)
    val externalAvailableStorage: Long,  // 外部存储可用 (Bytes)
    val clientIp: String,         // 设备 IP 地址
    // === 设备硬件标识 ===
    val deviceModel: String,      // 设备硬件型号 (如 U30 Air)
    val firmwareVer: String,      // 固件版本
    /** 是否需要登录验证。仅 UFI-TOOLS 源有意义，其他源恒为 false */
    val needToken: Boolean,
    /** AT 指令透传解析结果。源不支持 AT 时为 null，UI 需按 [DeviceCapabilities.atNetwork] 降级 */
    val atNetworkInfo: AtSignalInfo?,
    // === 详细硬件信息（用于主界面弹窗详情，源不支持时为空集合）===
    val cpuTempList: List<CpuTempItem>,
    val cpuFreqInfo: Map<String, CpuFreqItem>,
    val cpuUsageInfo: Map<String, String>,
    val memTotalKb: Long,
    val memAvailableKb: Long,
    val memUsedKb: Long,
    val swapTotalKb: Long,
    val swapUsedKb: Long,
    val swapFreeKb: Long,
    // === 设备身份 + 网络承载 ===
    val wanIp: String,            // WAN IPv4 地址
    val wanIpv6: String,          // WAN IPv6 地址
    val pdpType: String,          // PDP 承载类型（IPv4/IPv6/IPv4v6）
    val imei: String,
    val imsi: String,
    val iccid: String,
    val hardwareVersion: String,  // 硬件版本号
    val webVersion: String,       // Web/固件版本号
    val macAddress: String,
    val pinStatusCode: Int,       // SIM PIN 状态：0=已解锁，1=需PIN，2=PUK锁定，-1=无数据
    val monthlyUploadBytes: Long, // 当月上行流量 (Bytes)
    val monthlyDownloadBytes: Long, // 当月下行流量 (Bytes)
    val dailyRawBytes: Long,       // 日流量原始字节数
    val monthlyRawBytes: Long,     // 月流量原始字节数
)

data class AtSignalInfo(
    val networkType: String,   // "5G SA" / "4G LTE" / "NR" / "LTE"
    val operator: String,      // 运营商原始值（如 "46001" / "CHN-UNICOM"）
    val carrier: String,       // PLMN 映射的真实运营商（如 "中国联通 CUCC"）
    val rsrp: Int,             // RSRP (dBm)，负值，如 -95
    val sinr: Int,             // SINR (dB)，如 15
    val rsrq: Int,             // RSRQ (dB)，如 -10
    val band: String,          // 频段，如 "n78" / "B3"
    val pci: Int,              // Physical Cell ID
    val earfcn: Int,           // EARFCN / NR-ARFCN
    val rawQeng: String,       // 信号原始响应（调试用）
    val rawCops: String,       // AT+COPS 原始响应（调试用）
    val imei: String,          // AT+CGSN 获取的 IMEI
    val subscriptionRate: String, // AT+CGEQOSRDP=1 获取的签约速率
    val tac: String,           // Tracking Area Code (LTE) / NR TAC
    val cellId: String,        // Cell ID (LTE) / NR CI
    // === AT 新增字段 ===
    val moduleModel: String,       // AT+CGMM 模块型号
    val firmwareDetail: String,    // AT+CGMR 固件详细版本
    val cregStat: Int,             // AT+CREG? 网络注册状态码
    val lteRegistration: String,   // AT+CREG? 注册状态文本
    val wanIpAt: String,           // AT+CGCONTRDP=1 获取的 WAN IP
    val dnsServers: String,        // AT+CGCONTRDP=1 获取的 DNS 服务器
    val pinStatusAt: String,        // AT+CPIN? PIN 状态 (READY/SIM PIN/SIM PUK)
    val rfFunc: String,            // AT+CFUN? 射频功能状态
    val moduleState: String,       // AT+CPAS 模块活动状态
    val psAttached: String,        // AT+CGATT? PS 域附着
)

data class CpuTempItem(
    val type: String,   // 模块名称 (如 pa-thmzone, gpu-thmzone)
    val temp: Double,   // 原始温度值 (>1000 时需 /1000)
)

data class CpuFreqItem(
    val cur: Int,   // 当前频率 MHz
    val max: Int,   // 最大频率 MHz
)

/**
 * 轻量级通知监控数据。
 *
 * 仅包含通知阈值检查所需字段，不含完整设备状态。原先是 `WifiCrawlUfiTools` 的嵌套类，
 * 提升为顶层类以便各数据源共用。
 */
data class NotificationBaseInfo(
    val dailyFlowStr: String,
    val monthlyFlowStr: String,
    val tempStr: String,
    val cpuStr: String,
    val memStr: String,
    val batteryPercent: Int,
)
