package com.ufi_axis_widget.util

import android.content.Context
import android.os.SystemClock
import com.ufi_axis_widget.worker.CollectOutcome
import com.ufi_axis_widget.worker.WifiWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台通知监控器。
 *
 * 独立于 MainActivity 和 WifiWorker，在应用生命周期内持续运行。
 * 周期性地采集设备数据、检查通知阈值并刷新小组件，
 * 当超出阈值时通过系统通知栏推送提醒。
 *
 * 采集与发布链路完全复用 [WifiWorker.collectAndPublish]，与 Worker 同一套逻辑，
 * 区别只在触发时机：本类由进程内协程按 [SPUtil.getMonitorIntervalSec] 轮询，
 * 实时性远高于受 Doze 限制的 WorkManager 周期。
 *
 * 在 [UfiAxisApplication.onCreate] 中启动。
 */
object NotificationMonitor {

    private const val TAG = "NotificationMonitor"

    /**
     * 小组件渲染节流窗口。
     *
     * 轮询间隔最低可设到 15 秒，而渲染一次要重建所有形态的 RemoteViews/Bitmap
     * 并做跨进程 IPC。「更新时间」精度只到分钟，每 15 秒重画一次纯属白耗电。
     */
    private const val MIN_RENDER_INTERVAL_MS = 60_000L

    /**
     * 判定离线前的宽限时长。
     *
     * [WifiWorker.API_MAX_FAILURES] 那个「连续 3 次」是按 15 分钟 Worker 周期设的
     * 容错额度；本类最快 15 秒一轮，直接共用会让设备重启、换信道、Wi-Fi 休眠这类
     * 一分钟内就恢复的抖动被判成离线。所以本链路先按「连续失败已持续多久」过一道，
     * 过了宽限期才开始往共享计数里累加。
     */
    private const val OFFLINE_GRACE_MS = 3 * 60 * 1000L

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oneShotJobs = mutableListOf<Job>()

    /** 上次真正刷新小组件的时刻（elapsedRealtime），渲染节流用 */
    @Volatile
    private var lastRenderAt = 0L

    /** 本轮连续失败的起始时刻（elapsedRealtime），0 表示当前没在失败中 */
    @Volatile
    private var firstFailureAt = 0L

    /**
     * 获取当前设置的监控间隔（毫秒）。
     * 从 SP 动态读取，支持用户在通知管理界面实时调整。
     */
    private fun getIntervalMs(context: Context): Long {
        val sec = SPUtil.getMonitorIntervalSec(context).coerceIn(15, 600)
        return sec * 1000L
    }

    /**
     * 启动后台通知监控。
     * 在 Application.onCreate() 中调用，应用进程存活期间持续运行。
     *
     * @param context 上下文（建议传 ApplicationContext）
     */
    fun start(context: Context) {
        if (job?.isActive == true) {
            DebugLogger.d(TAG, "start: already running, skip")
            return
        }
        val appCtx = context.applicationContext
        job = scope.launch {
            val initialInterval = getIntervalMs(appCtx)
            DebugLogger.i(TAG, "NotificationMonitor started, interval=${initialInterval}ms")
            // 首次启动时立即执行一次检查，无需等待
            performCheck(appCtx)

            while (isActive) {
                // 每次循环都重新读取间隔，支持用户实时调整
                val intervalMs = getIntervalMs(appCtx)
                delay(intervalMs)
                performCheck(appCtx)
            }
        }
    }

    /**
     * 停止后台通知监控。
     */
    fun stop() {
        job?.cancel()
        job = null
        synchronized(oneShotJobs) {
            oneShotJobs.forEach { it.cancel() }
            oneShotJobs.clear()
        }
        DebugLogger.d(TAG, "NotificationMonitor stopped")
    }

