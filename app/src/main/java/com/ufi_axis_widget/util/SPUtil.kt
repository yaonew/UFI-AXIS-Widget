package com.ufi_axis_widget.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

object SPUtil {

    // ── 预编译 Regex：地址解析 ──
    private val PROTOCOL_RE = Regex("^(https?)://([^:/]+)(?::(\\d+))?/?$")
    private val HOST_PORT_RE = Regex("^([^:/]+)(?::(\\d+))?$")
    private val IPV4_RE = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    // ── 缓存 SimpleDateFormat：避免每次 saveData 都重新创建（线程安全） ──
    private val saveTimeFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

    // ── 缓存 baseUrl 构建结果：地址不变时跳过 regex 解析 + SP 读取 ──
    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedBaseUrlKey: String? = null  // address+protocol 组合作为 cache key

    /**
     * 配置读写入口。
     *
     * 返回的是**当前配置档视角**的 prefs：默认档就是裸 `wifi_data`，
     * 非默认档下设备相关的键会被自动重定向到 `p_<id>_` 前缀
     * （见 [DeviceProfiles]）。因此本文件里所有 `getSp(ctx).getXxx("裸键")`
     * 都不需要关心档位。
     */
    fun getSp(ctx: Context): SharedPreferences = DeviceProfiles.prefs(ctx)

    /**
     * 保存 WiFi 数据到 SharedPreferences（线程安全，异步写入）。
     * 使用 @Synchronized + apply()：内存立即更新（后续读取可见），磁盘写入异步。
     * @Synchronized 避免 Worker 与前台并发调用时的写入交错。
     */
    @Synchronized
    fun saveData(ctx: Context, data: WifiEntity) {
        val time = synchronized(saveTimeFormat) { saveTimeFormat.format(java.util.Date()) }
        // 计算数据字段哈希，供小组件渲染去重使用（避免每次渲染都读 14 个 SP 字段）
        val dataHash = computeWidgetDataHash(data, time)
        getSp(ctx).edit()
            .putString("flow", data.flow)
            .putString("daily_flow", data.dailyFlow)
            .putString("signal", data.signal)
            .putString("temp", data.temp)
            .putString("battery", data.battery)
            .putInt("battery_percent", data.batteryPercent)
            .putBoolean("battery_charging", data.batteryCharging)
            .putString("model", data.model)
            .putString("cpu", data.cpu)
            .putString("mem", data.mem)
            .putString("net_type", data.netType)
            .putString("app_ver", data.appVer)
            .putString("app_ver_code", data.appVerCode)
            .putString("battery_current", data.batteryCurrent)
            .putString("battery_voltage", data.batteryVoltage)
            .putString("internal_storage", data.internalStorage)
            .putLong("internal_total_storage", data.internalTotalStorage)
            .putLong("internal_used_storage", data.internalUsedStorage)
            .putLong("internal_available_storage", data.internalAvailableStorage)
            .putLong("external_total_storage", data.externalTotalStorage)
            .putLong("external_used_storage", data.externalUsedStorage)
            .putLong("external_available_storage", data.externalAvailableStorage)
            .putString("client_ip", data.clientIp)
            .putString("device_model", data.deviceModel)
            .putString("firmware_ver", data.firmwareVer)
            .putBoolean("need_token", data.needToken)
            .putString("update_time", time)
            .putInt("sp_cached_data_hash", dataHash)
            // 同时保存 AT 命令解析的网络制式，供小组件优先使用（AT 比 Goform 稳定）
            .putString("at_net_type", data.atNetworkInfo?.networkType ?: "")
            // goform 专用小组件的信号详情字段：这些值原本只在主界面用，
            // 小组件读不到就只能留空槽位，所以一并落盘
            .putString("at_carrier", data.atNetworkInfo?.let { it.carrier.ifEmpty { it.operator } } ?: "")
            .putString("at_band", data.atNetworkInfo?.band ?: "")
            .putInt("at_pci", data.atNetworkInfo?.pci ?: 0)
            .putInt("at_sinr", data.atNetworkInfo?.sinr ?: Int.MIN_VALUE)
            .apply()
    }

    /**
     * 计算数据字段的哈希指纹（仅影响小组件渲染的数据字段，不含外观设置）。
     * 与 [computeDataHash] 中数据部分算法一致，保证相同数据产生相同哈希值。
     * 结果缓存在 SP 的 `sp_cached_data_hash` 中，供小组件渲染去重使用。
     */
    private fun computeWidgetDataHash(data: WifiEntity, time: String): Int {
        var h = 17
        h = 31 * h + data.deviceModel.hashCode()
        h = 31 * h + data.model.hashCode()
        h = 31 * h + data.firmwareVer.hashCode()
        h = 31 * h + data.flow.hashCode()
        h = 31 * h + data.dailyFlow.hashCode()
        h = 31 * h + data.signal.hashCode()
        h = 31 * h + data.temp.hashCode()
        h = 31 * h + data.battery.hashCode()
        h = 31 * h + data.batteryPercent  // 电量百分比也应参与哈希
        h = 31 * h + data.batteryCharging.hashCode()  // 充电状态变化要能触发重绘
        h = 31 * h + data.cpu.hashCode()
        h = 31 * h + data.mem.hashCode()
        h = 31 * h + data.netType.hashCode()
        h = 31 * h + data.appVerCode.hashCode()
        h = 31 * h + data.batteryCurrent.hashCode()
        h = 31 * h + data.batteryVoltage.hashCode()       // 电池电压
        h = 31 * h + data.internalStorage.hashCode()      // 内部存储
        h = 31 * h + data.clientIp.hashCode()             // 客户端 IP
        // goform 专用小组件展示的信号详情：不纳入哈希会被渲染去重吞掉，
        // 表现为「频段/PCI 变了但小组件不刷新」
        h = 31 * h + (data.atNetworkInfo?.networkType ?: "").hashCode()
        h = 31 * h + (data.atNetworkInfo?.carrier ?: "").hashCode()
        h = 31 * h + (data.atNetworkInfo?.band ?: "").hashCode()
        h = 31 * h + (data.atNetworkInfo?.pci ?: 0)
        h = 31 * h + (data.atNetworkInfo?.sinr ?: 0)
        h = 31 * h + time.hashCode()
        return h
    }

    /** 读取缓存的 widget 数据哈希（由 [saveData] 写入），0 表示尚未缓存 */
    fun getCachedDataHash(ctx: Context): Int = getSp(ctx).getInt("sp_cached_data_hash", 0)

    /**
     * 只把「更新时间」推到当下，其余缓存字段一个不动。
     *
     * 供 UFI-AXIS 在每次真正问到设备之后手动打点。为什么需要单独一个入口：
     * [saveData] 里的时间戳只在整份数据通过合理性校验、真正落盘时才前进，
     * 一旦某轮的字段被判为脏数据（负流量等），采集其实发生过，界面上的时间却停在上一次，
     * 看起来就像「很久没刷新」。
     *
     * 同时把新时间揉进缓存哈希：小组件靠哈希做渲染去重，只改 update_time 而不动哈希的话，
     * 下一次渲染会认为「数据没变」直接跳过，时间照样不动。
     */
    fun touchUpdateTime(ctx: Context) {
        val time = synchronized(saveTimeFormat) { saveTimeFormat.format(java.util.Date()) }
        val sp = getSp(ctx)
        sp.edit()
            .putString("update_time", time)
            .putInt("sp_cached_data_hash", 31 * sp.getInt("sp_cached_data_hash", 0) + time.hashCode())
            .apply()
    }

    /**
     * 清掉「上一台设备」的运行时缓存：展示值 + 设备身份缓存。
     *
     * 切换配置档时必须调用（[DeviceProfiles.activate]）。这些键都是**全局**的
     * ——它们是「最后一次采集到的东西」，不按档存，所以换档后必须主动抹掉，
     * 否则小组件会拿着旧设备的型号/流量继续显示，直到下一轮采集才纠正。
     */
    fun clearDeviceRuntimeCache(ctx: Context) {
        val editor = getSp(ctx).edit()
        val keys = listOf(
            // saveData 写入的展示值
            "flow", "daily_flow", "signal", "temp", "battery", "battery_percent",
            "model", "cpu", "mem", "net_type", "app_ver", "app_ver_code",
            "battery_current", "battery_voltage", "internal_storage",
            "internal_total_storage", "internal_used_storage", "internal_available_storage",
            "external_total_storage", "external_used_storage", "external_available_storage",
            "client_ip", "device_model", "firmware_ver", "need_token", "update_time",
            "sp_cached_data_hash",
            "at_net_type", "at_carrier", "at_band", "at_pci", "at_sinr",
            "cached_monthly_data", "derived_daily_flow",
            // 设备身份 / 平台探测缓存：留着会把上一台设备的 IMEI、模块型号带过来
            "device_platform", "cache_at_cgmm", "cache_at_cgmr", "cache_at_cgsn",
            "cache_at_static_time",
            "cache_version_info_json", "cache_version_info_time",
            "cache_need_token_json", "cache_need_token_time"
        )
        for (key in keys) editor.remove(key)
        editor.apply()
    }

    /** 获取 AT 命令解析的网络制式（小组件优先使用，比 Goform 稳定） */
    fun getAtNetType(ctx: Context): String = getSp(ctx).getString("at_net_type", "") ?: ""

    // 认证与配置
    fun saveRawToken(ctx: Context, token: String) = getSp(ctx).edit().putString("raw_token", token).apply()
    fun getRawToken(ctx: Context) = getSp(ctx).getString("raw_token", "admin") ?: "admin"
    fun saveAuthToken(ctx: Context, token: String) = getSp(ctx).edit().putString("auth_token", token).apply()
    fun getAuthToken(ctx: Context) = getSp(ctx).getString("auth_token", "") ?: ""

    // 刷新频率 (单位: 分钟) — 后台 Worker 间隔
    fun setRefreshInterval(ctx: Context, minutes: Int) = getSp(ctx).edit().putInt("refresh_interval", minutes).apply()
    fun getRefreshInterval(ctx: Context) = getSp(ctx).getInt("refresh_interval", 15)

    // 主界面自动刷新间隔 (单位: 秒) — 前台轮询间隔，0 表示关闭
    fun setMainRefreshSeconds(ctx: Context, seconds: Int) = getSp(ctx).edit().putInt("main_refresh_seconds", seconds).apply()
    fun getMainRefreshSeconds(ctx: Context) = getSp(ctx).getInt("main_refresh_seconds", 5)

