package com.ufi_axis_widget.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ufi_axis_widget.widget.BaseWifiWidget

/**
 * 守卫恢复触发器。
 *
 * [WifiGuard] 只做「拦」：条件不满足时跳过本轮采集。问题是恢复的那一刻没人催 ——
 * 刚连上设备热点、刚亮屏、刚关掉省电模式，都得干等到下一个 15 分钟周期，
 * 小组件上还挂着一份旧数据加一句「已暂停刷新」。本类负责在这些**状态翻转的瞬间**
 * 补触发一次采集，让「暂停 + 恢复即刷」在体感上等价于「降频」。
 *
 * 监听三类系统事件，全部与数据源无关，对任何数据源同等生效：
 * - 默认网络能力变化（连上/切换 Wi-Fi）
 * - 屏幕点亮 / 熄灭
 * - 系统省电模式开关
 *
 * ## 已知降级：进程被杀后收不到事件
 *
 * 广播与网络回调都注册在 [com.ufi_axis_widget.UfiAxisApplication] 里，进程被系统回收后
 * 自然失效。**这不会让功能出错**：[WifiGuard.evaluate] 是每次现场同步判断的，
 * 亮屏 / 连上白名单之后的第一个 Worker 周期一定会放行，只是「立即」退化成
 * 「最多等一个周期」。所以不要为此加保活 —— 收益是几分钟的时效，代价是常驻进程。
 */
object RefreshTrigger {

    private const val TAG = "RefreshTrigger"

    /**
     * 触发防抖窗口。
     *
     * 一次网络切换会连续打出多个回调（可用 → 能力变化 → 已验证），亮屏也可能与
     * 网络恢复挤在一起。没有防抖就会在几秒内连发好几次采集，比不做这个功能还费电。
     */
    private const val DEBOUNCE_MS = 30_000L

    /** SSID 就绪延迟：网络刚可用时 DHCP/关联可能未完成，此时读 SSID 会拿到 null */
    private const val SSID_SETTLE_DELAY_MS = 1_500L

    @Volatile
    private var lastTriggerAt = 0L

    @Volatile
    private var installed = false

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 安装监听。幂等，重复调用只生效一次。
     *
     * 注意：**不检查任何开关**。三个事件源都很便宜（系统回调，无轮询），
     * 而且「连上白名单 Wi-Fi 立即刷新」在守卫全关时同样有价值 ——
     * 关掉指定 Wi-Fi 功能的用户，连上任意网络后也希望马上看到数据。
     * 真正的准入判断统一交给 [WifiGuard.evaluate]。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appCtx = context.applicationContext
        registerNetworkCallback(appCtx)
        registerSystemReceivers(appCtx)
        DebugLogger.logSys(TAG, "已安装守卫恢复触发器（网络 / 屏幕 / 省电模式）")
    }

    // ══════════════════════════════════════════════
    // 网络
    // ══════════════════════════════════════════════

    private fun registerNetworkCallback(appCtx: Context) {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // 只认「已验证可用」的网络：onAvailable 阶段链路往往还没通，
                    // 此时发请求必然失败，还会白占防抖窗口。
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                    // 延迟一下再判：刚关联上时 WifiManager 还读不到 SSID，
                    // 立刻 evaluate 会判成 BLOCKED_SSID_UNREADABLE，白白错过这次补刷。
                    handler.postDelayed({ tryTrigger(appCtx, "网络已就绪") }, SSID_SETTLE_DELAY_MS)
                }

                override fun onLost(network: Network) {
                    // 断网时守卫状态翻转为 blocked，重渲染让暂停原因显示出来，
                    // 否则用户看到的是一份不带说明的旧数据
                    renderOnly(appCtx, "网络断开")
                }
            })
        } catch (e: Exception) {
            DebugLogger.w(TAG, "registerDefaultNetworkCallback 失败: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════
    // 屏幕 / 省电模式
    // ══════════════════════════════════════════════

    /** 省电模式变化广播的 action，常量未公开导出，只能写字面量 */
    private const val ACTION_POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED"

    private fun registerSystemReceivers(appCtx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> tryTrigger(appCtx, "屏幕点亮")
                    Intent.ACTION_SCREEN_OFF -> renderOnly(appCtx, "屏幕关闭")
                    ACTION_POWER_SAVE_MODE_CHANGED -> {
                        // 开启省电时翻转为 blocked（只需重渲染），关闭时翻转为 allowed（需要补刷）
                        if (WifiGuard.isPowerSaveMode(appCtx)) renderOnly(appCtx, "省电模式开启")
                        else tryTrigger(appCtx, "省电模式关闭")
                    }
                }
            }
        }
        // SCREEN_ON / SCREEN_OFF 只能动态注册，清单里声明收不到
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            appCtx.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "registerReceiver 失败: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════
    // 触发
    // ══════════════════════════════════════════════

    /**
     * 带防抖地请求一次立即采集。
     *
     * 守卫判断不在这里做 —— 交给 [BaseWifiWidget.requestImmediateRefresh]，
     * 保证「所有采集入口都过同一个守卫」这条规则只有一处实现。
     */
    private fun tryTrigger(appCtx: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < DEBOUNCE_MS) {
            DebugLogger.d(TAG, "$reason → 防抖窗口内，跳过")
            return
        }
        lastTriggerAt = now
        BaseWifiWidget.requestImmediateRefresh(appCtx, reason)
    }

    /** 只重渲染不采集：用于「条件刚变成不满足」的场景，让暂停原因立刻显示出来 */
    private fun renderOnly(appCtx: Context, reason: String) {
        DebugLogger.d(TAG, "$reason → 仅重渲染小组件")
        try {
            BaseWifiWidget.renderAllWidgets(appCtx, force = true)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "renderOnly 失败: ${e.message}")
        }
    }
}