    /**
     * 执行一次性通知检查（供 AlarmReceiver 调用）。
     *
     * 在 IO 调度器上启动协程，执行与常规轮询相同的通知检查逻辑。
     * 检查完成后回调 [onComplete]，典型用法是释放 WakeLock 并调度下一次闹钟。
     *
     * @param context 上下文（建议传 ApplicationContext）
     * @param onComplete 检查完成后的回调（无论成功或失败）
     */
    fun performOneShotCheck(context: Context, onComplete: () -> Unit) {
        // LAZY + 先注册后启动：协程体绝不可能早于 add() 执行。
        // 直接 scope.launch 时协程可能在 launch 返回前就跑完 finally，那时外部变量
        // 还没赋值，remove 拿到 null 什么都没删，紧接着 add 又把这个已结束的 Job
        // 塞进列表 —— 闹钟每几分钟触发一次，列表只增不减。
        var job: Job? = null
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                performCheck(context)
            } catch (e: CancellationException) {
                throw e  // 取消必须传播，否则 stop() 会被当成一次探测失败
            } catch (e: Exception) {
                DebugLogger.e(TAG, "OneShot check failed: ${e.message}")
            } finally {
                synchronized(oneShotJobs) { oneShotJobs.remove(job) }
                onComplete()
            }
        }
        synchronized(oneShotJobs) { oneShotJobs.add(job) }
        job.start()
    }

    /**
     * 执行一次完整采集 + 通知检查。
     */
    private suspend fun performCheck(context: Context) {
        // 采集准入守卫：通知专用判定 —— 不看屏幕与省电模式，只看网络。
        // 息屏时停止阈值检查等于把「流量超额提醒」废掉，那不是省电，是功能缺失。
        val guard = WifiGuard.evaluateForNotify(context)
        if (!guard.allowed) {
            DebugLogger.d(TAG, "performCheck: skipped by guard ($guard)")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 采集很轻（4 个局域网 GET + 一次 SP 写），渲染才是重活：重建 RemoteViews
        // 与 Bitmap、跨进程 IPC。而 update_time 每轮都变且进数据哈希，
        // renderAllWidgets 自己的去重完全挡不住，只能在这里按时间节流。
        val allowRender = now - lastRenderAt >= MIN_RENDER_INTERVAL_MS
        try {
            // 走完整采集而非轻量链路：轻量链路只读数据查阈值，不写 SP 也不刷小组件，
            // 于是后台每轮都在真实轮询设备，桌面上的「更新时间」却停在最后一次
            // Worker 跑完的时刻。
            when (WifiWorker.collectAndPublish(context, render = allowRender)) {
                CollectOutcome.PUBLISHED -> {
                    if (allowRender) lastRenderAt = now
                    firstFailureAt = 0L
                }
                // 设备没应答才计入失败；脏数据说明设备其实在线，不能拿去凑离线阈值
                CollectOutcome.NO_DATA -> reportFailureIfPastGrace(context, now)
                CollectOutcome.DIRTY_DATA ->
                    DebugLogger.w(TAG, "performCheck: 本轮数据不可信，已丢弃")
            }
        } catch (e: CancellationException) {
            throw e  // 取消不是探测失败，吞掉会把一次正常停止累计成设备离线
        } catch (e: Exception) {
            DebugLogger.e(TAG, "performCheck failed: ${e.message}")
            reportFailureIfPastGrace(context, now)
        }
    }

    /**
     * 连续失败持续超过 [OFFLINE_GRACE_MS] 才上报给共享失败计数。
     *
     * 上报时用 ping 结果决定停机原因：ping 通说明设备在线、问题在端口/Token/认证；
     * ping 不通就是真的没连上。不区分的话界面会把「设备没连上」说成「配置错了」，
     * 引导用户去改根本没错的配置。
     */
    private fun reportFailureIfPastGrace(context: Context, now: Long) {
        if (firstFailureAt == 0L) {
            firstFailureAt = now
            DebugLogger.d(TAG, "performCheck: 首次失败，进入 ${OFFLINE_GRACE_MS / 1000}s 宽限期")
            return
        }
        if (now - firstFailureAt < OFFLINE_GRACE_MS) return
        val reason =
            if (WifiWorker.isDeviceReachable(context)) WifiWorker.REASON_API
            else WifiWorker.REASON_NETWORK
        WifiWorker.reportProbeFailure(context, reason)
    }
}