    // 显隐设置
    fun setWidgetSettings(ctx: Context, flow: Boolean, signal: Boolean, temp: Boolean, cpu: Boolean, model: Boolean, time: Boolean) {
        getSp(ctx).edit()
            .putBoolean("show_flow", flow)
            .putBoolean("show_signal", signal)
            .putBoolean("show_temp", temp)
            .putBoolean("show_cpu", cpu)
            .putBoolean("show_model", model)
            .putBoolean("show_time", time)
            .apply()
    }

    fun getShowFlow(ctx: Context) = getSp(ctx).getBoolean("show_flow", true)
    fun getShowSignal(ctx: Context) = getSp(ctx).getBoolean("show_signal", true)
    fun getShowTemp(ctx: Context) = getSp(ctx).getBoolean("show_temp", true)
    fun getShowCpu(ctx: Context) = getSp(ctx).getBoolean("show_cpu", true)
    fun getShowModel(ctx: Context) = getSp(ctx).getBoolean("show_model", true)
    fun getShowTime(ctx: Context) = getSp(ctx).getBoolean("show_time", true)
    fun getShowBattery(ctx: Context) = getSp(ctx).getBoolean("show_battery", true)
    fun getShowMem(ctx: Context) = getSp(ctx).getBoolean("show_mem", true)

    // ==================== 各尺寸独立显隐设置 ====================
    // 2×1 迷你版（默认：信号+电池+网络类型 开启）
    fun getShowSignal2x1(ctx: Context) = getSp(ctx).getBoolean("show_signal_2x1", true)
    fun setShowSignal2x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_signal_2x1", show).apply()
    fun getShowBattery2x1(ctx: Context) = getSp(ctx).getBoolean("show_battery_2x1", true)
    fun setShowBattery2x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_battery_2x1", show).apply()
    fun getShowNetwork2x1(ctx: Context) = getSp(ctx).getBoolean("show_network_2x1", true)
    fun setShowNetwork2x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_network_2x1", show).apply()

