package com.ufi_axis_widget.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * 采集准入守卫。
 *
 * 解决的问题：手机连着别的 Wi-Fi 或用蜂窝数据时，设备本来就不可能连上，
 * 但采集链路仍会照常发起请求、累计失败计数、把小组件推进「加载中 / 连接失败」，
 * 既费电又造成误报。开启相应开关后，不满足条件时直接跳过——不请求、不计失败、不告警，
 * 小组件继续展示上次的缓存数据。
 *
 * 判定维度有三类，**全部与数据源无关**（依据只来自手机侧系统状态），
 * 所以是全局开关，不随数据源切换而变化：
 * - 屏幕状态（[SPUtil.getPauseOnScreenOff]）
 * - 系统省电模式（[SPUtil.getPauseOnPowerSave]）
 * - 指定 Wi-Fi 白名单（[SPUtil.getWifiLockEnabled]）
 *
 * ## 为什么是「暂停」而不是「降频」
 *
 * `PeriodicWorkRequest` 的间隔无法动态调整，改间隔只能重新入队，会重置计时并引入
 * 「谁负责改回去」的状态管理。所以这里统一是暂停语义（每次现场同步判断、零状态），
 * 靠 [RefreshTrigger] 在条件恢复的瞬间补触发一次采集，效果等价于降频。
 *
 * ## 关于 SSID 与位置权限
 *
 * 从 Android 10（API 29）起，读取当前 Wi-Fi 的 SSID 需要
 * [Manifest.permission.ACCESS_FINE_LOCATION] 且系统定位开关处于打开状态，
 * 否则系统一律返回 `<unknown ssid>`。这是平台强制的限制，没有免权限的等价替代
 * （BSSID 同样受限；网关 IP 在不同路由下大量重复，不足以区分）。
 *
 * 本模块的口径是**严格判定**：开启白名单后，除了「确认连在名单里的那个 Wi-Fi 上」，
 * 其余一切情形（蜂窝、断网、连了别的 Wi-Fi、读不到 SSID）都暂停刷新。
 *
 * 早期版本在读不到 SSID 时降级为「只要在 Wi-Fi 链路上就放行」，本意是别让缺权限的用户
 * 完全刷不动，实际效果却是功能静默失效 —— 用户以为已经限定了 Wi-Fi，小组件却在任何
 * 网络下照样发请求、照样转「加载中」。宁可明确暂停并告诉用户缺什么，也不要假装在工作。
 * 万一真被卡住，关掉开关本身就是逃生出口。
 */
object WifiGuard {

    private const val TAG = "WifiGuard"

    /** 采集准入判定结果 */
    enum class Decision {
        /** 功能未开启，或已连在白名单 Wi-Fi 上 —— 正常采集 */
        ALLOWED,

        /** 屏幕已关闭且用户开启了息屏暂停 —— 跳过采集 */
        BLOCKED_SCREEN_OFF,

        /** 系统省电模式已开启且用户开启了省电暂停 —— 跳过采集 */
        BLOCKED_POWER_SAVE,

        /** 完全不在 Wi-Fi 链路上（蜂窝数据 / 断网 / 飞行模式）—— 跳过采集 */
        BLOCKED_NOT_WIFI,

        /** 在 Wi-Fi 上但不是白名单里的那个 —— 跳过采集 */
        BLOCKED_WRONG_SSID,

        /** 在 Wi-Fi 上但读不到 SSID（无位置权限 / 定位关闭）—— 无法确认，跳过采集 */
        BLOCKED_SSID_UNREADABLE,

        /** 功能已开启但白名单是空的 —— 跳过采集，并提示用户去添加 */
        BLOCKED_NO_SSID_CONFIGURED;

        val allowed: Boolean get() = this == ALLOWED
    }

    /**
     * 采集准入的唯一判定入口（用于「为渲染而做的采集」）。
     *
     * 省电两项放在网络判定之前：它们是纯本地查询，比读网络能力更便宜，
     * 而且命中时可以直接短路，连 ConnectivityManager 都不用碰。
     */
    fun evaluate(context: Context): Decision {
        if (SPUtil.getPauseOnScreenOff(context) && !isScreenOn(context)) {
            return Decision.BLOCKED_SCREEN_OFF
        }
        if (SPUtil.getPauseOnPowerSave(context) && isPowerSaveMode(context)) {
            return Decision.BLOCKED_POWER_SAVE
        }
        return evaluateNetwork(context)
    }

