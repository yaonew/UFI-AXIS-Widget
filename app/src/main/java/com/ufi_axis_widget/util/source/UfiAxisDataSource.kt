package com.ufi_axis_widget.util.source

import android.content.Context
import android.os.Build
import com.ufi_axis_widget.util.AtSignalInfo
import com.ufi_axis_widget.util.CpuFreqItem
import com.ufi_axis_widget.util.CpuTempItem
import com.ufi_axis_widget.util.DataSourceType
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.DeviceCapabilities
import com.ufi_axis_widget.util.NotificationBaseInfo
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.WifiEntity
import com.ufi_axis_widget.util.formatBattery
import com.ufi_axis_widget.util.formatFlow
import com.ufi_axis_widget.util.formatPercent
import com.ufi_axis_widget.util.formatSignal
import com.ufi_axis_widget.util.formatStorage
import com.ufi_axis_widget.util.formatTemp
import com.ufi_axis_widget.util.formatVoltage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * UFI-AXIS core 数据源（设备上自建的 Ktor 服务，默认端口 8088）。
 *
 * 与另外两个源的根本区别：字段已经由 core 归一化过了。goform 那边字段名随固件漂移、
 * 值全是字符串、同一个量散在好几个 key 里；core 对外只输出登记过的 canonical key，
 * 数值就是数值，所以这里几乎不需要「多字段逐级回退」那套体操。
 *
 * 认证：三步握手换 Token，之后每请求签名
 * 1. `GET  /pairing/info`      → `pairing_code`（仅未初始化的设备下发）/ `has_default_password`；
 * 2. `POST /pairing/challenge` → 一次性 nonce（2 分钟有效）；
 * 3. `POST /pairing/confirm`   带 `pairing_code` + `device_pubkey` + `challenge` + `signature`
 *    （+ 设备密码）→ `{ token, fingerprint }`。
 *
 * 指纹由 core 据公钥计算，**客户端自报的指纹一律不采信**；配对密码始终必填，
 * 首次配对提交的密码会被 core 落库为设备密码（出厂默认 admin）。
 *
 * 每个 `/api` 请求都必须同时带 `Authorization: Bearer <token>`、`X-Timestamp`（毫秒）、
 * `X-Nonce` 与 `X-Signature`（设备私钥对 `METHOD\nURI\nTS\nNONCE` 的 ECDSA-SHA256 签名）。
 * 缺一即 444，所以不能只带 Bearer。
 *
 * Token 失效（444）时自动重新配对一次：core 侧解绑或重装后 token 会失效，
 * 但配对密码还存在本地，用户不该被迫手动重来。
 *
 * 采集用 5 个 GET：`/api/dashboard/summary` 一把拿设备/电池/存储/流量/网络状态，
 * 另外 4 个补 CPU、内存、信号明细、当日流量。core 自带 3 秒响应缓存，
 * 频繁调用不会真的打到设备。
 */
object UfiAxisDataSource : DeviceDataSource {

    private const val TAG = "UfiAxisDataSource"

    /** core 的鉴权失败状态码：444 是它自定义的「未授权」，401 留作兼容旧版本 */
    private val AUTH_FAILURE_CODES = setOf(444, 401)

    /** 上报给 core 的客户端标识，拼在机型名后面，用于在已配对列表里区分是谁申请的 */
    private const val PAIR_CLIENT_TAG = "UFI 小组件"

    override val type = DataSourceType.UFI_AXIS
    override val capabilities = DeviceCapabilities.UFI_AXIS

    @Volatile
    override var lastError: String = ""
        private set

    @Volatile
    override var lastRawResponse: String = ""
        private set

    /** 配对独占锁：多个后台任务同时发现 token 失效时，只允许一个去换 token */
    private val pairMutex = Mutex()

    /** 配对失败退避，避免对着配对接口的 10 秒限流反复撞墙 */
    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var lastFailureAt = 0L

