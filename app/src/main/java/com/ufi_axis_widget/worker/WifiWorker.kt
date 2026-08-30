package com.ufi_axis_widget.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.formatFlow

import com.ufi_axis_widget.util.MainDialogHelper
import com.ufi_axis_widget.util.NotificationHelper
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.TrafficRecordManager
import com.ufi_axis_widget.util.WifiGuard
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry
import com.ufi_axis_widget.widget.BaseWifiWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 一轮完整采集的结果。
 *
 * `NO_DATA` 与 `DIRTY_DATA` 必须分开：前者是设备没应答（可能离线），
 * 后者是设备应答了但数值不可信（脏数据），拿脏数据去触发离线告警是误报。
 */
enum class CollectOutcome { PUBLISHED, NO_DATA, DIRTY_DATA }

class WifiWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "WifiWorker"

        /** 周期任务的唯一名。三个入队点必须用同一个名字，否则会排出多份重复任务 */
        const val WORK_NAME = "wifi_crawl"

        /**
         * 构造周期任务请求。
         *
         * 抽出来是因为原先三个入队点（MainActivity、WidgetSettingsActivity、
         * BaseWifiWidget.ensurePeriodicWorker）各自 new 一份，参数一旦不一致，
         * `UPDATE` 策略下就会互相覆盖出不同的退避配置 —— 表现为「有时会退避有时不会」，
         * 极难排查。现在只有这一处构造。
         *
         * 退避策略与 [KeepAlivePeriodicWorker] 对齐：设备离线时 Worker 会返回 retry，
         * 没有退避就会按固定间隔一直空转失败、白耗电。
         */
        fun buildPeriodicRequest(minutes: Int): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<WifiWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

        /**
         * 注册/更新周期任务。`minutes <= 0` 表示用户关闭了后台刷新，直接取消。
         *
         * @param keepExisting true 用 KEEP（小组件侧「有就别动」），false 用 UPDATE（设置页改间隔）
         */
        fun schedulePeriodic(context: Context, minutes: Int, keepExisting: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (minutes <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE,
                buildPeriodicRequest(minutes)
            )
        }

        /** 立即执行一次采集（一次性任务）。调用方负责判守卫 */
        fun enqueueOneShot(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WifiWorker>().build())
        }

        /**
         * TCP ping 超时（毫秒）。内网正常 < 100ms，但 Wi-Fi 省电休眠唤醒、
         * ARP 重解析、信道拥塞都可能让单次握手超过 1s，故留出余量避免误判。
         */
        private const val PING_TIMEOUT_MS = 2500

        /** API 请求连续失败多少次后才标记 stopped（ping 通过的前提下） */
        const val API_MAX_FAILURES = 3

        /** 网络不通连续多少次后才标记 stopped */
        const val NETWORK_MAX_FAILURES = 2

        /** 失败原因常量 */
        const val REASON_NETWORK = "network"   // 设备不在线/网络不通
        const val REASON_API = "api"           // 端口/Token/认证配置错误

        // ── 以下方法统一委托给 SPUtil（线程安全 + commit()）──

        fun isWorkerStopped(context: Context) = SPUtil.isWorkerStopped(context)

        fun getFailureSummary(context: Context) = SPUtil.getWorkerFailureSummary(context)

        fun resetFailureState(context: Context) = SPUtil.resetWorkerFailureState(context)

        fun incrementNetworkFailureCount(context: Context) = SPUtil.incrementNetworkFailureCount(context)

        fun incrementApiFailureCount(context: Context) = SPUtil.incrementApiFailureCount(context)

        fun markWorkerStoppedNetwork(context: Context) = SPUtil.markWorkerStoppedNetwork(context)

        fun markWorkerStoppedApi(context: Context) = SPUtil.markWorkerStoppedApi(context)

        /** 仅重置网络失败计数（ping 恢复时，内部委托 SPUtil） */
        private fun resetNetworkFailCount(context: Context) = SPUtil.resetNetworkFailureCount(context)

        /**
         * 轻量探测失败上报（供 NotificationMonitor / KeepAlivePeriodicWorker 调用）。
         *
         * 所有探测路径共用这一组失败计数与阈值，只有连续失败到
         * [API_MAX_FAILURES] 才判定设备离线。单次超时、token 失效、
         * 解析异常都不会立刻告警；共用计数也消除了多条路径各自写
         * `notify_prev_online` 造成的"离线→上线→离线"通知震荡。
         *
         * @param reason 停机原因，必须由调用方判定。写死成 [REASON_API] 会把
         *   「设备没连上」说成「端口/Token 配置错误」，引导用户去改根本没错的配置。
         */
        fun reportProbeFailure(context: Context, reason: String = REASON_API) {
            val fails = incrementApiFailureCount(context)
            DebugLogger.w(TAG, "reportProbeFailure: $fails/$API_MAX_FAILURES reason=$reason")
            if (fails < API_MAX_FAILURES) return

            val wasStopped = SPUtil.isWorkerStopped(context)
            if (reason == REASON_NETWORK) markWorkerStoppedNetwork(context)
            else markWorkerStoppedApi(context)
            NotificationHelper.checkDeviceOnlineStatus(context, isOnline = false)
            if (!wasStopped) BaseWifiWidget.renderAllWidgets(context)
        }

        /**
         * TCP 探活当前配置的设备，供轻量链路区分「没连上」与「配置错了」。
         *
         * 端口取数据源自报的心跳端口，与 [doWork] 步骤 1 完全一致。
         */
        fun isDeviceReachable(context: Context): Boolean = pingDevice(
            SPUtil.getDeviceHost(context),
            DeviceDataSourceRegistry.current(context).probePort(context)
        )

        /**
         * 轻量探测成功上报：清除共享失败状态。
         * 仅在确实有残留状态时才写入并刷新小组件，避免每个周期的无谓开销。
         */
        fun reportProbeSuccess(context: Context) {
            if (!SPUtil.isWorkerStopped(context) && SPUtil.getApiFailureCount(context) == 0) return
            resetFailureState(context)
            BaseWifiWidget.renderAllWidgets(context)
        }

        /**
         * 采集一轮并发布结果：写 SP、记流量、清失败状态、查通知阈值、刷小组件。
         *
         * [NotificationMonitor][com.ufi_axis_widget.util.NotificationMonitor] 与
         * [doWork] 共用这一条链路。原先 NotificationMonitor 走的是
         * `fetchNotificationBaseInfo` 轻量链路：只把数据读出来查阈值，既不写 SP
         * 也不刷小组件。于是后台每隔几分钟都在真实轮询设备、通知也照发，
         * 桌面上的「更新时间」却一直停在最后一次 Worker 跑完的时刻。
         *
         * 相比轻量链路只多一个局域网 GET（信号维度）和一次 SP 批量写，
         * 唤醒次数不变 —— 耗电量级不变。真正的重活是 [render]（重建 RemoteViews
         * 与 Bitmap、跨进程 IPC、磁贴更新），高频调用方应自行节流后传 false。
         *
         * 调用方负责守卫判断与失败计数（见 [reportProbeFailure]）。
         *
         * @param render 本轮是否刷新小组件。`update_time` 每轮都会变、会进数据哈希，
         *   渲染去重挡不住，所以高频轮询必须在这里节流而不是指望去重。
         */
        suspend fun collectAndPublish(ctx: Context, render: Boolean = true): CollectOutcome {
            val source = DeviceDataSourceRegistry.current(ctx)
            val data = source.getWifiData(ctx) ?: return CollectOutcome.NO_DATA

            // 基本合理性校验：防止坏数据写入 SP 和流量历史
            if (data.dailyRawBytes < 0 || data.monthlyRawBytes < 0) {
                DebugLogger.e(TAG, "collectAndPublish: 流量为负，丢弃本轮 " +
                    "(daily=${data.dailyRawBytes}, monthly=${data.monthlyRawBytes})")
                return CollectOutcome.DIRTY_DATA
            }

            SPUtil.saveData(ctx, data)

            // 记录流量数据到 Room（零额外网络开销，数据已在 data 中）
            // 数据源没有日流量字段时（如 goform 只有月累计）不跳过记录，
            // 改让 TrafficRecordManager 用月累计跨天做差推导今日用量
            val deriveDaily = !source.capabilities.dailyTraffic
            TrafficRecordManager.saveRecord(
                ctx, data.dailyRawBytes, data.monthlyRawBytes, deriveDaily = deriveDaily
            )
            // 通知链路拿到的是数据源直给的字符串，只有月累计的源那里是 "--"，
            // 换成推导值，「今日流量」阈值才能真正触发
            val dailyFlowForNotify =
                if (deriveDaily) formatFlow(
                    TrafficRecordManager.currentDailyUsageBytes(ctx, data.monthlyRawBytes)
                ) else data.dailyFlow

            // 采集成功 → 清除残留失败状态。只在确实有残留时写，
            // 否则每轮都白写一次 SP。必须排在渲染之前，不然小组件会先画出
            // 一份带「已暂停」的旧状态。
            if (SPUtil.isWorkerStopped(ctx) || SPUtil.getApiFailureCount(ctx) != 0) {
                resetFailureState(ctx)
            }
            SPUtil.setReconnecting(ctx, false)

            // 后台通知检测（仅系统通知栏，不显示应用内 Toast）
            NotificationHelper.checkAndNotify(
                context = ctx,
                dailyFlowStr = dailyFlowForNotify,
                monthlyFlowStr = data.flow,
                tempStr = MainDialogHelper.getHighestTemp(data),
                cpuStr = data.cpu,
                memStr = data.mem,
                batteryPercent = data.batteryPercent,
                isDeviceOnline = true,
                activity = null
            )
            // 渲染是本函数里最重的一步，高频轮询链路会自行节流后传 false
            if (render) BaseWifiWidget.renderAllWidgets(ctx)
            return CollectOutcome.PUBLISHED
        }

        /**
         * TCP ping 设备 IP:端口，检测网络是否可达。
         * 首次握手失败后立即重试一次，避免单次瞬时抖动被判为不可达。
         */
        private fun pingDevice(ip: String, port: Int): Boolean {
            if (tryConnect(ip, port)) return true
            Log.d(TAG, "Ping $ip:$port retrying once")
            return tryConnect(ip, port)
        }

        private fun tryConnect(ip: String, port: Int): Boolean {
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), PING_TIMEOUT_MS)
                    Log.d(TAG, "Ping $ip:$port OK")
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ping $ip:$port FAILED: ${e.message}")
                false
            }
        }

    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext

        // ====== 步骤 0：指定 Wi-Fi 守卫 ======
        // 不在白名单 Wi-Fi 上时直接放弃本轮：不请求、不累计失败计数、不发离线通知。
        // 必须清掉 reconnecting，否则小组件会停在「正在重试...」不动。
        val guard = WifiGuard.evaluate(ctx)
        if (!guard.allowed) {
            SPUtil.setReconnecting(ctx, false)
            DebugLogger.d(TAG, "doWork: skipped by Wi-Fi guard ($guard)")
            BaseWifiWidget.renderAllWidgets(ctx, force = true)
            return@withContext Result.success()
        }

        val host = SPUtil.getDeviceHost(ctx)

        // 心跳端口由数据源自报：goform 走后台端口，UFI-TOOLS 走 device_address 里的端口。
        // 若统一用后者，goform 模式会 ping 到关闭的 2333 端口而被误判离线。
        val port = DeviceDataSourceRegistry.current(ctx).probePort(ctx)

        val prevStopped = SPUtil.isWorkerStopped(ctx)
        val prevApiFails = SPUtil.getApiFailureCount(ctx)
        val prevNetFails = SPUtil.getNetworkFailureCount(ctx)
        DebugLogger.d(TAG, "doWork() started: addr=$host:$port, prevStopped=$prevStopped, apiFails=$prevApiFails, netFails=$prevNetFails")

        // ====== 步骤 1：每次运行都先 ping 检测网络可达性 ======
        // 即使之前被 stopped，也要尝试 ping — 这样设备恢复后能自动感知
        val pingOk = pingDevice(host, port)
        DebugLogger.d(TAG, "doWork: ping $host:$port = $pingOk")

        if (!pingOk) {
            // （原子递增，避免与前台竞态）
            val networkFails = incrementNetworkFailureCount(ctx)
            DebugLogger.w(TAG, "doWork: network unreachable ($networkFails/$NETWORK_MAX_FAILURES)")

            Log.w(TAG, "Device unreachable ($networkFails/$NETWORK_MAX_FAILURES)")

            if (networkFails >= NETWORK_MAX_FAILURES) {
                // 连续多次网络不通 → 标记 stopped（UI 展示用），但返回 retry 让 WorkManager 继续调度
                // 使用 retry 而非 failure：failure 会导致 PeriodicWorkRequest 永久停止，
                // 用户不开 App 就无法恢复；retry 则下一次调度时 ping 逻辑可自动检测设备恢复。
                markWorkerStoppedNetwork(ctx)
                SPUtil.setReconnecting(ctx, false)
                // 达到阈值才判定离线：单次 ping 抖动不发通知
                NotificationHelper.checkDeviceOnlineStatus(ctx, isOnline = false)
                DebugLogger.e(TAG, "doWork: NETWORK threshold reached, setting stopped=true, reason=network (retry)")
                BaseWifiWidget.renderAllWidgets(ctx)
                DebugLogger.flushToFile()
                Log.w(TAG, "Network unreachable threshold reached, worker will retry on next interval")
                return@withContext Result.retry()
            }

            // 还没达到阈值 → 继续重试（WorkManager 下次调度再试）
            // 不清除 API 计数 — 网络问题优先，等网络通了再试 API
            DebugLogger.flushToFile()
            return@withContext Result.retry()
        }

        // ====== 步骤 2：ping 通过 → 网络恢复了 ======
        // 重置网络失败计数（网络恢复了）
        resetNetworkFailCount(ctx)
        DebugLogger.d(TAG, "doWork: ping OK, reset network fail count")

        // 如果之前因网络问题被 stopped，此时自动解除 stopped 状态
        val wasNetworkStopped = SPUtil.isWorkerStopped(ctx) &&
                SPUtil.getWorkerStopReason(ctx) == REASON_NETWORK
        if (wasNetworkStopped) {
            resetFailureState(ctx)
            DebugLogger.i(TAG, "doWork: network recovered from stopped, auto-resuming (api failure count also reset)")
            Log.d(TAG, "Network recovered, auto-resuming worker")
            BaseWifiWidget.renderAllWidgets(ctx) // 更新小组件路由器图标
        }

        // ====== 步骤 3：网络可达，尝试 API 请求 ======
        val apiFails = SPUtil.getApiFailureCount(ctx)
        DebugLogger.d(TAG, "doWork: trying API fetch, current apiFails=$apiFails")

        // 设备应答了但数据不合理 —— 属于"脏数据"而非"设备离线"，不能触发断线告警
        var dataInvalid = false

        try {
            when (collectAndPublish(ctx)) {
                CollectOutcome.PUBLISHED -> {
                    DebugLogger.i(TAG, "doWork: API success, all failure states cleared")
                    DebugLogger.flushToFile()
                    Log.d(TAG, "Data fetch succeeded, all failure states cleared")
                    return@withContext Result.success()
                }
                // 设备在应答、只是数值不可信 —— 落入下方失败处理但不判离线
                CollectOutcome.DIRTY_DATA -> dataInvalid = true
                CollectOutcome.NO_DATA -> Unit
            }
        } catch (e: CancellationException) {
            throw e  // 协程取消必须传播，不能被 catch(Exception) 吞掉
        } catch (e: Exception) {
            DebugLogger.e(TAG, "doWork: API exception: ${e.message}")
            Log.e(TAG, "Data fetch exception: ${e.message}", e)
        }

        // API 请求失败（data == null 或异常）（原子递增，避免与前台竞态）
        val newApiFails = incrementApiFailureCount(ctx)
        val sourceError = DeviceDataSourceRegistry.current(ctx).lastError
        DebugLogger.w(TAG, "doWork: API failed ($newApiFails/$API_MAX_FAILURES), " +
            "dataInvalid=$dataInvalid, lastError=$sourceError")

        Log.w(TAG, "API fetch failed ($newApiFails/$API_MAX_FAILURES)")

        if (newApiFails >= API_MAX_FAILURES) {
            // 连续 API 失败达到阈值 → 标记 stopped（UI 展示用），但返回 retry 让 WorkManager 继续调度
            // 使用 retry 而非 failure：failure 会导致 PeriodicWorkRequest 永久停止，
            // 用户不开 App 就无法恢复；retry 则下一次调度时 API 请求可自动重试。
            markWorkerStoppedApi(ctx)
            SPUtil.setReconnecting(ctx, false)
            // 达到阈值才判定离线；脏数据说明设备其实在应答，不算离线
            if (!dataInvalid) {
                NotificationHelper.checkDeviceOnlineStatus(ctx, isOnline = false)
            }
            DebugLogger.e(TAG, "doWork: API threshold reached, setting stopped=true, reason=api (retry)")
            DebugLogger.flushToFile()
            BaseWifiWidget.renderAllWidgets(ctx)
            Log.w(TAG, "API failure threshold reached, worker will retry on next interval")
            return@withContext Result.retry()
        }

        // 还有重试配额 → 让 WorkManager 调度下次运行
        DebugLogger.flushToFile()
        Result.retry()
    }
}