    /**
     * 通知检查专用判定。
     *
     * 与 [evaluate] 的区别：**不看屏幕、不看省电模式**。
     * 阈值告警的价值恰恰在于用户没盯着屏幕的时候能收到，如果息屏就停止检查，
     * 「流量超额提醒」基本等于废掉。省电模式同理 —— 用户要的是少刷小组件，
     * 而不是不要告警。
     *
     * 网络维度仍然生效：不在白名单 Wi-Fi 上时设备根本连不上，请求纯属浪费。
     */
    fun evaluateForNotify(context: Context): Decision = evaluateNetwork(context)

    /** 网络维度判定，[evaluate] 与 [evaluateForNotify] 共用 */
    private fun evaluateNetwork(context: Context): Decision {
        if (!SPUtil.getWifiLockEnabled(context)) return Decision.ALLOWED

        if (!isOnWifi(context)) return Decision.BLOCKED_NOT_WIFI

        val allowList = SPUtil.getWifiLockSsids(context)
        if (allowList.isEmpty()) return Decision.BLOCKED_NO_SSID_CONFIGURED

        // 读不到 SSID 就无法确认是不是那个 Wi-Fi，此时放行等于让整个开关失效
        val ssid = currentSsid(context) ?: return Decision.BLOCKED_SSID_UNREADABLE

        return if (allowList.contains(ssid)) Decision.ALLOWED else Decision.BLOCKED_WRONG_SSID
    }

    /** 是否允许本次采集。等价于 `evaluate(context).allowed`，供调用点简写 */
    fun isRefreshAllowed(context: Context): Boolean = evaluate(context).allowed

    /** 给用户看的原因说明，用于小组件副标题 / 主界面提示 */
    fun blockedReason(decision: Decision): String = when (decision) {
        Decision.BLOCKED_SCREEN_OFF -> "屏幕已关闭，已暂停刷新"
        Decision.BLOCKED_POWER_SAVE -> "省电模式已开启，已暂停刷新"
        Decision.BLOCKED_NOT_WIFI -> "未连接 Wi-Fi，已暂停刷新"
        Decision.BLOCKED_WRONG_SSID -> "当前 Wi-Fi 不在名单内，已暂停刷新"
        Decision.BLOCKED_SSID_UNREADABLE -> "读不到 Wi-Fi 名称，已暂停刷新"
        Decision.BLOCKED_NO_SSID_CONFIGURED -> "尚未指定 Wi-Fi，已暂停刷新"
        else -> ""
    }

    /**
     * 屏幕是否处于交互状态。
     *
     * 用 `isInteractive` 而不是自己监听 SCREEN_ON/OFF 广播记状态：前者是同步查询、
     * 零状态、进程重启后依然正确；后者需要一个常驻宿主，进程被杀就会记错。
     * 亮屏时的「立即补刷一次」才需要广播，那部分在 [RefreshTrigger] 里。
     */
    fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    /** 系统省电模式是否开启。取不到 PowerManager 时按「未开启」处理，避免误暂停 */
    fun isPowerSaveMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isPowerSaveMode
    }

    /**
     * 手机当前是否走 Wi-Fi 链路。
     *
     * 判定口径与 [NetUtil.canReachDevice] 保持一致：VPN 也算，因为开启 always-on VPN 时
     * activeNetwork 可能只暴露 TRANSPORT_VPN，此时仍可能经底层 Wi-Fi 访问设备内网。
     */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * 当前连接的 Wi-Fi SSID，拿不到返回 null。
     *
     * 返回 null 的常见原因：不在 Wi-Fi 上、缺少定位权限、系统定位开关关闭。
     * 这三种情况系统的表现是一样的（`<unknown ssid>`），无法区分。
     */
    fun currentSsid(context: Context): String? {
        if (!hasLocationPermission(context)) return null

        return try {
            @Suppress("DEPRECATION")
            val info = (context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.connectionInfo ?: return null

            @Suppress("DEPRECATION")
            val raw = info.ssid ?: return null
            normalizeSsid(raw)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "currentSsid failed: ${e.message}")
            null
        }
    }

    /** 去掉系统给 SSID 加的引号，并过滤掉「未知」占位值 */
    private fun normalizeSsid(raw: String): String? {
        val trimmed = raw.trim().removeSurrounding("\"")
        if (trimmed.isEmpty()) return null
        if (trimmed.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true)) return null
        // 部分 ROM 在无权限时返回十六进制串而非 <unknown ssid>
        if (trimmed == "0x") return null
        return trimmed
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
