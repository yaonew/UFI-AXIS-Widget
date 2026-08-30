package com.ufi_axis_widget.util.source

import android.content.Context
import com.ufi_axis_widget.util.AtSignalInfo
import com.ufi_axis_widget.util.DataSourceType
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.DeviceCapabilities
import com.ufi_axis_widget.util.NotificationBaseInfo
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.WifiEntity
import com.ufi_axis_widget.util.formatBattery
import com.ufi_axis_widget.util.formatFlow
import com.ufi_axis_widget.util.formatSignal
import com.ufi_axis_widget.util.NetUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 设备原生 goform 接口数据源（ZTE 系随身 WiFi 私有协议）。
 *
 * 不经过 UFI-TOOLS，直接与设备 Web 后台对话。认证与协议细节参照 UFI-AXIS
 * 项目 `core/goform/GoformClient.kt` 已验证可用的实现：
 *
 * 认证模型（全程 SHA-256，**没有 MD5、没有 base64**）：
 * 1. `GET /goform/goform_get_cmd_process?cmd=LD&isTest=false` 取一次性挑战值 `LD`；
 * 2. `passHash = UPPER(hex(sha256(明文口令)))`；
 * 3. `encPwd   = UPPER(hex(sha256(passHash + LD)))`；
 * 4. `POST /goform/goform_set_cmd_process`
 *    body `isTest=false&goformId=LOGIN_MULTI_USER&user=admin&password=$encPwd&IP=localhost`，
 *    失败则回退 `goformId=LOGIN`（不带 IP 参数）；
 * 5. 从响应 `Set-Cookie` 取第一个 `;` 之前的片段，后续所有请求原样回发。
 *
 * 读操作同样依赖登录态：裸读会被设备以 `"none secure connection"` /
 * `"not logged in"` 拒绝。
 *
 * 本实现只做读，不实现写（AD 挑战仅写操作需要，小组件场景用不到）。
 *
 * 能力边界见 [DeviceCapabilities.GOFORM]：goform 协议里没有温度、CPU、内存、
 * 存储、电池电流电压，也没有当日流量，这些字段一律留空由 UI 显示「暂无数据」。
 */
object GoformDataSource : DeviceDataSource {

    private const val TAG = "GoformDataSource"

    override val type = DataSourceType.GOFORM
    override val capabilities = DeviceCapabilities.GOFORM

    @Volatile
    override var lastError: String = ""
        private set

    @Volatile
    override var lastRawResponse: String = ""
        private set

    /** 登录态与 session 独占锁：避免多个后台任务并发登录把 session 顶掉 */
    private val loginMutex = Mutex()

    @Volatile
    private var sessionCookie: String? = null

    @Volatile
    private var loggedIn = false

    /** 登录态有效期，过期后重新校验（设备侧 session 通常几分钟内失效） */
    private const val SESSION_TTL_MS = 3 * 60 * 1000L

    @Volatile
    private var lastLoginAt = 0L

    /**
     * session 版本号，每次登录成功递增。
     *
     * 用于并发场景下判断「我看到的这个 session 是不是已经被别人换掉了」：
     * Worker / 通知轮询 / 保活任务 / UI 测试连接可能同时读取，如果每个失败方都
     * 无条件清 session 重登，就会互相踢掉刚建立的会话（设备侧通常只允许单会话），
     * 形成登录风暴。带版本号比对后，只有持有当前版本的调用方才有资格触发重登。
     */
    @Volatile
    private var sessionVersion = 0

    /** 连续登录失败退避，避免设备被反复敲门 */
    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var lastFailureAt = 0L

    // 共用 [deviceHttpClient]：局域网直连的超时配置两个数据源完全一致，
    // 详见 DeviceHttp.kt 里对「为什么不挂 CookieJar」的说明
    private val client: OkHttpClient get() = deviceHttpClient

    // ══════════════════════════════════════════════
    // 公共接口实现
    // ══════════════════════════════════════════════