    /**
     * token 版本号，每换一次 token 自增。
     *
     * 一轮采集要发 4-5 个 GET，加上通知轮询与磁贴入口，多个请求会同时持着**同一个**
     * 旧 token。第一个撞上 444 的把 token 换掉之后，后到者手里的 token 也是旧的、
     * 同样会撞 444 —— 若无条件清理重配，就会把前者刚换好的新 token 又清掉，
     * 连环重配对，还有撞上 core 密码锁定（15 分钟 5 次）的风险。
     * 带上「我看到的版本」，只有版本没变才允许清理，与 GoformDataSource 的
     * sessionVersion 是同一套做法。
     */
    @Volatile
    private var tokenVersion = 0

    // 共用 [deviceHttpClient]，配置见 DeviceHttp.kt
    private val client: OkHttpClient get() = deviceHttpClient

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // ══════════════════════════════════════════════
    // 公共接口实现
    // ══════════════════════════════════════════════

    /**
     * 用免认证的 `/health` 探连通性，顺带把配对走通。
     *
     * 「测试连接」按钮走的就是这里，所以必须连 token 一起验：
     * 只探到端口开着、但配对没成，对用户来说仍然是不可用。
     */
    override suspend fun probeProtocol(context: Context): String? =
        withContext(Dispatchers.IO) {
            val protocol = listOf("http", "https").firstOrNull { p ->
                rawGet(context, p, "/health") != null
            } ?: run {
                lastError = "UFI-AXIS 服务无响应（${host(context)}:${SPUtil.getUfiAxisPort(context)}）"
                return@withContext null
            }
            // 强制重新配对：换了地址就该重新握手，旧 token 大概率属于另一台设备
            SPUtil.clearUfiAxisPairing(context)
            consecutiveFailures = 0
            if (ensureToken(context, protocol) == null) null else protocol
        }

    /** UFI-AXIS 服务端口独立于 device_address 里的端口 */
    override fun probePort(context: Context): Int = SPUtil.getUfiAxisPort(context)

