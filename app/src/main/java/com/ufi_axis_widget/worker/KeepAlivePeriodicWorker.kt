package com.ufi_axis_widget.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.NotificationHelper
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.WifiGuard
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry
import kotlinx.coroutines.CancellationException

/**
 * WorkManager 周期性保活 Worker。
 *
 * 作为 NotificationMonitor（进程内协程）的补充：
 * - NotificationMonitor 随进程死亡而停止
 * - 本 Worker 由 WorkManager 调度，进程死亡后仍可重新唤醒
 *
 * 每次执行时：
 * 1. 调用轻量 API 获取设备状态
 * 2. 检查通知阈值并触发通知
 *
 * 最小周期：15 分钟（WorkManager 限制）。
 */
class KeepAlivePeriodicWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "KeepAliveWorker"
        const val WORK_NAME = "keep_alive_periodic"
    }

    override suspend fun doWork(): Result {
        if (!SPUtil.getNotificationEnabled(applicationContext)) {
            DebugLogger.logSys(TAG, "Notifications disabled, skipping")
            return Result.success()
        }

        return try {
            // 采集准入守卫：用 evaluateForNotify —— 本 Worker 只服务于阈值告警，
            // 不参与小组件渲染，所以息屏/省电暂停不该拦它（阈值告警的价值恰恰在于
            // 用户没看屏幕时也能收到）。网络维度仍然生效。
            val guard = WifiGuard.evaluateForNotify(applicationContext)
            if (!guard.allowed) {
                DebugLogger.d(TAG, "doWork: skipped by guard ($guard)")
                return Result.success()
            }
            val info = DeviceDataSourceRegistry.current(applicationContext)
                .fetchNotificationBaseInfo(applicationContext)

            if (info != null) {
                // 成功获取到数据 → 设备在线，清除共享失败状态
                WifiWorker.reportProbeSuccess(applicationContext)
                NotificationHelper.checkAndNotify(
                    context = applicationContext,
                    dailyFlowStr = info.dailyFlowStr,
                    monthlyFlowStr = info.monthlyFlowStr,
                    tempStr = info.tempStr,
                    cpuStr = info.cpuStr,
                    memStr = info.memStr,
                    batteryPercent = info.batteryPercent,
                    isDeviceOnline = true
                )
                DebugLogger.logSys(TAG, "Periodic check completed successfully")
                Result.success()
            } else {
                DebugLogger.w(TAG, "Failed to fetch device info")
                // 单次失败不判定离线：累计共享失败计数，达到阈值才告警。
                // 原因由 ping 判定：写死成 api 会把「设备没连上」说成「配置错了」
                reportProbeFailureWithReason()
                Result.retry()
            }
        } catch (e: CancellationException) {
            throw e  // 协程取消必须传播，不能被 catch(Exception) 吞掉
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Periodic worker error: ${e.message}")
            reportProbeFailureWithReason()
            Result.retry()
        }
    }

    /**
     * 按 ping 结果决定停机原因后上报失败。
     *
     * 本 Worker 只做轻量采集、不自己 ping，若把原因写死成 api，界面会在纯断网
     * 场景下提示「端口/Token/认证配置错误」，把用户引到根本没错的配置上。
     */
    private fun reportProbeFailureWithReason() {
        val reason =
            if (WifiWorker.isDeviceReachable(applicationContext)) WifiWorker.REASON_API
            else WifiWorker.REASON_NETWORK
        WifiWorker.reportProbeFailure(applicationContext, reason)
    }
}
