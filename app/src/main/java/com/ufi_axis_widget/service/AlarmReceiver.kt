package com.ufi_axis_widget.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.NotificationMonitor
import com.ufi_axis_widget.util.SPUtil

/**
 * 精确定时器接收器：穿透 Doze 模式，确保后台通知检查不会因 CPU 休眠而中断。
 *
 * 使用 [AlarmManager.setAlarmClock] 设置闹钟，这是 Android 唯一不受
 * Doze / App Standby 任何限制的 API，能保证精确触发且无频率上限。
 *
 * 与 [NotificationMonitor]（协程轮询）和 WorkManager（周期任务）互补：
 * - NotificationMonitor 提供最快 15s 轮询，但进程死亡即失效
 * - WorkManager 持久但受 Doze 延迟（最小 15min + 维护窗口）
 * - AlarmReceiver 填补空白：持久化 + Doze 完全穿透 + 可调间隔
 *
 * 进程死亡自动恢复：
 * - 当用户开启自动恢复功能时，闹钟触发后检测前台服务是否存活
 * - 若进程已被杀死，自动重启 BackgroundMonitorService 和 NotificationMonitor
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val ACTION_CHECK = "com.ufi_axis_widget.ACTION_ALARM_CHECK"
        private const val REQUEST_CODE = 8472

        /**
         * 最小闹钟间隔（毫秒）。
         * setAlarmClock 不受 Doze 频率限制，5 分钟可保证及时性。
         */
        private const val MIN_ALARM_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * 创建一个新的 WakeLock 供调用方持有。
         * 每次 onReceive 创建独立锁，避免并发闹钟触发时互相覆盖。
         */
        private fun createWakeLock(context: Context): PowerManager.WakeLock {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UFI-AXIS:AlarmCheckLock"
            )
            lock.setReferenceCounted(false)
            lock.acquire(30_000L) // 30 秒超时保护
            return lock
        }

        /** 释放指定的 WakeLock */
        private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
            try {
                if (lock != null && lock.isHeld) lock.release()
            } catch (_: Exception) { }
        }

        /**
         * 调度下一次闹钟。
         * 可在 Application.onCreate()、BootReceiver、前台服务等处调用。
         * 仅当通知功能开启时生效。
         */
        fun scheduleNext(context: Context) {
            val appCtx = context.applicationContext
            if (!SPUtil.getNotificationEnabled(appCtx)) {
                DebugLogger.d(TAG, "Notifications disabled, skip alarm scheduling")
                return
            }

            val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // 计算触发时间：基于用户设置的监控间隔，但不低于最小值
            val monitorSec = SPUtil.getMonitorIntervalSec(appCtx).toLong().coerceIn(15, 600)
            val intervalMs = (monitorSec * 1000L).coerceAtLeast(MIN_ALARM_INTERVAL_MS)
            // 两套时间基准不能混用：setAlarmClock 收的是 RTC 墙上时钟，
            // 下面的降级分支用的是 ELAPSED_REALTIME_WAKEUP（开机以来毫秒）。
            // 把 elapsedRealtime 喂给 setAlarmClock 等于传了一个早已过去的时刻，
            // 系统会立刻触发 —— 表现就是「日志写着排 300 秒，实际每几秒响一次」。
            val triggerAtRtcMs = System.currentTimeMillis() + intervalMs
            val triggerAtElapsedMs = SystemClock.elapsedRealtime() + intervalMs

            val intent = Intent(appCtx, AlarmReceiver::class.java).apply {
                action = ACTION_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appCtx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 优先使用 setAlarmClock —— 唯一不受 Doze 任何限制的 API
            // showIntent 传 null：不显示状态栏闹钟图标，不影响穿透能力
            try {
                val alarmInfo = AlarmManager.AlarmClockInfo(triggerAtRtcMs, null)
                alarmManager.setAlarmClock(alarmInfo, pendingIntent)
                DebugLogger.d(TAG, "AlarmClock scheduled in ${intervalMs / 1000}s")
                return
            } catch (e: SecurityException) {
                DebugLogger.w(TAG, "setAlarmClock denied: ${e.message}, trying setExact")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "setAlarmClock failed: ${e.message}, trying setExact")
            }

            // 降级 1：精确闹钟（Doze 期间约 9 分钟一次上限）
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (!alarmManager.canScheduleExactAlarms()) {
                        DebugLogger.w(TAG, "SCHEDULE_EXACT_ALARM not granted, fallback to inexact")
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pendingIntent
                        )
                        return
                    }
                }
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pendingIntent
                )
                DebugLogger.d(TAG, "Exact alarm scheduled in ${intervalMs / 1000}s")
            } catch (e: Exception) {
                // 降级 2：非精确闹钟（仍可工作，时间精度更低）
                DebugLogger.w(TAG, "Exact alarm failed: ${e.message}, fallback to inexact")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pendingIntent
                )
            }
        }

        /** 取消已调度的闹钟 */
        fun cancel(context: Context) {
            val alarmManager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            DebugLogger.d(TAG, "Alarm cancelled")
        }

    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return

        DebugLogger.d(TAG, "Alarm fired, acquiring WakeLock")
        val localLock = AlarmReceiver.createWakeLock(context)
        val appCtx = context.applicationContext

        // goAsync() 让系统在异步工作完成前不要回收本进程。
        // 不加的话 onReceive 一返回进程就可能被杀，performOneShotCheck 的回调
        // 永远不会执行——WakeLock 泄漏到超时，而且下一次闹钟再也没人排程，
        // 整条 Doze 穿透链就此断掉，只能靠开机/更新广播恢复。
        val pending = goAsync()
        var handedOff = false

        try {
            // 通知功能关闭时不再链式调度
            if (!SPUtil.getNotificationEnabled(appCtx)) {
                DebugLogger.d(TAG, "Notifications disabled, skip")
                return
            }

            // 先把下一次闹钟排上，再做本次检查：链条不能依赖本次检查是否成功。
            // 同一个 REQUEST_CODE + FLAG_UPDATE_CURRENT，重复排程只会互相覆盖而非叠加。
            AlarmReceiver.scheduleNext(appCtx)

            // ─── 进程死亡自动恢复 ───
            // 用户开启自动恢复时，检测前台服务是否存活，若进程已被杀死则自动重启
            if (SPUtil.getAutoRecoverEnabled(appCtx)
                && SPUtil.getBackgroundServiceEnabled(appCtx)) {
                try {
                    // 重新调度 WorkManager 周期性保活任务（如果启用）
                    com.ufi_axis_widget.BackgroundKeepAliveActivity
                        .schedulePeriodicWorkerIfEnabled(appCtx)

                    // 重启前台保活服务
                    BackgroundMonitorService.start(appCtx)

                    // 重启 NotificationMonitor 协程轮询
                    NotificationMonitor.start(appCtx)

                    DebugLogger.d(TAG, "Auto-recovery: services restarted")
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "Auto-recovery failed: ${e.message}")
                }
            }

            // 使用 NotificationMonitor 的公开方法执行一次性检查
            // 内部使用 Dispatchers.IO，检查完成后释放 WakeLock 并结束 goAsync
            NotificationMonitor.performOneShotCheck(appCtx) {
                AlarmReceiver.releaseWakeLock(localLock)
                try { pending.finish() } catch (_: Exception) {}
            }
            handedOff = true
        } finally {
            // 没能移交给异步回调（提前 return 或抛异常）时兜底收尾，
            // 否则 WakeLock 会一直持有到 30s 超时、PendingResult 也不会释放。
            if (!handedOff) {
                AlarmReceiver.releaseWakeLock(localLock)
                try { pending.finish() } catch (_: Exception) {}
            }
        }
    }
}
