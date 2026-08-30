package com.ufi_axis_widget

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.ufi_axis_widget.service.AlarmReceiver
import com.ufi_axis_widget.service.BackgroundMonitorService
import com.ufi_axis_widget.util.AlertHistoryManager
import com.ufi_axis_widget.util.CrashHandler
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.NotificationHelper
import com.ufi_axis_widget.util.NotificationMonitor
import com.ufi_axis_widget.util.RefreshTrigger
import com.ufi_axis_widget.util.TrafficRecordManager
import com.ufi_axis_widget.util.WidgetBitmapCache
import com.ufi_axis_widget.util.widget.WidgetPrefs

/**
 * 全局 Application 入口。
 *
 * 在 onCreate 中调用 [DynamicColors.applyToActivitiesIfAvailable]，
 * 为所有 Activity 注册 Material You 动态配色（Android 12+ / API 31）。
 * 这使得 [DynamicColors.wrapContextIfAvailable] 能正确返回壁纸派生色调，
 * 供小组件 Palette 构建使用。
 *
 * 低于 API 31 的设备不受影响，方法内部自动跳过。
 */
class UfiAxisApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化全局崩溃捕获（必须在所有其他初始化之前，以捕获后续步骤的异常）
        CrashHandler.init(this)

        // 仅设置 DebugLogger 的 context 引用（轻量操作），使 CrashHandler 崩溃时
        // flushToFile() 可以工作。完整的 init()（含文件读取和系统信息采集）延迟到
        // Activity.onCreate() 中执行，避免阻塞 Application 启动。
        DebugLogger.setContextOnly(this)

        // 启用 Material You 动态配色：为所有 Activity 叠加动态色彩主题覆盖层。
        // 仅在 Android 12+ 且 OEM 提供动态配色时生效；低版本设备静默忽略。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        // 初始化通知渠道（Android 8.0+ 需要在应用启动时创建）
        NotificationHelper.init(this)

        // 初始化警报历史 Room 数据库（首次启动时自动从旧 SP 迁移）
        AlertHistoryManager.initDatabase(this)

        // 初始化流量记录 Room 数据库
        TrafficRecordManager.initDatabase(this)

        // 启动后台通知监控器：独立协程定时轻量检查阈值，
        // 不依赖任何 Activity，应用存活期间持续运行
        NotificationMonitor.start(this)

        // 同步前台保活服务状态：根据 SP 开关决定是否启动常驻服务
        try {
            BackgroundMonitorService.syncState(this)
        } catch (e: Exception) {
            DebugLogger.w("UfiAxisApp", "BackgroundMonitorService syncState failed: ${e.message}")
        }

        // 调度 Doze 穿透闹钟：确保 CPU 休眠后仍能执行通知检查
        try {
            AlarmReceiver.scheduleNext(this)
        } catch (e: Exception) {
            DebugLogger.w("UfiAxisApp", "AlarmReceiver scheduleNext failed: ${e.message}")
        }

        // 注册 WidgetBitmapCache 内存压力回调，系统内存不足时自动清理 Bitmap 缓存
        WidgetBitmapCache.register(this)

        // 把旧版全局显示项配置迁移到「类型层」作用域（幂等，跑过一次后直接返回）
        WidgetPrefs.migrateLegacyKeys(this)

        // 安装守卫恢复触发器：连上白名单 Wi-Fi / 亮屏 / 解锁 / 关掉省电模式的瞬间补刷一次，
        // 否则要干等到下一个刷新周期。进程被回收后失效属于已知降级，
        // 届时由周期任务兜底（守卫是现场判断的，不会卡在暂停态）。
        try {
            RefreshTrigger.install(this)
        } catch (e: Exception) {
            DebugLogger.w("UfiAxisApp", "RefreshTrigger install failed: ${e.message}")
        }
    }
}