    /**
     * goform 是明文 HTTP 后台，不存在 https 变体，所以这里不探测协议，
     * 而是把这次调用当成一次**真实连通性 + 口令校验**：
     * 「测试连接」按钮走的就是这个方法，必须真的能反映成败。
     */
    override suspend fun probeProtocol(context: Context): String? =
        withContext(Dispatchers.IO) {
            // 强制丢弃旧会话，确保这次是真的重新握手一遍
            invalidateSession()
            if (ensureLogin(context)) "http" else null
        }

    /** goform 走设备 Web 后台端口，而非 device_address 里的 UFI-TOOLS 端口 */
    override fun probePort(context: Context): Int = SPUtil.getGoformPort(context)

    override suspend fun getWifiData(context: Context, quickStart: Boolean): WifiEntity? =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureLogin(context)) return@withContext null

                // 批次 1：设备身份 + 网络基础 + SIM
                val identity = query(context, IDENTITY_COMMANDS) ?: return@withContext null
                // 批次 2：信号明细 + 流量 + 电量
                val metrics = query(context, METRICS_COMMANDS) ?: JSONObject()

                lastRawResponse = "identity=$identity\nmetrics=$metrics"

                buildEntity(context, identity, metrics)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "goform 采集异常: ${e.message}"
                DebugLogger.logApiErr(TAG, lastError)
                null
            }
        }

    override suspend fun fetchNotificationBaseInfo(context: Context): NotificationBaseInfo? =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureLogin(context)) return@withContext null
                val data = query(context, NOTIFY_COMMANDS) ?: return@withContext null

                val rx = data.longOf("monthly_rx_bytes")
                val tx = data.longOf("monthly_tx_bytes")

                NotificationBaseInfo(
                    // goform 没有当日流量，留空使通知层跳过日流量阈值检查
                    dailyFlowStr = "",
                    monthlyFlowStr = formatFlow(rx + tx),
                    // 温度 / CPU / 内存 goform 均不提供，留空跳过对应阈值检查
                    tempStr = "",
                    cpuStr = "",
                    memStr = "",
                    batteryPercent = data.intOf("battery_vol_percent", -1),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "goform 轻量采集异常: ${e.message}"
                DebugLogger.logApiErr(TAG, lastError)
                null
            }
        }

    /**
     * 切换设备地址 / 口令后调用，丢弃旧 session。
     *
     * 会一并清零失败退避计数——这是配置变更或用户主动「测试连接」的语义：
     * 换了地址/口令就该立刻重试，不该被上一套配置累积的退避挡住。
     * 读取失败导致的会话重建走 [renewSession]，那条路径必须保留计数。
     */
    fun invalidateSession() {
        sessionCookie = null
        loggedIn = false
        lastLoginAt = 0L
        consecutiveFailures = 0
    }

    // ══════════════════════════════════════════════
    // 认证
    // ══════════════════════════════════════════════

    private fun baseUrl(context: Context): String {
        val host = SPUtil.getDeviceHost(context)
        val port = SPUtil.getGoformPort(context)
        return if (port == 80) "http://$host" else "http://$host:$port"
    }

    private suspend fun ensureLogin(context: Context): Boolean = loginMutex.withLock {
        val now = System.currentTimeMillis()
        if (loggedIn && sessionCookie != null && now - lastLoginAt < SESSION_TTL_MS) return@withLock true
        loginLocked(context)
    }

    /**
     * 读取失败后重建会话。必须整体在锁内完成「版本比对 → 丢弃 → 重登」，
     * 否则并发调用方会互相踢掉刚建立的 session。
     *
     * @param seenVersion 调用方发起本次读取时看到的 [sessionVersion]
     * @return true 表示现在有一个可用的新会话（可能是别人建的），可以重试读取
     */
    private suspend fun renewSession(context: Context, seenVersion: Int): Boolean = loginMutex.withLock {
        // 已经有人换过会话了，直接用新的重试，不要再踢一次
        if (sessionVersion != seenVersion) return@withLock true

        sessionCookie = null
        loggedIn = false
        lastLoginAt = 0L
        // 刻意不清 consecutiveFailures：清了就等于绕过失败退避，
        // 设备离线或口令错误时会变成全速重试登录。
        loginLocked(context)
    }

    /** 真正的登录流程。调用方必须已持有 [loginMutex]。 */
    private fun loginLocked(context: Context): Boolean {
        val now = System.currentTimeMillis()

        // 失败退避：连续失败越多等越久，上限 60s（窗口算法见 DeviceHttp.kt）
        if (inLoginBackoff(consecutiveFailures, lastFailureAt, now)) {
            lastError = "goform 登录退避中（连续失败 $consecutiveFailures 次）"
            return false
        }

        loggedIn = false
        val base = baseUrl(context)
        val password = SPUtil.getGoformPassword(context)

        try {
            // Step 1：取 LD 挑战值（此时还没有 session，不带 Cookie）
            val ldBody = httpGet(base, "/goform/goform_get_cmd_process?cmd=LD&isTest=false", withCookie = false)
            val ld = ldBody?.let { runCatching { JSONObject(it).optString("LD", "") }.getOrDefault("") } ?: ""
            if (ld.isEmpty()) {
                DebugLogger.w(TAG, "ensureLogin: LD 为空，仍按空挑战继续尝试")
            }

            // Step 2：口令双层 SHA-256，均为大写十六进制
            val passHash = NetUtil.sha256(password).uppercase()
            val encPwd = NetUtil.sha256(passHash + ld).uppercase()

            // Step 3：优先 LOGIN_MULTI_USER，失败回退 LOGIN
            var ok = tryLogin(base, "isTest=false&goformId=LOGIN_MULTI_USER&user=admin&password=$encPwd&IP=localhost")
            if (!ok) {
                DebugLogger.w(TAG, "ensureLogin: LOGIN_MULTI_USER 失败，回退 LOGIN")
                ok = tryLogin(base, "isTest=false&goformId=LOGIN&user=admin&password=$encPwd")
            }

            if (!ok) {
                consecutiveFailures++
                lastFailureAt = System.currentTimeMillis()
                if (lastError.isEmpty()) lastError = "goform 登录被拒绝（口令错误或官方页面已占用会话）"
                DebugLogger.logApiErr(TAG, "ensureLogin failed: $lastError")
                return false
            }

            loggedIn = true
            lastLoginAt = System.currentTimeMillis()
            sessionVersion++
            consecutiveFailures = 0
            lastError = ""
            DebugLogger.i(TAG, "goform 登录成功 base=$base")
            return true
        } catch (e: Exception) {
            consecutiveFailures++
            lastFailureAt = System.currentTimeMillis()
            lastError = "goform 登录异常: ${e.message}"
            DebugLogger.logApiErr(TAG, lastError)
            return false
        }
    }

    /** 执行一次登录 POST，成功时把 Set-Cookie 存下来 */
    private fun tryLogin(base: String, formBody: String): Boolean {
        val request = Request.Builder()
            .url("$base/goform/goform_set_cmd_process")
            .header("Referer", "$base/index.html")
            .header("Origin", base)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody.toByteArray(Charsets.UTF_8).toRequestBody())
            .build()

        client.newCall(request).execute().use { resp ->
            // 无论成败都先抓 cookie —— 设备在失败响应里也可能下发新 session
            resp.header("Set-Cookie")?.substringBefore(';')?.takeIf { it.isNotBlank() }
                ?.let { sessionCookie = it }

            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || isLoginRejected(body)) {
                if (body.contains("\"result\":\"session\"", ignoreCase = true)) {
                    lastError = "设备会话被官方后台占用，请先退出设备管理页面"
                }
                return false
            }
            return true
        }
    }

    private fun isLoginRejected(body: String): Boolean {
        if (body.isBlank()) return true
        val lower = body.lowercase()
        return lower.contains("\"result\":\"failure\"") ||
            lower.contains("\"result\":\"session\"") ||
            lower.contains("no_match") ||
            lower.contains("failure")
    }

    private fun isAuthFailure(body: String): Boolean {
        if (body.isBlank()) return true
        val lower = body.lowercase()
        return lower.contains("none secure connection") ||
            lower.contains("not logged in") ||
            lower.contains("\"result\":\"failure\"")
    }

    // ══════════════════════════════════════════════
    // 读取
    // ══════════════════════════════════════════════

    /**
     * 批量读取 goform 字段。
     *
     * 单次 session 失效会自动重登一次并重试；再失败则返回 null。
     */
    private suspend fun query(context: Context, commands: List<String>): JSONObject? {
        val seenVersion = sessionVersion
        val body = queryRaw(context, commands)
            ?: run {
                // session 可能已失效 → 在锁内重建一次会话再试
                if (!renewSession(context, seenVersion)) return null
                queryRaw(context, commands)
            }
            ?: return null

        return runCatching { JSONObject(body) }.getOrElse {
            lastError = "goform 响应不是合法 JSON"
            DebugLogger.logApiErr(TAG, "$lastError: ${body.take(120)}")
            null
        }
    }

    private fun queryRaw(context: Context, commands: List<String>): String? {
        val base = baseUrl(context)
        val path = "/goform/goform_get_cmd_process?cmd=${commands.joinToString(",")}" +
            "&multi_data=1&isTest=false"
        val body = httpGet(base, path, withCookie = true) ?: return null

        if (isAuthFailure(body)) {
            DebugLogger.w(TAG, "queryRaw: 认证失效, body=${body.take(120)}")
            return null
        }
        // 设备高负载时可能返回截断响应：请求了多个字段却几乎没有返回
        if (commands.size > 5 && body.count { it == ':' } < 2) {
            DebugLogger.w(TAG, "queryRaw: 响应字段过少，视为无效, body=${body.take(120)}")
            return null
        }
        return body
    }

    private fun httpGet(base: String, path: String, withCookie: Boolean): String? {
        // goform 用 `_` 时间戳参数破除设备端缓存
        val sep = if (path.contains('?')) "&" else "?"
        val builder = Request.Builder()
            .url("$base$path${sep}_=${System.currentTimeMillis()}")

            .header("Referer", "$base/index.html")
        if (withCookie) sessionCookie?.let { builder.header("Cookie", it) }

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    lastError = "goform HTTP ${resp.code}"
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            lastError = "goform 请求失败: ${e.message}"
            null
        }
    }

    // ══════════════════════════════════════════════
    // 字段映射
    // ══════════════════════════════════════════════

    private val IDENTITY_COMMANDS = listOf(
        "network_type", "network_provider", "ppp_status",
        "lan_ipaddr", "mac_address", "station_mac", "imei", "imsi", "iccid", "sim_imsi",
        "hardware_version", "web_version", "wa_inner_version", "cr_version",
        "wan_ipaddr", "ipv6_wan_ipaddr", "pdp_type", "cell_id",
    )

    /**
     * 信号 / 流量 / 电量批次。
     *
     * `network_information` 是个**嵌套对象**（部分固件回 JSON 字符串），里面装着 NR 侧的
     * `nr_rsrp` / `Nr_snr` / `nr_rsrq` / `Nr_pci` / `Nr_fcn` / `Nr_bands` / `Nr_cell_id`。
     * 不查它就只能拿到 LTE 数值，5G 驻网时频段、PCI、频点全是 4G 的旧值或空。
     *
     * 注意没有查 `rssi` / `signalbar`：goform 的 `rssi` 是 0-5 的信号格数而不是 dBm，
     * 拿来当信号强度显示会得到 "3dBm" 这种荒谬结果。真实 dBm 只在
     * `nr_rsrp` / `Z5g_rsrp` / `lte_rsrp` / `Nr_signal_strength` 里。
     */
    private val METRICS_COMMANDS = listOf(
        "network_information",
        "lte_rsrp", "Lte_snr", "lte_rsrq", "lte_rssi", "Lte_pci", "Lte_fcn", "Lte_bands",
        "Z5g_rsrp", "Z5g_snr", "Z5g_SINR",
        "monthly_rx_bytes", "monthly_tx_bytes",
        // 电量键名各机型不一，读取侧是回退链，这里必须把回退链上的每个键都查出来：
        // cmd 清单就是契约，没问的字段固件一个都不会回，回退链再长也是空转。
        "battery_value", "battery_vol_percent", "battery_pers", "battery_capacity",
        "battery_charging", "pin_status",
    )

    private val NOTIFY_COMMANDS = listOf(
        "monthly_rx_bytes", "monthly_tx_bytes", "battery_vol_percent",
    )

    /**
     * goform `network_type` 的数字编码表。
     *
     * 同一字段在不同命令下有两种编码：状态类命令直接回可读文本（`"5G"`），
     * 而 `network_information` 回数字码（`20`）。所以只有纯数字才查表，
     * 否则原样透出——无条件查表会把 `"5G"` 变成 `未知(5G)`。
     *
     * 注意：`13` = 4G、`20` = 5G 是真机确认过的，其余数字码仍是按厂商常见编码推的，
     * 未经真机核对；这里的编码基准与 AT 链路（`AT+COPS` 的 3GPP ACT）**不通用**，
     * 两边不要互相套用。
     */
    private val NETWORK_TYPE_MAP = mapOf(
        "0" to "无服务", "1" to "GSM", "2" to "GPRS", "3" to "EDGE",
        "4" to "WCDMA", "5" to "HSDPA", "6" to "HSUPA", "7" to "HSPA",
        "8" to "LTE(FDD)", "9" to "LTE(TDD)", "10" to "CDMA", "11" to "EVDO",
        "12" to "LTE", "13" to "4G", "14" to "TDD LTE", "15" to "FDD LTE",
        "16" to "5G(NR)", "17" to "NR", "18" to "NR-SA", "19" to "NR-NSA",
        "20" to "5G", "21" to "5G-SA", "22" to "5G-NSA",
        "40" to "NR", "41" to "LTE-TDD", "42" to "LTE-FDD",
        "43" to "NR-TDD", "44" to "NR-FDD",
    )

    private fun mapNetworkType(code: String): String {
        val raw = code.trim()
        if (raw.isEmpty()) return ""
        if (raw.all { it.isDigit() }) return NETWORK_TYPE_MAP[raw] ?: raw
        return raw
    }

    /**
     * 把 `network_information` 里的 NR 字段提升到顶层，便于统一按 key 读取。
     *
     * 三种形态都要认：嵌套 JSONObject、JSON 字符串、以及压根没这个字段。
     * 顶层原有 key 优先，不被嵌套值覆盖。
     */
    private fun flatten(metrics: JSONObject): JSONObject {
        val nested = metrics.opt("network_information") ?: return metrics
        val obj = when (nested) {
            is JSONObject -> nested
            is String -> runCatching { JSONObject(nested) }.getOrNull()
            else -> null
        } ?: return metrics

        val merged = JSONObject()
        obj.keys().forEach { merged.put(it, obj.get(it)) }
        // 顶层覆盖嵌套：独立命令的值更权威
        metrics.keys().forEach { merged.put(it, metrics.get(it)) }
        return merged
    }

    /**
     * 从固件版本串里抽出产品型号。
     *
     * goform 协议没有独立的型号字段（UFI-AXIS 是跑在设备上、直接读 `Build.MODEL`，
     * 我们隔着 HTTP 拿不到），只能从版本串里提取。
     *
     * 取段规则：先在版本号标记（`V<数字>`）处截断，再取**第一个含数字、且不在
     * [MODULE_WORDS] 里**的下划线分段，最后剥掉粘连在前面的厂商/品类/模块词。
     *
     * 用「第一个」而不是「最后一个」：版本串里产品型号在前、基带模块型号在后是常见排列
     * （`F50_MU300V1.0.0`），取最后一段会拿到模块名 MU300 而不是产品名 F50。
     *
     * - `BD_FLYMODEM_F50V1.0.0B04` → 截断得 `BD_FLYMODEM_F50` → 分段 `F50`（BD/FLYMODEM 无数字）
     * - `BD_FLYMODEMF50V1.0.0B04`  → 截断得 `BD_FLYMODEMF50`  → 分段 `FLYMODEMF50` → 剥词 `F50`
     * - `ZTE_U30AIR_V1.0.0B08`     → 截断得 `ZTE_U30AIR_`     → 分段 `U30AIR`
     * - `F50_MU300V1.0.0`          → 截断得 `F50_MU300`       → 分段 `F50`（MU300 被过滤）
     * - `BD_MU300V1.0.0B04`        → 只剩模块名 → **放弃该候选串**，改试下一个
     *
     * 拿不到就返回空串，交由调用方回退。
     */
    internal fun extractModel(vararg versions: String): String {
        for (raw in versions) {
            val v = raw.trim().uppercase()
            if (v.isEmpty()) continue

            // 在第一个「V + 数字」处截断，去掉版本号尾巴
            val cut = Regex("V\\d").find(v)?.range?.first?.let { v.substring(0, it) } ?: v
            val segments = cut.split('_', '-').map { it.trim() }.filter { seg -> seg.any { it.isDigit() } }
            // 全是基带模块名（`BD_MU300V1.0.0` 这种）时不能退回第一段 —— 那等于把模块名
            // 当产品名报出去，正是「F50 被显示成 MU300」的成因。这里直接放弃本候选串，
            // 去试下一个（hardware_version / web_version 里常常才写着产品名）。
            val token = segments.firstOrNull { it !in MODULE_WORDS } ?: continue

            // 厂商 / 品类 / 模块词与型号粘在一起时剥掉前缀（FLYMODEMF50 → F50、MU300F50 → F50）
            val stripped = (VENDOR_WORDS + MODULE_WORDS).fold(token) { acc, word ->
                if (acc.length > word.length && acc.startsWith(word)) acc.removePrefix(word) else acc
            }
            val model = stripped.ifEmpty { token }
            if (model.isNotEmpty()) return model
        }
        return ""
    }

    /** 会与型号粘连的厂商/品类词，按长度降序剥离 */
    private val VENDOR_WORDS = listOf("FLYMODEM", "MODEM", "ZTE", "UFI", "BD")

    /**
     * 基带 / 通信模块型号，不是产品型号。
     *
     * 这些会出现在版本串里且形态与产品型号一样（字母+数字），只能靠名单排除。
     * 遇到新设备把模块名补进来即可；真机上如果仍然认错，用「设备显示名称」手动覆盖。
     */
    private val MODULE_WORDS = setOf("MU300", "MU500", "MU709", "RM500", "RM502", "RG500", "MH5000")

    private fun buildEntity(context: Context, identity: JSONObject, rawMetrics: JSONObject): WifiEntity {
        val metrics = flatten(rawMetrics)

        val monthlyRx = metrics.longOf("monthly_rx_bytes")
        val monthlyTx = metrics.longOf("monthly_tx_bytes")
        val monthlyTotal = monthlyRx + monthlyTx

        // 电量百分比的键名各机型不统一，逐个回退；全都没有则 -1，UI 按「无数据」隐藏
        val batteryPercent = metrics.intOrNull("battery_vol_percent")
            ?: metrics.intOrNull("battery_pers")
            ?: metrics.intOrNull("battery_value")
            ?: metrics.intOrNull("battery_capacity")
            ?: -1
        val charging = metrics.strOf("battery_charging") == "1"

        // NR（network_information）→ Z5g_*（独立字段）→ LTE，逐级回退
        val rsrp = metrics.intOrNull("nr_rsrp")
            ?: metrics.intOrNull("Z5g_rsrp")
            ?: metrics.intOrNull("lte_rsrp")
            ?: 0
        val sinr = metrics.intOrNull("Nr_snr")
            ?: metrics.intOrNull("Z5g_SINR")
            ?: metrics.intOrNull("Z5g_snr")
            ?: metrics.intOrNull("Lte_snr")
            ?: 0
        val rsrq = metrics.intOrNull("nr_rsrq") ?: metrics.intOrNull("lte_rsrq") ?: 0

        // 当前制式决定频段前缀：NR 用 nXX，LTE 用 BXX
        val netTypeRaw = identity.strOf("network_type").ifEmpty { metrics.strOf("network_type") }
        val netType = mapNetworkType(netTypeRaw)
        val isNr = netType.contains("5G", ignoreCase = true) || netType.contains("NR", ignoreCase = true)
        val band = if (isNr) {
            metrics.strOf("Nr_bands").firstBand()?.let { "n$it" } ?: ""
        } else {
            metrics.strOf("Lte_bands").firstBand()?.let { "B$it" } ?: ""
        }

        val pci = metrics.intOrNull("Nr_pci") ?: metrics.intOrNull("Lte_pci") ?: 0
        val earfcn = metrics.intOrNull("Nr_fcn") ?: metrics.intOrNull("Lte_fcn") ?: 0
        val cellId = metrics.strOf("Nr_cell_id").ifEmpty { identity.strOf("cell_id") }

        val imsi = identity.strOf("imsi").ifEmpty { identity.strOf("sim_imsi") }
        // UFI-AXIS 的字段定义：cr_version = 固件版本，wa_inner_version = 基带/Modem 版本
        val crVersion = identity.strOf("cr_version")
        val baseband = identity.strOf("wa_inner_version")
        val firmware = crVersion.ifEmpty { baseband }
        // 手动设置优先。固件串里装的常常是基带模块型号（MU300 这类）而不是产品名（F50），
        // 从版本号原理上推不出产品名，所以解析只作兜底。
        val hardware = identity.strOf("hardware_version")
        val web = identity.strOf("web_version")
        val override = SPUtil.getDeviceDisplayName(context)
        // 候选顺序：固件 → 基带 → 硬件版本 → Web 版本。前两个命中率最高，
        // 后两个只是兜底 —— 有的机型只把产品名写在这两处
        val parsed = extractModel(crVersion, baseband, hardware, web)
        val model = override.ifEmpty { parsed }.ifEmpty { "--" }

        DebugLogger.logApi(
            TAG,
            "型号: 手动=$override 解析=$parsed → $model ｜ 原始串 cr_version=$crVersion " +
                "wa_inner_version=$baseband hardware_version=$hardware web_version=$web"
        )

        val at = AtSignalInfo(
            networkType = netType,
            operator = identity.strOf("network_provider"),
            carrier = identity.strOf("network_provider"),
            rsrp = rsrp,
            sinr = sinr,
            rsrq = rsrq,
            band = band,
            pci = pci,
            earfcn = earfcn,
            // 以下字段 goform 无对应项，留空由 UI 逐项跳过
            rawQeng = "",
            rawCops = "",
            imei = identity.strOf("imei"),
            subscriptionRate = "",
            tac = "",
            cellId = cellId,
            moduleModel = "",
            firmwareDetail = baseband,
            cregStat = -1,
            lteRegistration = "",
            wanIpAt = identity.strOf("wan_ipaddr"),
            dnsServers = "",
            pinStatusAt = pinStatusText(metrics.strOf("pin_status")),
            rfFunc = "",
            moduleState = "",
            psAttached = identity.strOf("ppp_status"),
        )

        // 能力声明说「有」但真机没返回的字段，逐个点出来 —— 能力是按协议清单声明的，
        // 具体机型固件裁掉哪些字段只能在运行时看。UI 侧按「值缺失」隐藏，不靠能力声明。
        val missing = buildList {
            if (batteryPercent < 0) add("电量(battery_vol_percent/battery_pers/…)")
            if (rsrp == 0) add("RSRP(nr_rsrp/Z5g_rsrp/lte_rsrp)")
            if (sinr == 0) add("SINR(Nr_snr/Z5g_SINR/Lte_snr)")
            if (band.isEmpty()) add("频段(Nr_bands/Lte_bands)")
            if (pci == 0) add("PCI(Nr_pci/Lte_pci)")
            if (identity.strOf("network_provider").isEmpty()) add("运营商(network_provider)")
            if (monthlyTotal == 0L) add("月流量(monthly_rx_bytes/monthly_tx_bytes)")
        }
        if (missing.isNotEmpty()) {
            DebugLogger.logApi(TAG, "本机固件未返回的字段: ${missing.joinToString("、")}")
        }

        return WifiEntity(

            model = model,
            flow = formatFlow(monthlyTotal),
            // goform 无当日流量字段
            dailyFlow = "--",
            signal = formatSignal(rsrp),
            // 以下为 goform 不提供的维度，统一留空/清零，UI 按能力声明显示「暂无数据」
            temp = "--",
            battery = formatBattery(batteryPercent),
            batteryPercent = batteryPercent,
            batteryCharging = charging,
            cpu = "--",
            mem = "--",
            netType = netType,
            appVer = "",
            appVerCode = "",
            batteryCurrent = "",
            batteryVoltage = "",
            internalStorage = "--",
            internalAvailableStorage = 0L,
            internalTotalStorage = 0L,
            internalUsedStorage = 0L,
            externalTotalStorage = 0L,
            externalUsedStorage = 0L,
            externalAvailableStorage = 0L,
            clientIp = identity.strOf("lan_ipaddr"),
            deviceModel = model,
            firmwareVer = firmware,
            needToken = false,
            atNetworkInfo = at,
            cpuTempList = emptyList(),
            cpuFreqInfo = emptyMap(),
            cpuUsageInfo = emptyMap(),
            memTotalKb = 0L,
            memAvailableKb = 0L,
            memUsedKb = 0L,
            swapTotalKb = 0L,
            swapUsedKb = 0L,
            swapFreeKb = 0L,
            wanIp = identity.strOf("wan_ipaddr"),
            wanIpv6 = identity.strOf("ipv6_wan_ipaddr"),
            pdpType = identity.strOf("pdp_type"),
            imei = identity.strOf("imei"),
            imsi = imsi,
            iccid = identity.strOf("iccid"),
            hardwareVersion = identity.strOf("hardware_version"),
            webVersion = identity.strOf("web_version"),
            macAddress = identity.strOf("mac_address").ifEmpty { identity.strOf("station_mac") },
            pinStatusCode = metrics.intOf("pin_status", -1),
            monthlyUploadBytes = monthlyTx,
            monthlyDownloadBytes = monthlyRx,
            // goform 只有月累计，日流量交由 TrafficRecordManager 按日差值推导
            dailyRawBytes = 0L,
            monthlyRawBytes = monthlyTotal,
        )
    }

    private fun pinStatusText(code: String): String = when (code) {
        "0" -> "READY"
        "1" -> "SIM PIN"
        "2" -> "SIM PUK"
        else -> ""
    }

    // ── JSONObject 读取辅助：goform 所有值都是字符串，需要显式转换 ──

    private fun JSONObject.strOf(key: String): String = optString(key, "").trim()

    private fun JSONObject.longOf(key: String): Long =
        strOf(key).toLongOrNull() ?: 0L

    private fun JSONObject.intOf(key: String, fallback: Int): Int =
        strOf(key).toIntOrNull() ?: fallback

    /** 与 [intOf] 的区别：字段缺失或非数字时返回 null，便于多字段逐级回退时区分「没有」和「值为 0」 */
    private fun JSONObject.intOrNull(key: String): Int? = strOf(key).toIntOrNull()

    /** goform 的频段字段可能是逗号分隔的多频段（CA），取第一个作为主频段 */
    private fun String.firstBand(): String? =
        split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "0" }
}