    // 4×1 条形版（默认：全部开启）
    fun getShowModel4x1(ctx: Context) = getSp(ctx).getBoolean("show_model_4x1", true)
    fun setShowModel4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_model_4x1", show).apply()
    fun getShowSignal4x1(ctx: Context) = getSp(ctx).getBoolean("show_signal_4x1", true)
    fun setShowSignal4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_signal_4x1", show).apply()
    fun getShowBattery4x1(ctx: Context) = getSp(ctx).getBoolean("show_battery_4x1", true)
    fun setShowBattery4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_battery_4x1", show).apply()
    fun getShowTemp4x1(ctx: Context) = getSp(ctx).getBoolean("show_temp_4x1", true)
    fun setShowTemp4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_temp_4x1", show).apply()
    fun getShowTime4x1(ctx: Context) = getSp(ctx).getBoolean("show_time_4x1", true)
    fun setShowTime4x1(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_time_4x1", show).apply()

    // ==================== 各尺寸独立字体大小 ====================
    // 2×1 迷你版字体大小（sp，默认 9）
    fun getFontSize2x1(ctx: Context) = getSp(ctx).getInt("font_size_2x1", 9)
    fun setFontSize2x1(ctx: Context, sp: Int) = getSp(ctx).edit().putInt("font_size_2x1", sp).apply()
    // 4×1 条形版字体大小（sp，默认 9）
    fun getFontSize4x1(ctx: Context) = getSp(ctx).getInt("font_size_4x1", 9)
    fun setFontSize4x1(ctx: Context, sp: Int) = getSp(ctx).edit().putInt("font_size_4x1", sp).apply()

    fun setShowFlow(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_flow", show).apply()
    fun setShowSignal(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_signal", show).apply()
    fun setShowTemp(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_temp", show).apply()
    fun setShowCpu(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_cpu", show).apply()
    fun setShowModel(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_model", show).apply()
    fun setShowTime(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_time", show).apply()
    fun setShowBattery(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_battery", show).apply()
    fun setShowMem(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_mem", show).apply()

    fun getShowDivider(ctx: Context) = getSp(ctx).getBoolean("show_divider", true)
    fun setShowDivider(ctx: Context, show: Boolean) = getSp(ctx).edit().putBoolean("show_divider", show).apply()

    /** 统计已启用的显示项数量 */
    fun getEnabledCount(ctx: Context): Int {
        var count = 0
        if (getShowFlow(ctx)) count++
        if (getShowSignal(ctx)) count++
        if (getShowTemp(ctx)) count++
        if (getShowCpu(ctx)) count++
        if (getShowModel(ctx)) count++
        if (getShowTime(ctx)) count++
        if (getShowBattery(ctx)) count++
        if (getShowMem(ctx)) count++
        if (getShowDivider(ctx)) count++
        return count
    }

    // ── 小组件渲染配置批量读取（减少多次 SP 读取开销） ──

    /** 小组件显隐 + 外观配置一次性读取结果，供渲染时使用 */
    data class WidgetRenderConfig(
        val showFlow: Boolean, val showSignal: Boolean, val showTemp: Boolean,
        val showCpu: Boolean, val showModel: Boolean, val showTime: Boolean,
        val showBattery: Boolean, val showMem: Boolean,
        val showDivider: Boolean,
        val isDark: Boolean, val shouldClip: Boolean,
        val bgOpacity: Int, val bgImageUri: String
    )

    /** 一次性读取所有小组件渲染配置，避免多次独立 SP 读取 */
    fun loadWidgetRenderConfig(ctx: Context): WidgetRenderConfig {
        val sp = getSp(ctx)
        return WidgetRenderConfig(
            showFlow = sp.getBoolean("show_flow", true),
            showSignal = sp.getBoolean("show_signal", true),
            showTemp = sp.getBoolean("show_temp", true),
            showCpu = sp.getBoolean("show_cpu", true),
            showModel = sp.getBoolean("show_model", true),
            showTime = sp.getBoolean("show_time", true),
            showBattery = sp.getBoolean("show_battery", true),
            showMem = sp.getBoolean("show_mem", true),
            showDivider = sp.getBoolean("show_divider", true),
            isDark = isWidgetDark(ctx),
            shouldClip = sp.getBoolean("widget_clip_to_outline", false),
            bgOpacity = sp.getInt("widget_bg_opacity", 100),
            bgImageUri = sp.getString("widget_bg_image_uri", "") ?: ""
        )
    }

    fun getCachedMonthlyData(ctx: Context): Long = getSp(ctx).getLong("cached_monthly_data", 0L)
    fun setCachedMonthlyData(ctx: Context, bytes: Long) = getSp(ctx).edit().putLong("cached_monthly_data", bytes).apply()

    fun isFirstRun(ctx: Context) = getSp(ctx).getBoolean("is_first_run", true)
    fun setFirstRun(ctx: Context, value: Boolean) = getSp(ctx).edit().putBoolean("is_first_run", value).apply()

    // ==================== Worker 失败状态（线程安全读写） ====================
    /** 失败原因类型：空字符串=未失败, "network"=网络不通, "api"=端口/Token错误 */
    fun getWorkerStopReason(ctx: Context) = getSp(ctx).getString("worker_stop_reason", "") ?: ""

    /** worker 是否因连续失败被停止 */
    fun isWorkerStopped(ctx: Context) = getSp(ctx).getBoolean("worker_stopped_by_failure", false)

    /** API 连续失败计数 */
    fun getApiFailureCount(ctx: Context) = getSp(ctx).getInt("worker_api_failure_count", 0)

    /** 网络连续失败计数 */
    fun getNetworkFailureCount(ctx: Context) = getSp(ctx).getInt("worker_network_failure_count", 0)

    /** 获取失败原因汇总（供外部 UI 显示） */
    fun getWorkerFailureSummary(ctx: Context): String {
        if (!isWorkerStopped(ctx)) return ""
        return getWorkerStopReason(ctx).ifEmpty { "unknown" }
    }

    /** 小组件是否处于「正在重试」状态（用户点击刷新后、Worker 执行完毕前） */
    fun isReconnecting(ctx: Context) = getSp(ctx).getBoolean("widget_reconnecting", false)

    /** 设置小组件「正在重试」状态 */
    fun setReconnecting(ctx: Context, value: Boolean) {
        getSp(ctx).edit().putBoolean("widget_reconnecting", value).apply()
        DebugLogger.d("SPUtil", "setReconnecting=$value")
    }

    /** 原子递增网络失败计数，返回递增后的值 */
    @Synchronized
    fun incrementNetworkFailureCount(ctx: Context): Int {
        val sp = getSp(ctx)
        val count = sp.getInt("worker_network_failure_count", 0) + 1
        sp.edit().putInt("worker_network_failure_count", count).apply()
        return count
    }

    /** 原子递增 API 失败计数，返回递增后的值 */
    @Synchronized
    fun incrementApiFailureCount(ctx: Context): Int {
        val sp = getSp(ctx)
        val count = sp.getInt("worker_api_failure_count", 0) + 1
        sp.edit().putInt("worker_api_failure_count", count).apply()
        return count
    }

    /** 仅重置网络失败计数（ping 恢复时） */
    @Synchronized
    fun resetNetworkFailureCount(ctx: Context) {
        getSp(ctx).edit().putInt("worker_network_failure_count", 0).apply()
    }

    /** 重置所有失败状态（手动刷新、配置变更、Worker 启动时调用） */
    @Synchronized
    fun resetWorkerFailureState(ctx: Context) {
        val sp = getSp(ctx)
        val prevStopped = sp.getBoolean("worker_stopped_by_failure", false)
        sp.edit()
            .putInt("worker_api_failure_count", 0)
            .putInt("worker_network_failure_count", 0)
            .putBoolean("worker_stopped_by_failure", false)
            .putString("worker_stop_reason", "")
            .apply()
        DebugLogger.i("SPUtil", "resetWorkerFailureState called (prevStopped=$prevStopped)")
    }

    /** 标记网络不通导致的 Worker 停止 */
    @Synchronized
    fun markWorkerStoppedNetwork(ctx: Context) {
        getSp(ctx).edit()
            .putBoolean("worker_stopped_by_failure", true)
            .putInt("worker_api_failure_count", 0)
            .putString("worker_stop_reason", "network")
            .apply()
    }

    /** 标记 API 连续失败导致的 Worker 停止 */
    @Synchronized
    fun markWorkerStoppedApi(ctx: Context) {
        getSp(ctx).edit()
            .putBoolean("worker_stopped_by_failure", true)
            .putString("worker_stop_reason", "api")
            .apply()
    }

    // ==================== 设备连接配置 ====================
    const val DEFAULT_DEVICE_ADDRESS = "192.168.0.1:2333"

    /**
     * 地址与协议按数据源分槽。
     *
     * 三个源对「地址」的要求根本不同：UFI-TOOLS 要带 2333 端口，goform 走 80，
     * UFI-AXIS 走 8088。共用一个槽的结果就是切源之后地址还是上一个源的，
     * 采集必然失败，而用户完全看不出哪里不对。
     *
     * UFI-TOOLS 继续用没有后缀的老键，升级上来的配置原地生效；
     * 另两个源的键在没写过时回退读老键，所以「同一台设备换个源」不用重新填地址。
     */
    private fun sourceSuffix(ctx: Context): String = when (getDataSourceType(ctx)) {
        DataSourceType.UFI_TOOLS -> ""
        DataSourceType.GOFORM -> "_goform"
        DataSourceType.UFI_AXIS -> "_ufi_axis"
    }

    /** 获取设备地址（单一字段，支持 IP:端口 或 域名） */
    fun getDeviceAddress(ctx: Context): String {
        val sp = getSp(ctx)
        val own = sp.getString("device_address${sourceSuffix(ctx)}", null)?.takeIf { it.isNotBlank() }
        return own
            ?: sp.getString("device_address", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DEVICE_ADDRESS
    }

    /** 保存设备地址（同时重置协议探测结果与响应缓存，下次自动重探） */
    fun setDeviceAddress(ctx: Context, address: String) {
        val v = address.trim()
        val suffix = sourceSuffix(ctx)
        getSp(ctx).edit()
            .putString("device_address$suffix", v.ifEmpty { DEFAULT_DEVICE_ADDRESS })
            .putString("device_protocol$suffix", "auto")  // 地址变了，旧探测结果作废
            .apply()
        invalidateBaseUrlCache()  // 地址变更，清除 baseUrl 缓存
        invalidateResponseCaches(ctx)  // 设备换了，旧响应缓存全部作废
    }

    /** 从地址中提取主机部分（IP 或域名） */
    fun getDeviceHost(ctx: Context): String {
        return parseAddress(getDeviceAddress(ctx)).host
    }

    /** 从地址中提取端口号 */
    fun getDevicePortInt(ctx: Context): Int {
        return parseAddress(getDeviceAddress(ctx)).port
    }

    // ── 协议自动探测缓存 ──

    /** 获取自动探测到的协议（\"auto\" = 未探测 / \"http\" / \"https\"） */
    fun getDeviceProtocol(ctx: Context): String {
        val sp = getSp(ctx)
        return sp.getString("device_protocol${sourceSuffix(ctx)}", null)
            ?: sp.getString("device_protocol", "auto") ?: "auto"
    }

    /** 保存自动探测到的协议 */
    fun setDeviceProtocol(ctx: Context, protocol: String) =
        getSp(ctx).edit().putString("device_protocol${sourceSuffix(ctx)}", protocol).apply()

    /** 当前地址是否需要协议探测（域名或公网IP，且未显式写协议前缀） */
    fun needsProtocolProbe(ctx: Context): Boolean {
        val raw = getDeviceAddress(ctx).trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return false
        return !isPrivateOrLocalIp(parseAddress(raw).host)
    }

    /**
     * 构建完整 Base URL：
     * - 显式协议（http:// 或 https://）→ 直接使用
     * - 私有 IP → http://
     * - 域名/公网IP → 查缓存协议；若已探测到则用对应协议，否则默认 https://
     */
    /**
     * 构建基础 URL（线程安全：使用 @Synchronized 保证检查-写入原子性）。
     * 地址和协议未变更时，直接返回缓存结果。
     */
    @Synchronized
    fun buildBaseUrl(ctx: Context): String {
        val raw = getDeviceAddress(ctx)
        val protocol = getDeviceProtocol(ctx)
        val cacheKey = "${sourceSuffix(ctx)}|$raw|$protocol"
        // 缓存命中：地址和协议未变，直接返回上次构建结果
        if (cacheKey == cachedBaseUrlKey && cachedBaseUrl != null) {
            return cachedBaseUrl!!
        }

        val (parsedProtocol, host, parsedPort, protocolExplicit, portExplicit) = parseAddress(raw)

        val finalProtocol = if (protocolExplicit) {
            parsedProtocol
        } else {
            val stored = protocol
            if (stored == "auto") parsedProtocol else stored
        }

        val port = if (portExplicit) {
            parsedPort
        } else {
            when {
                finalProtocol == "https" -> 443
                isPrivateOrLocalIp(host) -> 2333
                else -> 80
            }
        }

        val result = "$finalProtocol://$host:$port/"
        cachedBaseUrl = result
        cachedBaseUrlKey = cacheKey
        return result
    }

    /** 清除 baseUrl 缓存（设备地址变更时调用） */
    fun invalidateBaseUrlCache() {
        cachedBaseUrl = null
        cachedBaseUrlKey = null
    }

    // ==================== API 接口高级配置 ====================
    const val DEFAULT_AT_COMMAND_PATH = "/api/AT"
    const val DEFAULT_DEVICE_INFO_PATH = "/api/baseDeviceInfo"
    const val DEFAULT_GOFORM_COMMAND_PATH = "/api/goform/goform_get_cmd_process"
    const val DEFAULT_NEED_TOKEN_PATH = "/api/need_token"
    const val DEFAULT_VERSION_INFO_PATH = "/api/version_info"
    //UFI-TOOLS文档中注明的固定秘钥：https://github.com/kanoqwq/UFI-TOOLS/blob/http-server-version/API_Doc.md
    const val DEFAULT_SECRET_KEY = "minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd"

    fun getAtCommandPath(ctx: Context) = getSp(ctx).getString("at_command_path", DEFAULT_AT_COMMAND_PATH) ?: DEFAULT_AT_COMMAND_PATH
    fun setAtCommandPath(ctx: Context, path: String) = getSp(ctx).edit().putString("at_command_path", path.ifBlank { DEFAULT_AT_COMMAND_PATH }).apply()

    fun getDeviceInfoPath(ctx: Context) = getSp(ctx).getString("device_info_path", DEFAULT_DEVICE_INFO_PATH) ?: DEFAULT_DEVICE_INFO_PATH
    fun setDeviceInfoPath(ctx: Context, path: String) = getSp(ctx).edit().putString("device_info_path", path.ifBlank { DEFAULT_DEVICE_INFO_PATH }).apply()

    fun getGoformCommandPath(ctx: Context) = getSp(ctx).getString("goform_command_path", DEFAULT_GOFORM_COMMAND_PATH) ?: DEFAULT_GOFORM_COMMAND_PATH
    fun setGoformCommandPath(ctx: Context, path: String) = getSp(ctx).edit().putString("goform_command_path", path.ifBlank { DEFAULT_GOFORM_COMMAND_PATH }).apply()

    fun getNeedTokenPath(ctx: Context) = getSp(ctx).getString("need_token_path", DEFAULT_NEED_TOKEN_PATH) ?: DEFAULT_NEED_TOKEN_PATH
    fun setNeedTokenPath(ctx: Context, path: String) = getSp(ctx).edit().putString("need_token_path", path.ifBlank { DEFAULT_NEED_TOKEN_PATH }).apply()

    fun getVersionInfoPath(ctx: Context) = getSp(ctx).getString("version_info_path", DEFAULT_VERSION_INFO_PATH) ?: DEFAULT_VERSION_INFO_PATH
    fun setVersionInfoPath(ctx: Context, path: String) = getSp(ctx).edit().putString("version_info_path", path.ifBlank { DEFAULT_VERSION_INFO_PATH }).apply()

    fun getSecretKey(ctx: Context) = getSp(ctx).getString("secret_key", DEFAULT_SECRET_KEY) ?: DEFAULT_SECRET_KEY
    fun setSecretKey(ctx: Context, key: String) = getSp(ctx).edit().putString("secret_key", key.ifBlank { DEFAULT_SECRET_KEY }).apply()

    // ==================== 数据源选择 ====================

    /**
     * 当前采集数据源。默认 UFI-TOOLS —— 老用户升级后行为不变。
     *
     * 连接相关的键已按源分槽：地址与协议带源后缀（见 [sourceSuffix]），端口与凭据
     * 本来就各有各的键（`goform_port`/`goform_password`、`ufi_axis_port`/
     * `ufi_axis_pair_password`、UFI-TOOLS 的 `raw_token`）。所以来回切源不会互相
     * 污染，也不会丢配置。
     *
     * 仍然共用的是「高级配置」那几个端点路径与签名密钥——它们只有 UFI-TOOLS 用到。
     */
    fun getDataSourceType(ctx: Context): DataSourceType =
        DataSourceType.fromId(getSp(ctx).getString("data_source", null))

    fun setDataSourceType(ctx: Context, type: DataSourceType) {
        getSp(ctx).edit().putString("data_source", type.id).apply()
        invalidateBaseUrlCache()
        invalidateResponseCaches(ctx)
    }

    // ==================== goform 直连配置 ====================

    /** goform Web 后台端口。ZTE 设备通常是 80 */
    const val DEFAULT_GOFORM_PORT = 80

    /** goform 后台登录口令（设备出厂默认 admin，非本应用私钥） */
    const val DEFAULT_GOFORM_PASSWORD = "admin"

    fun getGoformPort(ctx: Context) = getSp(ctx).getInt("goform_port", DEFAULT_GOFORM_PORT)
    fun setGoformPort(ctx: Context, port: Int) =
        getSp(ctx).edit().putInt("goform_port", if (port in 1..65535) port else DEFAULT_GOFORM_PORT).apply()

    fun getGoformPassword(ctx: Context) =
        getSp(ctx).getString("goform_password", DEFAULT_GOFORM_PASSWORD) ?: DEFAULT_GOFORM_PASSWORD
    fun setGoformPassword(ctx: Context, pwd: String) =
        getSp(ctx).edit().putString("goform_password", pwd.ifBlank { DEFAULT_GOFORM_PASSWORD }).apply()

    // ==================== UFI-AXIS core 配置 ====================
    //
    // 认证走「配对换 Token」：/pairing/info 拿配对码 → /pairing/confirm 换 token+secret，
    // 之后所有 /api/* 带 Authorization: Bearer <token>。
    // 配对密码要存下来 —— token 被服务端解绑后需要静默重配对，否则用户得手动再来一遍。

    /** UFI-AXIS core 默认端口 */
    const val DEFAULT_UFI_AXIS_PORT = 8088

    fun getUfiAxisPort(ctx: Context) = getSp(ctx).getInt("ufi_axis_port", DEFAULT_UFI_AXIS_PORT)
    fun setUfiAxisPort(ctx: Context, port: Int) =
        getSp(ctx).edit().putInt("ufi_axis_port", if (port in 1..65535) port else DEFAULT_UFI_AXIS_PORT).apply()

    fun getUfiAxisToken(ctx: Context) = getSp(ctx).getString("ufi_axis_token", "") ?: ""

    /** core 按设备公钥算出并回显的指纹，仅用于展示与排障（客户端不自报指纹） */
    fun getUfiAxisFingerprint(ctx: Context) = getSp(ctx).getString("ufi_axis_fingerprint", "") ?: ""

    fun saveUfiAxisPairing(ctx: Context, token: String, fingerprint: String) =
        getSp(ctx).edit()
            .putString("ufi_axis_token", token)
            .putString("ufi_axis_fingerprint", fingerprint)
            .apply()

    fun clearUfiAxisPairing(ctx: Context) =
        getSp(ctx).edit().remove("ufi_axis_token").remove("ufi_axis_fingerprint").apply()

    /** core 的出厂配对密码。首次配对时提交的密码会被 core 落库为设备密码 */
    const val DEFAULT_UFI_AXIS_PASSWORD = "admin"

    fun getUfiAxisPairPassword(ctx: Context) =
        getSp(ctx).getString("ufi_axis_pair_password", "")?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_UFI_AXIS_PASSWORD
    fun setUfiAxisPairPassword(ctx: Context, pwd: String) =
        getSp(ctx).edit().putString("ufi_axis_pair_password", pwd).apply()

    /**
     * 本机硬件级稳定标识，作为 `device_hwid` 上报。
     *
     * core 只用它合并「同一台手机清数据/重装后换了密钥」产生的重复配对记录，
     * 不参与任何安全判定（设备身份由 Keystore 里的密钥对决定）。所以刻意
     * **不按配置档隔离**：换配置档不代表换了手机。
     */
    fun getDeviceHwId(ctx: Context): String {
        val raw = ctx.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
        raw.getString("device_hwid", null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, "android_id")
        }.getOrNull()
        // ANDROID_ID 在个别定制 ROM 上会返回空，退回随机值：反正只用于去重
        val seed = androidId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        val hwId = NetUtil.sha256(seed)
        raw.edit().putString("device_hwid", hwId).apply()
        return hwId
    }

    // ==================== 指定 Wi-Fi 才刷新 ====================

    /** 是否只在白名单 Wi-Fi 下采集。默认关闭，保持既有行为 */
    fun getWifiLockEnabled(ctx: Context) = getSp(ctx).getBoolean("wifi_lock_enabled", false)
    fun setWifiLockEnabled(ctx: Context, enabled: Boolean) =
        getSp(ctx).edit().putBoolean("wifi_lock_enabled", enabled).apply()

    /**
     * 白名单 SSID 集合。
     *
     * 用 StringSet 而非逗号拼接：SSID 本身允许包含逗号，拼接会导致无法正确还原。
     * 返回的是副本，直接改动不会影响存储。
     */
    fun getWifiLockSsids(ctx: Context): Set<String> =
        getSp(ctx).getStringSet("wifi_lock_ssids", emptySet())?.toSet() ?: emptySet()

    fun setWifiLockSsids(ctx: Context, ssids: Set<String>) =
        getSp(ctx).edit().putStringSet("wifi_lock_ssids", ssids.filter { it.isNotBlank() }.toSet()).apply()

    fun addWifiLockSsid(ctx: Context, ssid: String) {
        if (ssid.isBlank()) return
        setWifiLockSsids(ctx, getWifiLockSsids(ctx) + ssid)
    }

    fun removeWifiLockSsid(ctx: Context, ssid: String) {
        setWifiLockSsids(ctx, getWifiLockSsids(ctx) - ssid)
    }

    // ==================== 采集准入守卫（省电类）====================
    //
    // 这几项与数据源无关：判断依据全部来自手机侧系统状态（屏幕、省电模式），
    // 换任何数据源都同样适用，因此是全局开关而不是随数据源走的配置。

    /**
     * 息屏时暂停采集。默认关闭，保持既有行为。
     *
     * 只作用于「为渲染而做的采集」，不影响通知检查 —— 见 [WifiGuard.evaluateForNotify]。
     */
    fun getPauseOnScreenOff(ctx: Context) = getSp(ctx).getBoolean("guard_pause_on_screen_off", false)
    fun setPauseOnScreenOff(ctx: Context, enabled: Boolean) =
        getSp(ctx).edit().putBoolean("guard_pause_on_screen_off", enabled).apply()

    /** 系统省电模式开启时暂停采集。默认关闭 */
    fun getPauseOnPowerSave(ctx: Context) = getSp(ctx).getBoolean("guard_pause_on_power_save", false)
    fun setPauseOnPowerSave(ctx: Context, enabled: Boolean) =
        getSp(ctx).edit().putBoolean("guard_pause_on_power_save", enabled).apply()

    // ==================== 免打扰时段 ====================
    //
    // 只静音通知，不停止采集。夜间停采会让 TrafficRecordManager 的「单日结清」累加器
    // 缺采样点，跨天结清位置漂移，日用量直接算错 —— 这是两个功能的硬冲突，
    // 所以本项刻意不进 WifiGuard。
    //
    // 默认值必须落在同一天内（start < end）：设置界面用一条 00:00–24:00 的区间滑条表达，
    // 跨零点区间在这根轴上画不出来。以前默认 23:00–07:00，一打开界面就是跨天形态，
    // 着色区被画到两端，看着像「选的时段没上色」。

    /** 默认免打扰起始 00:00 */
    const val DEFAULT_QUIET_START = 0

    /** 默认免打扰结束 07:00 */
    const val DEFAULT_QUIET_END = 7 * 60

    fun getQuietHoursEnabled(ctx: Context) = getSp(ctx).getBoolean("notify_quiet_enabled", false)
    fun setQuietHoursEnabled(ctx: Context, enabled: Boolean) =
        getSp(ctx).edit().putBoolean("notify_quiet_enabled", enabled).apply()

    /** 免打扰起始，存「当天第几分钟」（0..1439）。默认 00:00 */
    fun getQuietHoursStart(ctx: Context) = getSp(ctx).getInt("notify_quiet_start_minute", DEFAULT_QUIET_START)
    fun setQuietHoursStart(ctx: Context, minute: Int) =
        getSp(ctx).edit().putInt("notify_quiet_start_minute", minute.coerceIn(0, 1439)).apply()

    /** 免打扰结束，存「当天第几分钟」（0..1439）。默认 07:00 */
    fun getQuietHoursEnd(ctx: Context) = getSp(ctx).getInt("notify_quiet_end_minute", DEFAULT_QUIET_END)
    fun setQuietHoursEnd(ctx: Context, minute: Int) =
        getSp(ctx).edit().putInt("notify_quiet_end_minute", minute.coerceIn(0, 1439)).apply()

    // ==================== 套餐额度与账期 ====================
    //
    // 与数据源无关：额度是运营商套餐属性，换数据源不影响。
    // 但**属于设备**——不同随身 WiFi 插不同卡，所以后续引入配置档时这两个键要随档走。

    /** 月度套餐额度（字节）。0 表示未设置，此时不显示进度与预测 */
    fun getTrafficQuotaBytes(ctx: Context): Long = getSp(ctx).getLong("traffic_quota_bytes", 0L)
    fun setTrafficQuotaBytes(ctx: Context, bytes: Long) =
        getSp(ctx).edit().putLong("traffic_quota_bytes", bytes.coerceAtLeast(0L)).apply()

    /**
     * 账期起始日（1..28）。
     *
     * 限制到 28 是因为 29/30/31 在 2 月或小月不存在，允许设置只会制造一堆
     * 「这个月的账期从哪天开始」的边界分支，收益为零。
     */
    fun getTrafficBillingDay(ctx: Context): Int =
        getSp(ctx).getInt("traffic_billing_day", 1).coerceIn(1, 28)

    fun setTrafficBillingDay(ctx: Context, day: Int) =
        getSp(ctx).edit().putInt("traffic_billing_day", day.coerceIn(1, 28)).apply()

    // ==================== 常驻通知显示实时数据 ====================

    /**
     * 前台常驻通知是否显示实时流量/信号。
     *
     * 与「自定义通知文案」（[getCustomNotifTitle] / [getCustomNotifText]）互斥：
     * 后者的用途是把保活通知伪装成别的东西，开启实时数据后伪装自然失效。
     */
    fun getNotifShowLiveData(ctx: Context) = getSp(ctx).getBoolean("notif_show_live_data", false)
    fun setNotifShowLiveData(ctx: Context, enabled: Boolean) =
        getSp(ctx).edit().putBoolean("notif_show_live_data", enabled).apply()

    /**
     * 实时状态显示项（常驻通知与快捷设置磁贴共用）。
     *
     * 空集合表示「全部项」—— 这样新增可选项时老用户自动获得，不需要迁移。
     * 取值键定义在 [StatusSummary.FIELDS]。
     */
    fun getStatusFields(ctx: Context): Set<String> =
        getSp(ctx).getStringSet("status_fields", emptySet())?.toSet() ?: emptySet()

    fun setStatusFields(ctx: Context, keys: Set<String>) =
        getSp(ctx).edit().putStringSet("status_fields", keys).apply()

    // ==================== 小组件三击切换配置档 ====================
    //
    // 全局键：连击行为属于「小组件怎么响应手势」，与具体是哪台设备无关。

    /**
     * 三击小组件是否循环切换设备配置档。
     *
     * SP 键仍是 `widget_double_tap_switch`：手势从双击改成三击只是交互层面的调整，
     * 改键名会让已经打开这个开关的用户静默回到默认关闭状态。
     */
    fun getWidgetTripleTapSwitch(ctx: Context) =
        getSp(ctx).getBoolean("widget_double_tap_switch", false)

    fun setWidgetTripleTapSwitch(ctx: Context, enabled: Boolean) =
        getSp(ctx).edit().putBoolean("widget_double_tap_switch", enabled).apply()

    /**
     * 参与循环的档 id。
     *
     * 空集合表示「全部档参与」—— 默认行为，也是新建档后不用回来改设置的原因。
     * 少于 2 个有效档时循环无意义，由调用方回退成全部档。
     */
    fun getWidgetCycleProfiles(ctx: Context): Set<String> =
        getSp(ctx).getStringSet("widget_cycle_profiles", emptySet())?.toSet() ?: emptySet()

    fun setWidgetCycleProfiles(ctx: Context, ids: Set<String>) =
        getSp(ctx).edit().putStringSet("widget_cycle_profiles", ids).apply()

    /**
     * 累计本次点击是这一串连击里的第几下，返回 1 表示新的一串。
     *
     * 用 SP 存时间戳和计数而不是内存变量：小组件点击走广播，进程随时可能被回收，
     * 内存里的「上次点击时间」根本活不到第二下。
     *
     * 用 `commit()` 而不是 `apply()`：两次点击之间进程可能被杀，异步写有丢的可能，
     * 一次几十字节的同步写在广播里可以接受。
     *
     * 计数只增不「消费」：RemoteViews 没有连击手势，判定完全靠相邻广播的时间差。
     * 连击的第 N 下由调用方顺手撤销第 N-1 下的效果；只有单击那份动作会延后一小段
     * 再执行（见 BaseWifiWidget.onReceive），执行前用 [getWidgetTapCount] 复核一次。
     *
     * @param windowMs 相邻两下之间的最大间隔，超过就重新从 1 计数。
     *                 桌面点击到广播落地本身就有几十到上百毫秒的抖动，窗口给得太紧
     *                 会让正常速度的双击被判成两次单击
     */
    fun bumpWidgetTapCount(ctx: Context, windowMs: Long = 800L): Int {
        val sp = getSp(ctx)
        val now = System.currentTimeMillis()
        val last = sp.getLong("widget_last_click_ts", 0L)
        val count = if (last > 0L && now - last in 0..windowMs) {
            sp.getInt("widget_tap_count", 0) + 1
        } else {
            1
        }
        sp.edit()
            .putLong("widget_last_click_ts", now)
            .putInt("widget_tap_count", count)
            .commit()
        return count
    }

    /** 只读当前连击数，不推进计数。给延后执行的单击动作复核「这一串是不是已经变成连击了」 */
    fun getWidgetTapCount(ctx: Context): Int = getSp(ctx).getInt("widget_tap_count", 0)

    /**
     * 小组件点击广播的私有校验串（每次安装生成一次，之后固定）。
     *
     * AppWidgetProvider 的 receiver 必须 exported（否则系统投递不到 APPWIDGET_UPDATE），
     * 所以任何应用都能显式广播我们的 ACTION_REFRESH：污染连击计数、三击切换设备配置档、
     * 反复唤醒局域网采集。这个串放在 MODE_PRIVATE 的 SP 里，外部应用读不到，
     * 用它当「这条广播是不是我们自己的 PendingIntent 发出来的」的判据。
     */
    fun getWidgetTapToken(ctx: Context): String {
        val sp = getSp(ctx)
        sp.getString("widget_tap_token", null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val token = java.util.UUID.randomUUID().toString()
        sp.edit().putString("widget_tap_token", token).commit()
        return token
    }

    // ==================== 设备名称手动覆盖 ====================

    /**
     * 用户自定的设备名称，空串表示不覆盖。
     *
     * goform 协议没有产品型号字段，只能从固件版本串里猜，而那里装的往往是**基带模块型号**
     * （如 MU300）而不是产品名（如 F50）——同一模块会出现在多个型号的机器里，
     * 从版本号原理上就推不出产品名。所以提供手动覆盖，作为唯一可靠的来源。
     */
    fun getDeviceDisplayName(ctx: Context): String =
        getSp(ctx).getString("device_display_name", "")?.trim() ?: ""

    fun setDeviceDisplayName(ctx: Context, name: String) =
        getSp(ctx).edit().putString("device_display_name", name.trim()).apply()

    // ── 解析工具 ──

    /** 解析地址字符串 → (协议, 主机, 端口, 协议是否显式指定, 端口是否显式指定) */
    private fun parseAddress(raw: String): AddressParts {
        val trimmed = raw.trim()

        // 1) 带协议前缀：http://host:port 或 https://host:port
        PROTOCOL_RE.find(trimmed)?.let { m ->
            val protocol = m.groupValues[1]
            val host = m.groupValues[2]
            val portStr = m.groupValues[3]
            val portExplicit = portStr.isNotEmpty()
            val port = if (portExplicit) portStr.toInt() else (if (protocol == "https") 443 else 80)
            return AddressParts(protocol, host, port, protocolExplicit = true, portExplicit = portExplicit)
        }

        // 2) 无协议：host:port 或 host
        HOST_PORT_RE.find(trimmed)?.let { m ->
            val host = m.groupValues[1]
            val explicitPort = m.groupValues[2].toIntOrNull()
            val portExplicit = explicitPort != null
            val (protocol, defaultPort) = resolveProtocol(host, explicitPort)
            return AddressParts(protocol, host, explicitPort ?: defaultPort, protocolExplicit = false, portExplicit = portExplicit)
        }

        // 解析失败 → 默认地址
        return parseAddress(DEFAULT_DEVICE_ADDRESS)
    }

    /** 根据主机类型决定协议与默认端口（仅用于未探测时的默认值）。公网域名默认 https://，协议探测不通过时自动回退 http://。 */
    private fun resolveProtocol(host: String, explicitPort: Int?): Pair<String, Int> {
        if (isPrivateOrLocalIp(host)) {
            return "http" to (explicitPort ?: 2333)
        }
        return "https" to (explicitPort ?: 443)
    }

    /** 判断是否为私有/本地 IP */
    internal fun isPrivateOrLocalIp(host: String): Boolean {
        val ipPattern = IPV4_RE
        val m = ipPattern.find(host) ?: return false
        val o1 = m.groupValues[1].toIntOrNull() ?: return false
        val o2 = m.groupValues[2].toIntOrNull() ?: return false
        return o1 == 10 ||
                o1 == 127 ||
                (o1 == 172 && o2 in 16..31) ||
                (o1 == 192 && o2 == 168)
    }

    private data class AddressParts(
        val protocol: String,
        val host: String,
        val port: Int,
        val protocolExplicit: Boolean = false,
        val portExplicit: Boolean = false
    )

    // ==================== 主题模式 ====================
    // app_theme: "system" (默认/跟随设备), "light", "dark"
    // widget_theme: "follow_app" (默认/跟随应用), "light", "dark"

    fun getAppTheme(ctx: Context) = getSp(ctx).getString("app_theme", "system") ?: "system"
    fun setAppTheme(ctx: Context, mode: String) = getSp(ctx).edit().putString("app_theme", mode).apply()

    fun getWidgetTheme(ctx: Context) = getSp(ctx).getString("widget_theme", "follow_app") ?: "follow_app"
    fun setWidgetTheme(ctx: Context, mode: String) = getSp(ctx).edit().putString("widget_theme", mode).apply()

    /** 获取小组件是否跟随应用主题 */
    fun getWidgetFollowAppTheme(ctx: Context) = getSp(ctx).getBoolean("widget_follow_app_theme", true)
    fun setWidgetFollowAppTheme(ctx: Context, follow: Boolean) = getSp(ctx).edit().putBoolean("widget_follow_app_theme", follow).apply()

    /** 判断小组件当前是否应使用暗色模式 */
    fun isWidgetDark(ctx: Context): Boolean {
        if (!getWidgetFollowAppTheme(ctx)) {
            // 如果不跟随应用主题，则根据小组件自身的主题设置决定
            return when (getWidgetTheme(ctx)) {
                "light" -> false
                "dark" -> true
                else -> { // 如果设为了 follow_app 但 follow 开关关了，强制走系统识别
                    val nightMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    nightMode == Configuration.UI_MODE_NIGHT_YES
                }
            }
        }
        // 原有逻辑：跟随应用主题
        return when (getWidgetTheme(ctx)) {
            "light" -> false
            "dark" -> true
            else -> { // follow_app
                when (getAppTheme(ctx)) {
                    "light" -> false
                    "dark" -> true
                    else -> { // system
                        val nightMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        nightMode == Configuration.UI_MODE_NIGHT_YES
                    }
                }
            }
        }
    }

    /** 判断应用当前是否应使用暗色模式（用于 Activity 启动时设置） */
    fun getNightMode(ctx: Context): Int {
        return when (getAppTheme(ctx)) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    // ==================== 自定义颜色主题 ====================
    // color_theme = -1 表示使用自定义颜色
    fun getCustomAccentLight(ctx: Context) = getSp(ctx).getInt("custom_accent_light", 0xFF222222.toInt())
    fun setCustomAccentLight(ctx: Context, color: Int) = getSp(ctx).edit().putInt("custom_accent_light", color).apply()
    fun getCustomAccentDark(ctx: Context) = getSp(ctx).getInt("custom_accent_dark", 0xFFCCCCCC.toInt())
    fun setCustomAccentDark(ctx: Context, color: Int) = getSp(ctx).edit().putInt("custom_accent_dark", color).apply()

    /** 获取当前颜色主题索引（-1 为自定义） */
    fun getColorThemeIndex(ctx: Context) = getSp(ctx).getInt("color_theme", 0)
    fun setColorThemeIndex(ctx: Context, index: Int) = getSp(ctx).edit().putInt("color_theme", index).apply()

    /** 获取小组件独立颜色主题索引 */
    fun getWidgetColorThemeIndex(ctx: Context) = getSp(ctx).getInt("widget_color_theme", 0)
    fun setWidgetColorThemeIndex(ctx: Context, index: Int) = getSp(ctx).edit().putInt("widget_color_theme", index).apply()

    fun getWidgetCustomAccentLight(ctx: Context) = getSp(ctx).getInt("widget_custom_accent_light", 0xFF222222.toInt())
    fun setWidgetCustomAccentLight(ctx: Context, color: Int) = getSp(ctx).edit().putInt("widget_custom_accent_light", color).apply()
    fun getWidgetCustomAccentDark(ctx: Context) = getSp(ctx).getInt("widget_custom_accent_dark", 0xFFCCCCCC.toInt())
    fun setWidgetCustomAccentDark(ctx: Context, color: Int) = getSp(ctx).edit().putInt("widget_custom_accent_dark", color).apply()

    // ==================== 崩溃信息 ====================
    /** 上次崩溃时间戳（0 = 无崩溃记录） */
    fun getLastCrashTime(ctx: Context) = getSp(ctx).getLong("last_crash_time", 0L)
    fun setLastCrashTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("last_crash_time", time).apply()

    /** 保存崩溃信息摘要（时间戳 + 异常类名 + 脱敏后的 message） */
    fun setLastCrashInfo(ctx: Context, crashInfo: String) {
        val summary = crashInfo.lines().firstOrNull { it.contains("异常堆栈") || it.contains("Exception") }
            ?: crashInfo.take(200)
        getSp(ctx).edit()
            .putLong("last_crash_time", System.currentTimeMillis())
            .putString("last_crash_summary", summary.take(500))
            .apply()
    }

    fun setLastCrashSummary(ctx: Context, summary: String) = getSp(ctx).edit().putString("last_crash_summary", summary).apply()

    /** 获取上次崩溃摘要 */
    fun getLastCrashSummary(ctx: Context) = getSp(ctx).getString("last_crash_summary", "") ?: ""
    /** 清除崩溃标志（用户已查看或忽略） */
    fun clearCrashInfo(ctx: Context) = getSp(ctx).edit()
        .putLong("last_crash_time", 0L)
        .remove("last_crash_summary")
        .apply()

    // ==================== 调试模式 ====================
    fun getDebugEnabled(ctx: Context) = getSp(ctx).getBoolean("debug_enabled", false)
    fun setDebugEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("debug_enabled", enabled).apply()

    // ==================== 更新镜像源与自动检查 ====================
    // 0 = GitHub 官方，1 = 国内镜像 (gh-proxy)
    fun getUpdateMirror(ctx: Context) = getSp(ctx).getInt("update_mirror", 0)
    fun setUpdateMirror(ctx: Context, mirror: Int) = getSp(ctx).edit().putInt("update_mirror", mirror).apply()

    /** 获取是否开启自动检测更新 */
    fun getAutoCheckUpdate(ctx: Context) = getSp(ctx).getBoolean("auto_check_update", true)

    /** 设置是否开启自动检测更新 */
    fun setAutoCheckUpdate(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("auto_check_update", enabled).apply()

    /** 获取上次自动检查更新的时间戳 */
    fun getLastUpdateCheckTime(ctx: Context) = getSp(ctx).getLong("last_update_check_time", 0L)

    /** 保存本次自动检查更新的时间戳 */
    fun setLastUpdateCheckTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("last_update_check_time", time).apply()

    // ==================== 自定义背景图片 ====================
    /** 获取自定义背景图片 URI（空字符串=未设置） */
    fun getBgImageUri(ctx: Context) = getSp(ctx).getString("bg_image_uri", "") ?: ""

    /** 保存自定义背景图片 URI */
    fun setBgImageUri(ctx: Context, uri: String) = getSp(ctx).edit().putString("bg_image_uri", uri).apply()

    /** 清除自定义背景图片 */
    fun clearBgImageUri(ctx: Context) = getSp(ctx).edit().remove("bg_image_uri").apply()

    // ==================== 小组件自定义背景 ====================
    /** 获取小组件自定义背景图片 URI / 文件路径（空字符串=未设置） */
    fun getWidgetBgImageUri(ctx: Context) = getSp(ctx).getString("widget_bg_image_uri", "") ?: ""

    /** 保存小组件自定义背景图片 URI / 文件路径 */
    fun setWidgetBgImageUri(ctx: Context, uri: String) = getSp(ctx).edit().putString("widget_bg_image_uri", uri).apply()

    /** 清除小组件自定义背景图片 */
    fun clearWidgetBgImageUri(ctx: Context) = getSp(ctx).edit().remove("widget_bg_image_uri").apply()

    /**
     * 将 content:// URI 拷贝到应用内部存储，返回绝对文件路径。
     * 解决 content:// URI 在 Widget 进程跨进程访问权限问题。
     *
     * 保留**未裁切**的整图：小组件背景按形态各存一个取景矩形，重新取景时要能缩小、
     * 平移到画面别处，所以不能只留裁好的那一块。为此加一道长边上限——手机照片
     * 动辄 4000px / 8MB，而渲染目标长边只有 640px，2560 已经足够支撑 5 倍放大取景。
     */
    fun saveWidgetBgImageToInternal(ctx: Context, sourceUri: android.net.Uri): String? {
        return try {
            val dir = java.io.File(ctx.filesDir, "widget_bg")
            if (!dir.exists()) dir.mkdirs()
            // 使用时间戳文件名，避免历史记录覆盖同一物理文件
            val file = java.io.File(dir, "custom_bg_${System.currentTimeMillis()}.jpg")

            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(sourceUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)

            if (longEdge in 1..MAX_BG_LONG_EDGE) {
                // 尺寸已达标：直接字节拷贝，避免重编码掉画质
                ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                    java.io.FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: run {
                    if (file.exists()) file.delete()
                    return null
                }
                return file.absolutePath
            }

            // 超限或读不到尺寸：解码后等比压缩再存
            val sample = if (longEdge > MAX_BG_LONG_EDGE) {
                Integer.highestOneBit(maxOf(longEdge / MAX_BG_LONG_EDGE, 1))
            } else 1
            val decoded = ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(
                    input, null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } ?: run {
                if (file.exists()) file.delete()
                return null
            }

            val scaled = run {
                val edge = maxOf(decoded.width, decoded.height)
                if (edge > MAX_BG_LONG_EDGE) {
                    val ratio = MAX_BG_LONG_EDGE.toFloat() / edge
                    val s = android.graphics.Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * ratio).toInt().coerceAtLeast(1),
                        (decoded.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                    if (s !== decoded) decoded.recycle()
                    s
                } else decoded
            }

            java.io.FileOutputStream(file).use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
            scaled.recycle()
            file.absolutePath
        } catch (e: Exception) {
            DebugLogger.w("SPUtil", "saveWidgetBgImageToInternal failed: ${e.message}")
            null
        }
    }

    /** 背景原图长边上限（px）。渲染目标长边 640，5 倍放大取景仍有余量 */
    private const val MAX_BG_LONG_EDGE = 2560

    // ==================== 设备平台缓存（AT+CGMI 探测结果） ====================
    // "spreadtrum" | "quectel" | "" (未探测)
    fun getCachedPlatform(ctx: Context) = getSp(ctx).getString("device_platform", "") ?: ""
    fun setCachedPlatform(ctx: Context, platform: String) = getSp(ctx).edit().putString("device_platform", platform).apply()

    /** 获取小组件背景透明度（0-100，默认 100 = 完全不透明） */
    fun getWidgetBgOpacity(ctx: Context) = getSp(ctx).getInt("widget_bg_opacity", 100)

    /** 保存小组件背景透明度 */
    fun setWidgetBgOpacity(ctx: Context, opacity: Int) = getSp(ctx).edit().putInt("widget_bg_opacity", opacity).apply()

    // ==================== 小组件圆角裁剪兜底开关 ====================
    /** 是否启用小组件圆角处理（默认关闭）。
     *  开启后 Bitmap 裁剪 + clipToOutline + 圆角 drawable 全链路圆角，
     *  适配原生/国际版桌面。国产桌面通常已自带圆角，无需开启。 */
    fun getWidgetClipToOutline(ctx: Context) = getSp(ctx).getBoolean("widget_clip_to_outline", false)
    fun setWidgetClipToOutline(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("widget_clip_to_outline", enabled).apply()

    // ==================== Android 12+ 动态配色（Material You）====================
    /** 是否启用小组件动态配色（Material You，仅 Android 12+ 生效）。
     *  开启后小组件背景/文字颜色自动跟随系统壁纸色调，无需手动选择配色。
     *  默认 false：用户需在实验功能页手动开启。 */
    fun getWidgetDynamicColor(ctx: Context) = getSp(ctx).getBoolean("widget_dynamic_color", false)
    fun setWidgetDynamicColor(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("widget_dynamic_color", enabled).apply()

    /** 动态配色对比度级别：0=柔和, 1=标准(默认), 2=强烈 */
    fun getWidgetDynamicContrast(ctx: Context) = getSp(ctx).getInt("widget_dynamic_contrast", 1)
    fun setWidgetDynamicContrast(ctx: Context, level: Int) = getSp(ctx).edit().putInt("widget_dynamic_contrast", level).apply()

    /** 动态配色高级设置开关 */
    fun getWidgetDynamicAdvanced(ctx: Context) = getSp(ctx).getBoolean("widget_dynamic_advanced", false)
    fun setWidgetDynamicAdvanced(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("widget_dynamic_advanced", enabled).apply()

    /** 动态配色色源选择：0=Primary, 1=Secondary, 2=Tertiary, 3=Neutral, 4=NeutralVariant */
    fun getWidgetDynamicColorSource(ctx: Context) = getSp(ctx).getInt("widget_dynamic_color_source", 0)
    fun setWidgetDynamicColorSource(ctx: Context, source: Int) = getSp(ctx).edit().putInt("widget_dynamic_color_source", source).apply()
    /** 高级：浅色背景亮度 (85-99, 默认 97) */
    fun getDynAdvLightBg(ctx: Context) = getSp(ctx).getInt("dyn_adv_light_bg", 97)
    fun setDynAdvLightBg(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_light_bg", v).apply()
    /** 高级：浅色文字亮度 (5-40, 默认 12) */
    fun getDynAdvLightTxt(ctx: Context) = getSp(ctx).getInt("dyn_adv_light_txt", 12)
    fun setDynAdvLightTxt(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_light_txt", v).apply()
    /** 高级：深色背景亮度 (3-20, 默认 8) */
    fun getDynAdvDarkBg(ctx: Context) = getSp(ctx).getInt("dyn_adv_dark_bg", 8)
    fun setDynAdvDarkBg(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_dark_bg", v).apply()
    /** 高级：深色文字亮度 (75-98, 默认 90) */
    fun getDynAdvDarkTxt(ctx: Context) = getSp(ctx).getInt("dyn_adv_dark_txt", 90)
    fun setDynAdvDarkTxt(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_dark_txt", v).apply()
    /** 高级：饱和度增强 (50-150, 默认 100 = 无增强) */
    fun getDynAdvSatBoost(ctx: Context) = getSp(ctx).getInt("dyn_adv_sat_boost", 100)
    fun setDynAdvSatBoost(ctx: Context, v: Int) = getSp(ctx).edit().putInt("dyn_adv_sat_boost", v).apply()

    // ==================== 小组件兼容性设置 ====================
    // 「隐藏小组件名称」不在这里存：它的真实开关是影子 receiver 的 enabled 状态，
    // 由 WidgetLabelToggle 直接读写 PackageManager。多存一份 SP 只会两边打架。


    // ==================== 小组件背景开关 & 历史 ====================
    /** 小组件自定义背景是否启用（关闭时使用默认纯色背景） */
    fun getWidgetBgImageEnabled(ctx: Context): Boolean {
        val sp = getSp(ctx)
        if (!sp.contains("widget_bg_image_enabled")) {
            // 迁移：已有背景图的用户默认启用
            val hasUri = getWidgetBgImageUri(ctx).isNotBlank()
            if (hasUri) {
                setWidgetBgImageEnabled(ctx, true)
                return true
            }
            return false
        }
        return sp.getBoolean("widget_bg_image_enabled", false)
    }
    fun setWidgetBgImageEnabled(ctx: Context, enabled: Boolean) =
        getSp(ctx).edit().putBoolean("widget_bg_image_enabled", enabled).apply()

    /** 获取实际应用的小组件背景 URI（考虑启用状态，禁用时返回空） */
    fun getAppliedWidgetBgImageUri(ctx: Context): String {
        return if (getWidgetBgImageEnabled(ctx)) getWidgetBgImageUri(ctx) else ""
    }

    /** 获取小组件背景历史列表（最多 3 条 URI） */
    /**
     * 背景历史的存储键 —— 按作用域独立。
     *
     * 「最近使用」是给当前正在设置的那个组件挑图用的，四种形态混在一条列表里，
     * 点进去大半是给别的比例裁过的图，参考价值很低。
     *
     * 全局默认沿用老键，老用户的列表不会丢；实例作用域各自一条，
     * 且刻意**不回退**到全局 —— 用户要的就是彼此独立。
     */
    private fun bgHistoryKey(kind: String?, id: Int?): String = when {
        kind == null -> "widget_bg_image_history"
        id != null -> "widget.$kind.$id.bg_history"
        else -> "widget.$kind.bg_history"
    }

    fun getWidgetBgHistory(ctx: Context, kind: String? = null, id: Int? = null): List<String> {
        try {
            val json = getSp(ctx).getString(bgHistoryKey(kind, id), "[]") ?: "[]"
            val arr = org.json.JSONArray(json)
            return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /** 保存小组件背景历史列表 */
    fun setWidgetBgHistory(ctx: Context, history: List<String>, kind: String? = null, id: Int? = null) {
        val arr = org.json.JSONArray(history.take(MAX_BG_HISTORY))
        getSp(ctx).edit().putString(bgHistoryKey(kind, id), arr.toString()).apply()
    }

    /**
     * 这张背景图文件是否仍被别人引用。
     *
     * 背景图按作用域独立之后，同一个物理文件可能被多处同时引用：全局键
     * `widget_bg_image_uri`、作用域键 `widget.<kind>[.<id>].bg_image_uri`
     * （打开「单独设置外观」时快照会把全局路径原样复制过去），以及背景历史列表。
     * 删文件前必须先问一句，否则表现是「改了 A 组件的背景，B 组件的背景变成纯色」。
     *
     * @param excludeKeys 本次正要摘掉的引用键，判断时不算数
     */
    fun isWidgetBgFileReferenced(
        ctx: Context,
        path: String,
        excludeKeys: Set<String> = emptySet(),
        excludeHistory: Boolean = false
    ): Boolean {
        if (path.isBlank() || path.startsWith("content://")) return false
        val sp = getSp(ctx)
        val referencedBySp = sp.all.any { (key, value) ->
            key !in excludeKeys && key.endsWith("bg_image_uri") && value == path
        }
        if (referencedBySp) return true
        if (excludeHistory) return false
        // 历史也是按作用域独立的，要扫全部历史键而不只是全局那一条；
        // 用 contains 而不是精确解析 JSON：误判只会导致「多留一个文件」，方向是安全的
        return sp.all.any { (key, value) ->
            (key == "widget_bg_image_history" || key.endsWith(".bg_history")) &&
                (value as? String)?.contains(path) == true
        }
    }

    /**
     * 清扫 `widget_bg` 目录里已无人引用的文件。
     *
     * 组件被删时它的作用域键和独立历史会被整段清掉，那些只被它引用的文件就成了孤儿；
     * 逐个精确追踪很容易漏，直接按「当前还有没有键引用」全量扫一遍最稳。
     */
    fun sweepUnusedWidgetBgFiles(ctx: Context) {
        try {
            val dir = java.io.File(ctx.filesDir, "widget_bg")
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && !isWidgetBgFileReferenced(ctx, f.absolutePath)) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            DebugLogger.w("SPUtil", "sweepUnusedWidgetBgFiles failed: ${e.message}")
        }
    }

    /** 删除背景文件，但仅在没有任何作用域/历史还引用它时 */
    private fun deleteWidgetBgFileIfUnused(
        ctx: Context,
        path: String,
        excludeKeys: Set<String> = emptySet(),
        excludeHistory: Boolean = false
    ) {
        if (path.isBlank() || path.startsWith("content://")) return
        if (isWidgetBgFileReferenced(ctx, path, excludeKeys, excludeHistory)) return
        try { java.io.File(path).delete() } catch (_: Exception) {}
    }

    /** 添加一条 URI 到背景历史（去重后插入首位，超出 3 条截断并清理无人引用的旧文件） */
    fun addWidgetBgHistory(ctx: Context, uri: String, kind: String? = null, id: Int? = null) {
        val current = getWidgetBgHistory(ctx, kind, id).toMutableList()
        current.remove(uri)          // 去重
        current.add(0, uri)          // 插入首位
        val trimmed = current.take(MAX_BG_HISTORY)
        val evicted = current.drop(MAX_BG_HISTORY)
        // 先落库新历史，再清理：这样 excludeHistory 不用手工模拟「淘汰后的历史」
        setWidgetBgHistory(ctx, trimmed, kind, id)
        // 被历史淘汰 ≠ 没人用：别的作用域可能正把它当背景，或还在别人的历史里
        evicted.forEach { deleteWidgetBgFileIfUnused(ctx, it) }
    }

    /** 清除小组件背景（清除全局 SP，文件仅在无人引用时删除，历史保留不变） */
    fun clearWidgetBgImage(ctx: Context) {
        val filePath = getWidgetBgImageUri(ctx)
        getSp(ctx).edit()
            .remove("widget_bg_image_uri")
            .putBoolean("widget_bg_image_enabled", false)
            .apply()
        // 正在摘掉的就是全局这条引用，所以排除它自己；作用域键与历史仍算引用
        deleteWidgetBgFileIfUnused(ctx, filePath, excludeKeys = setOf("widget_bg_image_uri"))
    }


    private const val MAX_BG_HISTORY = 3

    // ==================== 响应缓存策略 ====================
    // 低频变化的 API 响应缓存到 SP，减少重复请求

    /** 响应缓存默认 TTL：1 小时（毫秒） */
    const val CACHE_TTL_HOUR_MS = 3_600_000L

    // ── version_info 缓存 ──
    fun getCachedVersionInfoJson(ctx: Context) = getSp(ctx).getString("cache_version_info_json", "") ?: ""
    fun setCachedVersionInfoJson(ctx: Context, json: String) = getSp(ctx).edit().putString("cache_version_info_json", json).apply()
    fun getVersionInfoCacheTime(ctx: Context) = getSp(ctx).getLong("cache_version_info_time", 0L)
    fun setVersionInfoCacheTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("cache_version_info_time", time).apply()
    fun isVersionInfoCacheFresh(ctx: Context) =
        System.currentTimeMillis() - getVersionInfoCacheTime(ctx) < CACHE_TTL_HOUR_MS

    // ── need_token 缓存 ──
    fun getCachedNeedTokenJson(ctx: Context) = getSp(ctx).getString("cache_need_token_json", "") ?: ""
    fun setCachedNeedTokenJson(ctx: Context, json: String) = getSp(ctx).edit().putString("cache_need_token_json", json).apply()
    fun getNeedTokenCacheTime(ctx: Context) = getSp(ctx).getLong("cache_need_token_time", 0L)
    fun setNeedTokenCacheTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("cache_need_token_time", time).apply()
    fun isNeedTokenCacheFresh(ctx: Context) =
        System.currentTimeMillis() - getNeedTokenCacheTime(ctx) < CACHE_TTL_HOUR_MS

    // ── AT 静态字段缓存（CGMM 模块型号 / CGMR 固件版本 / CGSN IMEI）──
    fun getCachedModuleModel(ctx: Context) = getSp(ctx).getString("cache_at_cgmm", "") ?: ""
    fun setCachedModuleModel(ctx: Context, value: String) = getSp(ctx).edit().putString("cache_at_cgmm", value).apply()
    fun getCachedFirmwareDetail(ctx: Context) = getSp(ctx).getString("cache_at_cgmr", "") ?: ""
    fun setCachedFirmwareDetail(ctx: Context, value: String) = getSp(ctx).edit().putString("cache_at_cgmr", value).apply()
    fun getCachedImeiFromAt(ctx: Context) = getSp(ctx).getString("cache_at_cgsn", "") ?: ""
    fun setCachedImeiFromAt(ctx: Context, value: String) = getSp(ctx).edit().putString("cache_at_cgsn", value).apply()
    fun getAtStaticCacheTime(ctx: Context) = getSp(ctx).getLong("cache_at_static_time", 0L)
    fun setAtStaticCacheTime(ctx: Context, time: Long) = getSp(ctx).edit().putLong("cache_at_static_time", time).apply()
    fun isAtStaticCacheFresh(ctx: Context) =
        System.currentTimeMillis() - getAtStaticCacheTime(ctx) < CACHE_TTL_HOUR_MS

    /** 清除所有响应缓存（设备地址变更、Token 变更时调用，强制下轮全量刷新） */
    fun invalidateResponseCaches(ctx: Context) {
        getSp(ctx).edit()
            .putLong("cache_version_info_time", 0L)
            .putLong("cache_need_token_time", 0L)
            .putLong("cache_at_static_time", 0L)
            .putString("device_platform", "")  // 平台探测也一并清除
            .apply()
        DebugLogger.i("SPUtil", "invalidateResponseCaches: all response caches cleared")
    }

    // ==================== 通知提醒设置 ====================

    /** 通知总开关 */
    fun getNotificationEnabled(ctx: Context) = getSp(ctx).getBoolean("notification_enabled", false)
    fun setNotificationEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notification_enabled", enabled).apply()

    /** 各类型提醒开关 */
    fun getNotifyDailyFlow(ctx: Context) = getSp(ctx).getBoolean("notify_daily_flow", false)
    fun setNotifyDailyFlow(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_daily_flow", enabled).apply()

    fun getNotifyMonthlyFlow(ctx: Context) = getSp(ctx).getBoolean("notify_monthly_flow", false)
    fun setNotifyMonthlyFlow(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_monthly_flow", enabled).apply()

    fun getNotifyTemp(ctx: Context) = getSp(ctx).getBoolean("notify_temp", false)
    fun setNotifyTemp(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_temp", enabled).apply()

    fun getNotifyCpu(ctx: Context) = getSp(ctx).getBoolean("notify_cpu", false)
    fun setNotifyCpu(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_cpu", enabled).apply()

    fun getNotifyDeviceOnline(ctx: Context) = getSp(ctx).getBoolean("notify_device_online", false)
    fun setNotifyDeviceOnline(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_device_online", enabled).apply()

    fun getNotifyBattery(ctx: Context) = getSp(ctx).getBoolean("notify_battery", false)
    fun setNotifyBattery(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_battery", enabled).apply()

    fun getNotifyMemory(ctx: Context) = getSp(ctx).getBoolean("notify_memory", false)
    fun setNotifyMemory(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("notify_memory", enabled).apply()

    /** 阈值设置 */
    // 今日流量阈值（字节，默认 1GB）
    fun getNotifyDailyFlowThreshold(ctx: Context) = getSp(ctx).getLong("notify_daily_flow_threshold", 1_073_741_824L)
    fun setNotifyDailyFlowThreshold(ctx: Context, bytes: Long) = getSp(ctx).edit().putLong("notify_daily_flow_threshold", bytes).apply()
    // 本月流量阈值（字节，默认 10GB）
    fun getNotifyMonthlyFlowThreshold(ctx: Context) = getSp(ctx).getLong("notify_monthly_flow_threshold", 10_737_418_240L)
    fun setNotifyMonthlyFlowThreshold(ctx: Context, bytes: Long) = getSp(ctx).edit().putLong("notify_monthly_flow_threshold", bytes).apply()
    // 温度阈值（℃，默认 70）
    fun getNotifyTempThreshold(ctx: Context) = getSp(ctx).getInt("notify_temp_threshold", 70)
    fun setNotifyTempThreshold(ctx: Context, temp: Int) = getSp(ctx).edit().putInt("notify_temp_threshold", temp).apply()
    // CPU 阈值（%，默认 80）
    fun getNotifyCpuThreshold(ctx: Context) = getSp(ctx).getInt("notify_cpu_threshold", 80)
    fun setNotifyCpuThreshold(ctx: Context, cpu: Int) = getSp(ctx).edit().putInt("notify_cpu_threshold", cpu).apply()
    // 电量阈值（%，默认 20）
    fun getNotifyBatteryThreshold(ctx: Context) = getSp(ctx).getInt("notify_battery_threshold", 20)
    fun setNotifyBatteryThreshold(ctx: Context, battery: Int) = getSp(ctx).edit().putInt("notify_battery_threshold", battery).apply()
    // 内存阈值（%，默认 90）
    fun getNotifyMemoryThreshold(ctx: Context) = getSp(ctx).getInt("notify_memory_threshold", 90)
    fun setNotifyMemoryThreshold(ctx: Context, mem: Int) = getSp(ctx).edit().putInt("notify_memory_threshold", mem).apply()

    /** 防抖时间戳 */
    fun getNotifyLastTime(ctx: Context, key: String) = getSp(ctx).getLong(key, 0L)
    fun setNotifyLastTime(ctx: Context, key: String, time: Long) = getSp(ctx).edit().putLong(key, time).apply()

    /** 设备在线状态记录（用于上下线检测） */
    fun getNotifyPrevOnline(ctx: Context) = getSp(ctx).getBoolean("notify_prev_online", true)
    fun setNotifyPrevOnline(ctx: Context, online: Boolean) = getSp(ctx).edit().putBoolean("notify_prev_online", online).apply()

    // ══════════════════════════════════════════════
    // 后台通知监控间隔
    // ══════════════════════════════════════════════

    /** 后台监控检查间隔（秒），默认 60 秒 */
    fun getMonitorIntervalSec(ctx: Context): Int = getSp(ctx).getInt("monitor_interval_sec", 60)
    fun setMonitorIntervalSec(ctx: Context, seconds: Int) = getSp(ctx).edit().putInt("monitor_interval_sec", seconds).apply()

    // ══════════════════════════════════════════════
    // 后台保活服务
    // ══════════════════════════════════════════════

    /** 后台保活前台服务开关 */
    fun getBackgroundServiceEnabled(ctx: Context): Boolean = getSp(ctx).getBoolean("background_service_enabled", false)
    fun setBackgroundServiceEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("background_service_enabled", enabled).apply()

    // ══════════════════════════════════════════════
    // 前台服务通知自定义
    // ══════════════════════════════════════════════

    /** 前台保活服务通知标题（空字符串 = 使用默认值） */
    fun getCustomNotifTitle(ctx: Context): String = getSp(ctx).getString("custom_notif_title", "").orEmpty()
    fun setCustomNotifTitle(ctx: Context, title: String) = getSp(ctx).edit().putString("custom_notif_title", title).apply()

    /** 前台保活服务通知内容（空字符串 = 使用默认值） */
    fun getCustomNotifText(ctx: Context): String = getSp(ctx).getString("custom_notif_text", "").orEmpty()
    fun setCustomNotifText(ctx: Context, text: String) = getSp(ctx).edit().putString("custom_notif_text", text).apply()

    // ══════════════════════════════════════════════
    // 保活增强功能
    // ══════════════════════════════════════════════

    /** 从最近任务中隐藏本应用 */
    fun getHideFromRecents(ctx: Context): Boolean = getSp(ctx).getBoolean("hide_from_recents", false)
    fun setHideFromRecents(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("hide_from_recents", enabled).apply()

    /** WorkManager 周期性保活任务开关 */
    fun getPeriodicWorkerEnabled(ctx: Context): Boolean = getSp(ctx).getBoolean("periodic_worker_enabled", false)
    fun setPeriodicWorkerEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("periodic_worker_enabled", enabled).apply()

    /** WorkManager 周期性保活任务间隔（分钟），默认 15，最小 15（WorkManager 限制） */
    fun getPeriodicWorkerIntervalMin(ctx: Context): Int = getSp(ctx).getInt("periodic_worker_interval_min", 15)
    fun setPeriodicWorkerIntervalMin(ctx: Context, minutes: Int) = getSp(ctx).edit().putInt("periodic_worker_interval_min", minutes.coerceAtLeast(15)).apply()

    /** 无障碍保活服务开关（记录用户意图，实际状态由系统控制） */
    fun getAccessibilityKeepAlive(ctx: Context): Boolean = getSp(ctx).getBoolean("accessibility_keep_alive", false)
    fun setAccessibilityKeepAlive(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("accessibility_keep_alive", enabled).apply()

    /** 进程死亡自动恢复开关：当进程被杀死后通过 AlarmReceiver 自动恢复服务和监控 */
    fun getAutoRecoverEnabled(ctx: Context): Boolean = getSp(ctx).getBoolean("auto_recover_enabled", false)
    fun setAutoRecoverEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("auto_recover_enabled", enabled).apply()

    // ══════════════════════════════════════════════
    // 流量每小时记录设置
    // ══════════════════════════════════════════════

    /** 是否开启每小时流量记录 */
    fun getTrafficHourlyRecordEnabled(ctx: Context): Boolean = getSp(ctx).getBoolean("traffic_hourly_record", false)
    fun setTrafficHourlyRecordEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("traffic_hourly_record", enabled).apply()

    // ══════════════════════════════════════════════
    // 月流量重置日（1~28，默认 1 表示每月 1 日重置）
    // ══════════════════════════════════════════════

    /** 获取月流量重置日（1~28） */
    fun getTrafficMonthlyResetDay(ctx: Context): Int = getSp(ctx).getInt("traffic_monthly_reset_day", 1)
    /** 设置月流量重置日（1~28） */
    fun setTrafficMonthlyResetDay(ctx: Context, day: Int) = getSp(ctx).edit().putInt("traffic_monthly_reset_day", day.coerceIn(1, 28)).apply()

    // ══════════════════════════════════════════════
    // 流量记录总开关
    // ══════════════════════════════════════════════

    /** 是否开启流量记录功能 */
    fun getTrafficRecordEnabled(ctx: Context): Boolean = getSp(ctx).getBoolean("traffic_record_enabled", false)
    /** 设置流量记录功能开关 */
    fun setTrafficRecordEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("traffic_record_enabled", enabled).apply()

    /**
     * 由月累计推导出的今日用量（格式化串，如 `"1.23 GB"`）。
     *
     * 只有月累计、没有日流量字段的数据源（goform）经 `TrafficRecordManager` 推导后写在这里，
     * 供 `NotificationHelper.checkDailyFlow` 在数据源直给值是 `"--"` 时回退取用。
     * 数据源本身支持日流量时这个键不会被写，也不会被用到。
     */
    fun getDerivedDailyFlow(ctx: Context): String = getSp(ctx).getString("derived_daily_flow", "") ?: ""
    fun setDerivedDailyFlow(ctx: Context, flow: String) = getSp(ctx).edit().putString("derived_daily_flow", flow).apply()

    // ══════════════════════════════════════════════
    // 流量记录快捷入口
    // ══════════════════════════════════════════════

    /** 是否开启流量快捷入口（主界面点击流量信息跳转） */
    fun getTrafficQuickEntryEnabled(ctx: Context): Boolean = getSp(ctx).getBoolean("traffic_quick_entry", false)
    /** 设置流量快捷入口开关 */
    fun setTrafficQuickEntryEnabled(ctx: Context, enabled: Boolean) = getSp(ctx).edit().putBoolean("traffic_quick_entry", enabled).apply()

    // ══════════════════════════════════════════════
    // 流量历史分页设置
    // ══════════════════════════════════════════════

    /** 获取流量记录每页显示条数（默认 30） */
    fun getTrafficPageSize(ctx: Context): Int = getSp(ctx).getInt("traffic_page_size", 30)
    /** 设置流量记录每页显示条数 */
    fun setTrafficPageSize(ctx: Context, size: Int) = getSp(ctx).edit().putInt("traffic_page_size", size).apply()

}