    override suspend fun getWifiData(context: Context, quickStart: Boolean): WifiEntity? =
        withContext(Dispatchers.IO) {
            try {
                val summary = apiGet(context, "/api/dashboard/summary") ?: return@withContext null
                // 设备已经应答，这一轮就算刷新过了 —— 立刻打点更新时间。
                // core 的返回里没有「本次采集时间」这种字段，而 saveData 的时间戳只在
                // 整份数据通过校验后才前进，中间任何一步把这轮判为脏数据，界面上的时间就会卡住。
                SPUtil.touchUpdateTime(context)
                // 补充维度取不到也不该让整轮采集失败：主界面少一个面板 ≫ 全屏「采集失败」
                val cpu = apiGet(context, "/api/system/cpu") ?: JSONObject()
                val memory = apiGet(context, "/api/system/memory") ?: JSONObject()
                val signal = apiGet(context, "/api/network/signal") ?: JSONObject()
                val traffic = trafficOf(context, summary)

                lastRawResponse = "summary=$summary\ncpu=$cpu\nmemory=$memory\n" +
                    "signal=$signal\ntraffic=$traffic"

                buildEntity(context, summary, cpu, memory, signal, traffic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "UFI-AXIS 采集异常: ${e.message}"
                DebugLogger.logApiErr(TAG, lastError)
                null
            }
        }

    override suspend fun fetchNotificationBaseInfo(context: Context): NotificationBaseInfo? =
        withContext(Dispatchers.IO) {
            try {
                val summary = apiGet(context, "/api/dashboard/summary") ?: return@withContext null
                val cpu = apiGet(context, "/api/system/cpu") ?: JSONObject()
                val memory = apiGet(context, "/api/system/memory") ?: JSONObject()
                val traffic = trafficOf(context, summary)

                NotificationBaseInfo(
                    dailyFlowStr = formatFlow(todayBytes(traffic)),
                    monthlyFlowStr = formatFlow(monthlyBytes(summary, traffic)),
                    tempStr = cpu.optDouble("temperature", -1.0)
                        .takeIf { it > 0 }?.let { formatTemp(it) } ?: "",
                    cpuStr = cpu.optDouble("usage_percent", -1.0)
                        .takeIf { it >= 0 }?.let { formatPercent(it) } ?: "",
                    memStr = memory.optDouble("usage_percent", -1.0)
                        .takeIf { it >= 0 }?.let { formatPercent(it) } ?: "",
                    batteryPercent = summary.optJSONObject("battery")?.let { b ->
                        b.optInt("percent", -1).takeIf { it >= 0 } ?: b.optInt("level", -1)
                    } ?: -1,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "UFI-AXIS 轻量采集异常: ${e.message}"
                DebugLogger.logApiErr(TAG, lastError)
                null
            }
        }

    /**
     * 流量段。dashboard 的 `traffic_summary` 与 `/api/traffic/summary` 共用同一个
     * mapper（字段完全一致），所以正常情况直接复用聚合结果，省掉一次请求；
     * 只有它整段超时变 null 时才单独去打那个接口。
     */
    private suspend fun trafficOf(context: Context, summary: JSONObject): JSONObject =
        summary.optJSONObject("traffic_summary")
            ?: apiGet(context, "/api/traffic/summary")
            ?: JSONObject()

    /** 改地址 / 改端口 / 改配对密码后调用：旧 token 属于旧目标，留着只会白撞 401 */
    fun invalidatePairing(context: Context) {
        SPUtil.clearUfiAxisPairing(context)
        consecutiveFailures = 0
    }

    // ══════════════════════════════════════════════
    // 认证：配对换 Token
    // ══════════════════════════════════════════════

    private fun host(context: Context) = SPUtil.getDeviceHost(context)

    private fun protocolOf(context: Context): String {
        val stored = SPUtil.getDeviceProtocol(context)
        return if (stored == "https") "https" else "http"
    }

    private fun baseUrl(context: Context, protocol: String): String {
        val port = SPUtil.getUfiAxisPort(context)
        return "$protocol://${host(context)}:$port"
    }

    /** 返回可用 token 与取到它时的 [tokenVersion]，拿不到返回 null（原因写进 [lastError]） */
    private suspend fun ensureToken(context: Context, protocol: String): Pair<String, Int>? {
        // 先读版本再读 token：反过来的话可能拿到「比版本更新的 token」，
        // 之后误判成版本已变而跳过清理。宁可版本偏旧（结果是少清一次），
        // 也不能版本偏新（结果是把别人刚换的 token 清掉）
        val seen = tokenVersion
        SPUtil.getUfiAxisToken(context).takeIf { it.isNotEmpty() }?.let { return it to seen }
        return pairMutex.withLock {
            // 等锁期间别人可能已经配好了
            val existing = SPUtil.getUfiAxisToken(context).takeIf { it.isNotEmpty() }
            if (existing != null) existing to tokenVersion
            else pairLocked(context, protocol)?.let { it to tokenVersion }
        }
    }

    /**
     * 凭据失效后换 token。
     *
     * @param seenVersion 调用方发请求时用的那个 token 的版本。版本已经变了说明
     *   别的请求刚换过，此时直接用新 token 重试即可，绝不能再清一次。
     */
    private suspend fun renewPairing(
        context: Context,
        protocol: String,
        seenVersion: Int
    ): String? = pairMutex.withLock {
        if (tokenVersion != seenVersion) {
            return@withLock SPUtil.getUfiAxisToken(context).takeIf { it.isNotEmpty() }
        }
        SPUtil.clearUfiAxisPairing(context)
        pairLocked(context, protocol)
    }

    /** 真正的配对流程。调用方必须已持有 [pairMutex]。 */
    private fun pairLocked(context: Context, protocol: String): String? {
        val now = System.currentTimeMillis()
        // 配对三个端点各有独立的每 IP 500ms 限流，正常握手撞不上；
        // 这里的退避是为了别在「密码错」这类必然失败上反复撞密码锁定（15 分钟 5 次）
        if (inLoginBackoff(consecutiveFailures, lastFailureAt, now)) {
            lastError = "UFI-AXIS 配对退避中（连续失败 $consecutiveFailures 次）"
            return null
        }

        fun fail(reason: String): String? {
            lastError = reason
            consecutiveFailures++
            lastFailureAt = System.currentTimeMillis()
            DebugLogger.logApiErr(TAG, reason)
            return null
        }

        val pubKey = UfiAxisDeviceKey.publicKeySpkiBase64()
            ?: return fail("UFI-AXIS 无法生成本机设备密钥")

        val info = rawGet(context, protocol, "/pairing/info")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return fail("UFI-AXIS 取配对信息失败（非本地子网或服务未启动）")

        // 配对码只在设备尚未初始化时下发；已初始化的设备凭设备密码登录，空码是正常的
        val code = info.optString("pairing_code", "")

        val challenge = rawPost(context, protocol, "/pairing/challenge", "{}")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optString("challenge", "")
            ?.takeIf { it.isNotEmpty() }
            ?: return fail("UFI-AXIS 取配对挑战失败")

        // 签名对象是挑战原文，不做任何拼装 —— core 侧就是这么验的
        val signature = UfiAxisDeviceKey.sign(challenge)
            ?: return fail("UFI-AXIS 挑战签名失败")

        val body = JSONObject().apply {
            put("pairing_code", code)
            put("device_pubkey", pubKey)
            put("challenge", challenge)
            put("signature", signature)
            // 密码始终必填：首次配对即「设置设备密码」，之后是「凭密码登录」
            put("password", SPUtil.getUfiAxisPairPassword(context))
            // 带上应用后缀：core 的已配对列表里要能一眼分清「这条是小组件申请的」，
            // 而不是和 UFI-AXIS 官方 app 的记录混在一起（两者机型名会完全一样）
            put("device_name", "${Build.MODEL ?: "Android"} · $PAIR_CLIENT_TAG")
            // 仅用于合并「同一台手机重装后换了密钥」的重复记录，不参与安全判定
            put("device_hwid", SPUtil.getDeviceHwId(context))
        }

        val resp = rawPost(context, protocol, "/pairing/confirm", body.toString())
            ?: return fail("UFI-AXIS 配对请求无响应")

        val json = runCatching { JSONObject(resp) }.getOrNull()
            ?: return fail("UFI-AXIS 配对响应无法解析")

        val token = json.optString("token", "")
        if (token.isEmpty()) {
            // core 的失败体带 code，直接把原因转给用户比「配对失败」有用得多
            val reason = when (json.optString("code", "")) {
                "PASSWORD_REQUIRED" -> "需要设备配对密码"
                "INVALID_PASSWORD" -> "配对密码错误（出厂默认 admin）"
                "INVALID_CODE" -> "配对码无效或已过期，请重启设备端 core 后重试"
                "INVALID_CHALLENGE" -> "配对挑战已失效，请重试"
                "INVALID_DEVICE_KEY" -> "本机设备密钥被拒，请在设备端删除旧配对记录后重试"
                // 刻意不自动调 /pairing/unpair 补救：那个接口会清掉设备上的全部配对，
                // 顺手替用户做这种决定太危险，只把该怎么处理说清楚
                "ALREADY_PAIRED" -> "设备端已达最大配对数，请先在 UFI-AXIS 应用里删掉旧的配对记录"
                "PASSWORD_LOCKED" -> "密码错误次数过多，已被临时锁定（15 分钟）"
                else -> json.optString("error", "未知原因")
            }
            return fail("UFI-AXIS 配对失败：$reason")
        }

        SPUtil.saveUfiAxisPairing(context, token, json.optString("fingerprint", ""))
        // 版本自增必须紧跟保存：并发的 444 处理靠它判断「这个 token 是不是我看到的那个」
        tokenVersion++
        consecutiveFailures = 0
        DebugLogger.logSys(TAG, "UFI-AXIS 配对成功")
        return token
    }

    // ══════════════════════════════════════════════
    // HTTP
    // ══════════════════════════════════════════════

    /** 带设备签名的鉴权 GET。凭据失效（444/401）时重新配对一次再重试。 */
    private suspend fun apiGet(context: Context, path: String): JSONObject? {
        val protocol = protocolOf(context)
        val (token, seenVersion) = ensureToken(context, protocol) ?: return null

        val first = execute(context, protocol, path, token)
        if (first.second in AUTH_FAILURE_CODES) {
            // token 被服务端吊销（core 侧删了本机记录）或本机时钟漂移过大；
            // 换 token 后只重试一次，避免登录风暴
            DebugLogger.w(TAG, "$path 返回 ${first.second}，重新配对后重试")
            val fresh = renewPairing(context, protocol, seenVersion) ?: return null
            val second = execute(context, protocol, path, fresh)
            return second.first?.let { parse(path, it) }
        }
        return first.first?.let { parse(path, it) }
    }

    private fun parse(path: String, body: String): JSONObject? =
        runCatching { JSONObject(body) }.getOrElse {
            lastError = "UFI-AXIS $path 响应不是 JSON"
            DebugLogger.logApiErr(TAG, lastError)
            null
        }

    /** @return body 与 HTTP 状态码（网络异常时状态码为 -1） */
    private fun execute(
        context: Context,
        protocol: String,
        path: String,
        token: String
    ): Pair<String?, Int> {
        return try {
            // core 对每个 /api 请求都要验签：METHOD\nURI\nTS\nNONCE，
            // 其中 URI 是服务端可见的 path + query（这里的路径都不带 query）
            val timestamp = System.currentTimeMillis().toString()
            val nonce = UfiAxisDeviceKey.newNonce()
            val signature = UfiAxisDeviceKey.sign("GET\n$path\n$timestamp\n$nonce")
                ?: run {
                    lastError = "UFI-AXIS 请求签名失败"
                    DebugLogger.logApiErr(TAG, lastError)
                    return null to -1
                }
            val req = Request.Builder()
                .url(baseUrl(context, protocol) + path)
                .header("Authorization", "Bearer $token")
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code !in AUTH_FAILURE_CODES) {
                        lastError = "UFI-AXIS $path HTTP ${resp.code}"
                        DebugLogger.logApiErr(TAG, lastError)
                    }
                    return null to resp.code
                }
                resp.body?.string() to resp.code
            }
        } catch (e: Exception) {
            lastError = "UFI-AXIS $path 请求异常: ${e.message}"
            DebugLogger.logApiErr(TAG, lastError)
            null to -1
        }
    }

    /** 免认证 GET（`/health`、`/pairing/info`） */
    private fun rawGet(context: Context, protocol: String, path: String): String? = try {
        val req = Request.Builder().url(baseUrl(context, protocol) + path).get().build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        DebugLogger.d(TAG, "rawGet $path failed: ${e.message}")
        null
    }

    /** 免认证 POST（`/pairing/confirm`）。失败体也要读出来 —— 里面装着拒绝原因的 code。 */
    private fun rawPost(context: Context, protocol: String, path: String, json: String): String? = try {
        val req = Request.Builder()
            .url(baseUrl(context, protocol) + path)
            .post(json.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp -> resp.body?.string() }
    } catch (e: Exception) {
        DebugLogger.d(TAG, "rawPost $path failed: ${e.message}")
        null
    }

    // ══════════════════════════════════════════════
    // 字段映射
    // ══════════════════════════════════════════════

    private fun buildEntity(
        context: Context,
        summary: JSONObject,
        cpu: JSONObject,
        memory: JSONObject,
        signal: JSONObject,
        traffic: JSONObject,
    ): WifiEntity {
        val deviceInfo = summary.optJSONObject("device_info") ?: JSONObject()
        // device 是个对象（brand/model/device/manufacturer/android_version/sdk_version/build_id），
        // 不是字符串 —— 设备名在 model 里，build_id 才是固件版本（kernel 是内核版本，两回事）
        val deviceObj = deviceInfo.optJSONObject("device") ?: JSONObject()
        val coreModel = deviceObj.optString("model", "")
        val firmware = deviceObj.optString("build_id", "")
            .takeIf { it.isNotEmpty() && it != "Unknown" } ?: ""
        val identity = deviceInfo.optJSONObject("identity") ?: JSONObject()
        val network = deviceInfo.optJSONObject("network") ?: JSONObject()
        val battery = summary.optJSONObject("battery") ?: JSONObject()
        val storage = summary.optJSONObject("storage")
            ?: deviceInfo.optJSONObject("storage") ?: JSONObject()
        val limit = summary.optJSONObject("traffic_limit") ?: JSONObject()

        // 用户手填的设备名优先：core 报的是 Build.MODEL（MU300 这类模块型号），
        // 不一定是用户认得的产品名（F50）
        val model = SPUtil.getDeviceDisplayName(context).ifEmpty { coreModel }

        val monthlyRx = limit.optLong("monthly_rx_bytes", 0L)
        val monthlyTx = limit.optLong("monthly_tx_bytes", 0L)
        val monthlyTotal = monthlyBytes(summary, traffic)
        val dailyTotal = todayBytes(traffic)

        // level 是原始电量刻度，percent 才是换算过的百分比
        val batteryPercent = battery.optInt("percent", -1)
            .takeIf { it >= 0 } ?: battery.optInt("level", -1)
        val charging = battery.optBoolean("is_charging", false)

        val cpuTemp = cpu.optDouble("temperature", -1.0)
        val cpuUsage = cpu.optDouble("usage_percent", -1.0)
        val memUsage = memory.optDouble("usage_percent", -1.0)

        val memTotalKb = memory.optLong("total", 0L) / 1024
        val memAvailableKb = memory.optLong("available", 0L) / 1024
        val memUsedKb = memory.optLong("used", 0L) / 1024

        val internalTotal = storage.optLong("total", 0L)
        val internalUsed = storage.optLong("used", 0L)
        val internalAvailable = storage.optLong(
            "available", (internalTotal - internalUsed).coerceAtLeast(0L)
        )

        // 各核心频率：core 给的是数组，键名用核心序号，与 UFI-TOOLS 侧的 map 结构对齐
        val freqInfo = mutableMapOf<String, CpuFreqItem>()
        cpu.optJSONArray("cores")?.let { arr ->
            for (i in 0 until arr.length()) {
                val core = arr.optJSONObject(i) ?: continue
                val mhz = core.optDouble("freq_mhz", 0.0).toInt()
                freqInfo["cpu${core.optInt("core", i)}"] = CpuFreqItem(cur = mhz, max = 0)
            }
        }

        val at = buildSignal(signal, network)

        return WifiEntity(
            model = model,
            flow = formatFlow(monthlyTotal),
            dailyFlow = if (dailyTotal > 0) formatFlow(dailyTotal) else "--",
            signal = formatSignal(at.rsrp),
            temp = if (cpuTemp > 0) formatTemp(cpuTemp) else "--",
            battery = formatBattery(batteryPercent),
            batteryPercent = batteryPercent,
            batteryCharging = charging,
            cpu = if (cpuUsage >= 0) formatPercent(cpuUsage) else "--",
            mem = if (memUsage >= 0) formatPercent(memUsage) else "--",
            netType = network.optString("type", ""),
            // core 的版本号在 /api/update，不值得为它多打一次请求
            appVer = "",
            appVerCode = "",
            // core 只报电池电压（V），不报电流
            batteryCurrent = "",
            batteryVoltage = battery.optDouble("voltage", -1.0)
                .takeIf { it > 0 }?.let { formatVoltage(it) } ?: "",
            internalStorage = if (internalTotal > 0) formatStorage(internalTotal, internalUsed) else "--",
            internalAvailableStorage = internalAvailable,
            internalTotalStorage = internalTotal,
            internalUsedStorage = internalUsed,
            // core 的存储接口只报一份（设备自身分区），没有内外之分
            externalTotalStorage = 0L,
            externalUsedStorage = 0L,
            externalAvailableStorage = 0L,
            clientIp = "",
            deviceModel = coreModel,
            firmwareVer = firmware,
            needToken = false,
            atNetworkInfo = at,
            // 温度只有一个总值，没有分模块列表
            cpuTempList = if (cpuTemp > 0) listOf(CpuTempItem("cpu", cpuTemp)) else emptyList(),
            cpuFreqInfo = freqInfo,
            cpuUsageInfo = if (cpuUsage >= 0) mapOf("total" to formatPercent(cpuUsage)) else emptyMap(),
            memTotalKb = memTotalKb,
            memAvailableKb = memAvailableKb,
            memUsedKb = memUsedKb,
            // core 不报 swap
            swapTotalKb = 0L,
            swapUsedKb = 0L,
            swapFreeKb = 0L,
            wanIp = "",
            wanIpv6 = "",
            pdpType = "",
            imei = identity.optString("imei", ""),
            imsi = identity.optString("imsi", ""),
            iccid = identity.optString("iccid", ""),
            hardwareVersion = "",
            webVersion = "",
            macAddress = "",
            pinStatusCode = -1,
            monthlyUploadBytes = monthlyTx,
            monthlyDownloadBytes = monthlyRx,
            dailyRawBytes = dailyTotal,
            monthlyRawBytes = monthlyTotal,
        )
    }

    /**
     * 信号明细。
     *
     * core 已经把服务小区派生成 `band_label` / `arfcn` / `pci` / `signal_strength`，
     * NR 优先 LTE 兜底的选择在服务端做过了，这里直接用，**不要**再去解析 `rat` 文案
     * （那是 44 项映射表的输出，含「未知(xx)」这类值，按它分支会静默走错）。
     */
    private fun buildSignal(signal: JSONObject, network: JSONObject): AtSignalInfo {
        val operator = signal.optString("operator", "").ifEmpty { network.optString("operator", "") }
        val bandLabel = signal.optString("band_label", "")
        return AtSignalInfo(
            networkType = network.optString("type", "").ifEmpty { signal.optString("rat", "") },
            operator = operator,
            carrier = operator,
            rsrp = signal.optInt("rsrp", 0),
            sinr = signal.optInt("sinr", 0),
            rsrq = signal.optInt("rsrq", 0),
            band = bandLabel,
            pci = signal.optInt("pci", 0),
            earfcn = signal.optInt("arfcn", 0),
            rawQeng = "",
            rawCops = "",
            imei = "",
            subscriptionRate = "",
            tac = "",
            cellId = signal.optString("cell_id", ""),
            moduleModel = "",
            firmwareDetail = "",
            cregStat = -1,
            lteRegistration = if (signal.optBoolean("network_registered", false)) "已注册" else "",
            wanIpAt = "",
            dnsServers = "",
            pinStatusAt = "",
            rfFunc = "",
            moduleState = "",
            psAttached = "",
        )
    }

    /**
     * 月累计：优先 traffic_limit 的原始字节，再退回 traffic_summary 的 total_*_bytes，
     * 最后才是 traffic_limit 的 used_bytes。
     */
    private fun monthlyBytes(summary: JSONObject, traffic: JSONObject): Long {
        val limit = summary.optJSONObject("traffic_limit") ?: JSONObject()
        val fromLimit = limit.optLong("monthly_rx_bytes", 0L) + limit.optLong("monthly_tx_bytes", 0L)
        if (fromLimit > 0) return fromLimit
        val fromSummary = traffic.optLong("total_rx_bytes", 0L) + traffic.optLong("total_tx_bytes", 0L)
        if (fromSummary > 0) return fromSummary
        return limit.optLong("used_bytes", 0L)
    }

    /**
     * 当日用量。core 直接给了 `today_rx_bytes` / `today_tx_bytes`（自算的「当月累计 − 当日基线」），
     * 用原始字节，不要去反解 `today_*_display` 那种「1.0 GB」的展示串。
     */
    private fun todayBytes(traffic: JSONObject): Long =
        traffic.optLong("today_rx_bytes", 0L) + traffic.optLong("today_tx_bytes", 0L)
}
