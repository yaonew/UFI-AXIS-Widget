package com.ufi_axis_widget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.ufi_axis_widget.R
import com.ufi_axis_widget.service.BackgroundMonitorService
import com.ufi_axis_widget.service.StatusTileService
import com.ufi_axis_widget.util.DataSourceType
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.DeviceProfiles
import com.ufi_axis_widget.util.NotificationHelper
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.ThemeColors
import com.ufi_axis_widget.util.WidgetBitmapCache
import com.ufi_axis_widget.util.WidgetLabelToggle
import com.ufi_axis_widget.util.WifiGuard
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry
import com.ufi_axis_widget.util.widget.WidgetAppearance
import com.ufi_axis_widget.util.widget.WidgetPrefs

import com.ufi_axis_widget.worker.WifiWorker
import java.util.Date
import java.util.Locale

abstract class BaseWifiWidget(val layoutId: Int) : AppWidgetProvider() {

    companion object {
        private const val TAG = "WifiWidget"
        private const val RENDER_DEDUP_MS = 2000L // 2 秒内重复调用直接跳过
        const val ACTION_REFRESH = "com.ufi_axis_widget.ACTION_REFRESH"

        /** 点击广播的私有校验串 extra，值取自 [SPUtil.getWidgetTapToken] */
        private const val EXTRA_TAP_TOKEN = "tap_token"

        /**
         * 单击刷新的延后时间。
         *
         * 刷新会立刻 updateAppWidget，桌面上那个小组件的视图整个被换掉，紧接着落下的
         * 第二下有相当概率点在正在重建的视图上被丢掉 —— 表现就是「双击要点好几次」。
         * 所以单击的动作先压住，等过连击窗口的前半段，期间来了第二下就直接放弃。
         */
        private const val SINGLE_TAP_DELAY_MS = 320L

        /** 延后单击动作用的主线程 Handler。必须是静态的：receiver 每次广播都是新实例 */
        private val tapHandler = Handler(Looper.getMainLooper())

        /** 已排队但还没执行的单击动作，下一下点击进来时先撤掉它 */
        @Volatile private var pendingSingleTap: Runnable? = null

        /** 上次 renderAllWidgets 完成时间戳，用于去重 Worker 与 MainActivity 双重渲染 */
        @Volatile private var lastRenderTime = 0L

        /** 上次渲染的数据指纹，数据未变时跳过整次渲染（performRender + applyWidgetTheme） */
        @Volatile private var lastDataHash: Int = 0
        /** 标记数据哈希是否已被首次计算过，避免 hash=0 被误判为"未缓存" */
        @Volatile private var hasCachedHash: Boolean = false

        /**
         * 获取或创建背景 Bitmap（委托 WidgetBitmapCache，分离纯色/自定义图缓存）。
         */
        private fun getOrCreateBgBitmap(context: Context, appearance: WidgetAppearance, color: Int, cornerRadiusDp: Float): Bitmap? {
            return if (appearance.bgImageUri.isNotBlank()) {
                WidgetBitmapCache.getOrCreateImageBitmap(
                    context, appearance.bgImageUri, cornerRadiusDp,
                    appearance.bgCrop, appearance.bmpW, appearance.bmpH,
                    appearance.widgetWDp, appearance.widgetHDp
                )
            } else {
                WidgetBitmapCache.getOrCreateSolidBitmap(
                    context, color, cornerRadiusDp, appearance.bmpW, appearance.bmpH,
                    appearance.widgetWDp, appearance.widgetHDp
                )
            }
        }

        fun getWidgetErrorLog(context: Context): String {
            return context.getSharedPreferences("widget_debug", Context.MODE_PRIVATE)
                .getString("error_log", "暂无日志") ?: "暂无日志"
        }

        fun clearWidgetErrorLog(context: Context) {
            context.getSharedPreferences("widget_debug", Context.MODE_PRIVATE)
                .edit().putString("error_log", "").apply()
        }

        /** 将各种温度格式统一为 "XX°C"：处理 "℃"、裸 "C" 后缀，避免重复替换导致 "°°C" */
        private fun normalizeTempString(temp: String): String {
            // 1. 先统一 "℃" → "°C"
            var s = temp.replace("℃", "°C")
            // 2. 如果已经是 "°C" 结尾则直接返回，避免二次替换
            if (s.endsWith("°C")) return s
            // 3. 处理裸 "C" 结尾（如 "35C" → "35°C"）
            if (s.endsWith("C") && !s.endsWith("°C")) {
                s = s.removeSuffix("C") + "°C"
            }
            return s
        }

        /** 缓存 SimpleDateFormat 避免每次日志追加都重新创建 */
        private val logTimeFormat = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun appendLog(context: Context, msg: String) {
            synchronized(logTimeFormat) {
                try {
                    val sp = context.getSharedPreferences("widget_debug", Context.MODE_PRIVATE)
                    val old = sp.getString("error_log", "") ?: ""
                    val timestamp = logTimeFormat.format(Date())
                    val newLog = "[$timestamp] $msg\n$old"
                    sp.edit().putString("error_log", newLog.lines().take(50).joinToString("\n")).apply()
                    Log.d(TAG, msg)
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "appendLog failed: ${e.message}")
                }
            }
        }

        /** 渲染去重锁，防止 TOCTOU 竞态（Worker 和 MainActivity 同时触发） */
        private val renderLock = Any()

        /**
         * 渲染串行锁。
         *
         * 与 [renderLock] 分开：去重判断很快，渲染要构建 Bitmap 并做跨进程 IPC，
         * 两者共用一把锁会让并发调用方在 IPC 期间白等。但渲染本身必须串行 ——
         * [WidgetBitmapCache] 是无同步的可变单例且会 recycle，
         * `force = true` 的调用跳过去重后可以与 Worker 的渲染撞在一起。
         */
        private val renderSerialLock = Any()

        /**
         * 用 SP 里的缓存值跑一次通知阈值检测。
         *
         * 抽出来是因为「跳过渲染」与「正常渲染」两条路径都要跑它，参数完全一样；
         * 之前逐字重复两遍，加一个通知维度就必然漏改一处。
         */
        private fun notifyFromCache(context: Context) {
            val sp = SPUtil.getSp(context)
            NotificationHelper.checkAndNotify(
                context = context,
                dailyFlowStr = sp.getString("daily_flow", "") ?: "",
                monthlyFlowStr = sp.getString("flow", "") ?: "",
                tempStr = sp.getString("temp", "") ?: "",
                cpuStr = sp.getString("cpu", "") ?: "",
                memStr = sp.getString("mem", "") ?: "",
                // -1 是全局约定的「无数据」哨兵：切换配置档会清掉这个键，
                // 默认给 0 会被 checkBattery 当成真实的 0% 电量而误发「电量过低」
                batteryPercent = sp.getInt("battery_percent", -1),
                isDeviceOnline = !WifiWorker.isWorkerStopped(context)
            )
        }

        fun renderAllWidgets(context: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            // ── 阶段1：synchronized 仅保护去重检查和时间戳更新 ──
            val pendingHash = synchronized(renderLock) {
                if (now - lastRenderTime < RENDER_DEDUP_MS && !force) {
                    return // 短时间内已渲染过（Worker 和 MainActivity 双重触发去重）
                }

                val currentHash = computeDataHash(context)
                if (!force && hasCachedHash && currentHash == lastDataHash) {
                    // SP 数据未变，跳过整次渲染（performRender + applyWidgetTheme）
                    // 但通知检测仍需执行（数据未变不代表通知已发送）
                    notifyFromCache(context)
                    return
                }

                // ════ 通知提醒检测（在小组件刷新周期中触发，确保后台被杀时仍能检测） ════
                notifyFromCache(context)
                // 时间戳立刻占位，挡住并发的重复渲染；
                // 但数据哈希要等渲染真正成功后才提交，见阶段 2。
                lastRenderTime = now
                currentHash
            }

            // ── 阶段2：实际渲染在 renderLock 之外、renderSerialLock 之内执行 ──
            try {
                synchronized(renderSerialLock) { renderAllSizes(context) }
                synchronized(renderLock) {
                    lastDataHash = pendingHash
                    hasCachedHash = true
                }
                // 数据/状态确实变了才通知磁贴，命中去重的路径不必打扰它
                StatusTileService.requestUpdate(context)
                BackgroundMonitorService.refreshLiveData(context)
            } catch (e: Exception) {
                // 渲染失败（Bitmap OOM、RemoteViews 异常等）时不能提交哈希，
                // 否则会被当成「已渲染过」，小组件会一直停在旧画面直到数据再次变化。
                DebugLogger.w(TAG, "renderAllWidgets failed: ${e.message}")
                // 只回滚自己写的那个时间戳：期间可能有另一次渲染成功并写了新值，
                // 无条件清零会连带把它的去重窗口一起抹掉
                synchronized(renderLock) { if (lastRenderTime == now) lastRenderTime = 0L }
            }
        }

        /**
         * 遍历注册表里所有已启用的小组件形态执行渲染。
         *
         * 默认按「类型层」构建一份 RemoteViews 广播给该形态的全部实例（与改造前一致，
         * 一次 IPC 搞定）；只有确实存在实例级覆盖配置的 id 才单独构建，
         * 避免为了一个很窄的场景把所有人的渲染开销乘上实例数。
         */
        private fun renderAllSizes(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)

            for (spec in WidgetRegistry.enabled) {
                // 主 provider 与影子 provider 都要遍历：切换标签后旧组件下仍可能有已放置实例
                for (widgetClass in spec.providers) {
                    val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, widgetClass))
                    if (ids.isEmpty()) continue

                    val customized = ids.filter { WidgetPrefs.hasInstanceOverride(context, spec.kind, it) }
                    val shared = ids.filterNot { it in customized }

                    if (shared.isNotEmpty()) {
                        val rv = buildRemoteViews(context, spec, widgetClass, null)
                        appWidgetManager.updateAppWidget(shared.toIntArray(), rv)
                    }
                    for (id in customized) {
                        val rv = buildRemoteViews(context, spec, widgetClass, id)
                        appWidgetManager.updateAppWidget(id, rv)
                    }
                }
            }
        }

        /** 按形态声明构建一份 RemoteViews：数据 → 主题 → 点击 */
        private fun buildRemoteViews(
            context: Context,
            spec: WidgetSpec,
            widgetClass: Class<*>,
            appWidgetId: Int?
        ): RemoteViews {
            // 布局与渲染器都按当前数据源的能力选：换数据源时同一个桌面组件直接换版面，
            // 不需要用户删掉重加。RemoteViews 的 layout 允许在每次更新时变化。
            val variant = spec.variantFor(DeviceDataSourceRegistry.currentCapabilities(context))
            val rv = RemoteViews(context.packageName, variant.layoutId)
            val scope = WidgetScope(context, spec.kind, appWidgetId)
            variant.renderer.render(context, rv, scope)
            spec.themer.render(context, rv, scope)
            setupClick(context, rv, widgetClass)
            return rv
        }

        /**
         * 计算 SP 数据指纹：缓存的数据字段哈希 + 实时读取的外观设置字段。
         * 数据字段（14 个）的哈希在 [SPUtil.saveData] 时预计算并缓存，
         * 避免每次渲染都从 SP 读取 40+ 个字段。
         * 外观设置变化频率低，直接实时读取即可。
         */
        private fun computeDataHash(context: Context): Int {
            val sp = SPUtil.getSp(context)
            var h = SPUtil.getCachedDataHash(context)
            if (h == 0 && !hasCachedHash) return 0 // 数据尚未缓存（首次启动），触发全量渲染
            // 各形态的显示项（含类型层与实例层覆盖）：SP 里 widget.* 前缀的键一次性纳入，
            // 这样以后新增形态或新增开关都不会漏进哈希 —— 漏了就表现为「改了设置组件不刷新」
            for ((key, value) in sp.all) {
                if (!key.startsWith("widget.")) continue
                h = 31 * h + key.hashCode()
                h = 31 * h + (value?.hashCode() ?: 0)
            }
            // 旧裸 key 仍是回退来源，未迁移的用户要靠它们
            h = 31 * h + sp.getBoolean("show_flow", true).hashCode()

            h = 31 * h + sp.getBoolean("show_signal", true).hashCode()
            h = 31 * h + sp.getBoolean("show_temp", true).hashCode()
            h = 31 * h + sp.getBoolean("show_cpu", true).hashCode()
            h = 31 * h + sp.getBoolean("show_model", true).hashCode()
            h = 31 * h + sp.getBoolean("show_time", true).hashCode()
            h = 31 * h + sp.getBoolean("show_battery", true).hashCode()
            h = 31 * h + sp.getBoolean("show_mem", true).hashCode()
            // 外观设置（主题/颜色/背景/透明度变更时必须触发重渲染）
            h = 31 * h + (sp.getString("widget_theme", "") ?: "").hashCode()
            h = 31 * h + sp.getBoolean("widget_follow_app_theme", true).hashCode()
            h = 31 * h + sp.getInt("color_theme", 0)
            h = 31 * h + sp.getInt("widget_color_theme", 0)
            h = 31 * h + sp.getInt("widget_custom_accent_light", 0)
            h = 31 * h + sp.getInt("widget_custom_accent_dark", 0)
            h = 31 * h + (sp.getString("widget_bg_image_uri", "") ?: "").hashCode()
            h = 31 * h + sp.getBoolean("widget_bg_image_enabled", false).hashCode()
            h = 31 * h + sp.getInt("widget_bg_opacity", 100)
            h = 31 * h + sp.getBoolean("widget_clip_to_outline", false).hashCode()
            // Android 12+ 动态配色开关（影响调色板选择，变更时必须重渲染）
            h = 31 * h + sp.getBoolean("widget_dynamic_color", true).hashCode()
            h = 31 * h + sp.getInt("widget_dynamic_contrast", 1)
            h = 31 * h + sp.getBoolean("widget_dynamic_advanced", false).hashCode()
            h = 31 * h + sp.getInt("widget_dynamic_color_source", 0)
            if (sp.getBoolean("widget_dynamic_advanced", false)) {
                h = 31 * h + sp.getInt("dyn_adv_light_bg", 97)
                h = 31 * h + sp.getInt("dyn_adv_light_txt", 12)
                h = 31 * h + sp.getInt("dyn_adv_dark_bg", 8)
                h = 31 * h + sp.getInt("dyn_adv_dark_txt", 90)
                h = 31 * h + sp.getInt("dyn_adv_sat_boost", 100)
            }
            // 各尺寸独立显隐设置
            h = 31 * h + sp.getBoolean("show_signal_2x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_battery_2x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_network_2x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_model_4x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_signal_4x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_battery_4x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_temp_4x1", true).hashCode()
            h = 31 * h + sp.getBoolean("show_time_4x1", true).hashCode()
            // 各尺寸独立字体大小
            h = 31 * h + sp.getInt("font_size_2x1", 9)
            h = 31 * h + sp.getInt("font_size_4x1", 9)
            // Worker 状态（影响 error overlay 显隐）
            h = 31 * h + WifiWorker.isWorkerStopped(context).hashCode()
            // 指定 Wi-Fi 守卫状态（暂停/恢复时必须重渲染，否则被哈希去重吞掉）
            h = 31 * h + WifiGuard.isRefreshAllowed(context).hashCode()
            // 当前数据源：能力声明变化会改变槽位显隐，切数据源后必须重绘
            h = 31 * h + SPUtil.getDataSourceType(context).ordinal
            // 重试状态不再独立影响 UI，仅与 stopped 组合使用
            return h
        }

        internal fun setupClick(context: Context, rv: RemoteViews, clazz: Class<*>) {
            val intent = Intent(context, clazz).apply {
                action = ACTION_REFRESH
                // 带上私有校验串，onReceive 会核对；见 SPUtil.getWidgetTapToken 的说明
                putExtra(EXTRA_TAP_TOKEN, SPUtil.getWidgetTapToken(context))
            }
            val pi = PendingIntent.getBroadcast(context, clazz.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // 确保布局中有 id 为 widget_root 的根容器
            try { rv.setOnClickPendingIntent(R.id.widget_root, pi) } catch (_: Exception) {
                // RemoteViews: 布局中无对应 id 时抛异常，静默吞掉
            }
        }

        /**
         * 非常态覆盖层的统一处理：重试中 / 连接失败 / 守卫暂停。
         *
         * 四个尺寸的渲染入口逻辑完全一致，抽在这里避免判定规则改一处漏三处。
         *
         * @param showRetryOverlay 是否显示「正在重试」覆盖层（4×2 与 2×2 布局有对应文案控件；
         *                         1×1 / 4×1 只有图标，文案会被 RemoteViews 静默丢弃）
         */
        private fun applyStateOverlay(
            context: Context,
            rv: RemoteViews,
            showRetryOverlay: Boolean = false
        ): OverlayState {
            val stopped = WifiWorker.isWorkerStopped(context)
            // 指定 Wi-Fi 守卫拦停时：不是"设备出问题"而是"本来就不该刷新"，
            // 因此既不显示加载中也不显示连接失败，直接展示上次的缓存数据。
            val decision = WifiGuard.evaluate(context)
            val paused = !decision.allowed

            // ===== 加载覆盖层（仅设备断连且用户刚点击刷新时显示）：提示用户并非功能不生效 =====
            if (showRetryOverlay && stopped && !paused && SPUtil.isReconnecting(context)) {
                safeSetVisibility(rv, R.id.widget_content, false)
                safeSetVisibility(rv, R.id.widget_error_overlay, true)
                safeSetImageResource(rv, R.id.widget_error_icon, R.drawable.ic_sync)
                safeSetText(rv, R.id.widget_error_text, "正在重试...")
                safeSetText(rv, R.id.widget_error_hint, "请稍候")
                return OverlayState(handled = true, pausedReason = "")
            }

            // ===== 错误状态：隐藏数据区，全屏显示连接失败提示 =====
            val showError = stopped && !paused
            safeSetVisibility(rv, R.id.widget_content, !showError)
            safeSetVisibility(rv, R.id.widget_error_overlay, showError)
            if (showError) {
                safeSetImageResource(rv, R.id.widget_error_icon, R.drawable.ic_router_off)
                return OverlayState(handled = true, pausedReason = "")
            }

            return OverlayState(
                handled = false,
                pausedReason = if (paused) WifiGuard.blockedReason(decision) else ""
            )
        }

        /**
         * [applyStateOverlay] 的返回值。
         *
         * @param handled     覆盖层已接管整个画面，调用方应立即 return
         * @param pausedReason 守卫暂停的原因文案，非空时应写进更新时间行，
         *                     否则用户看到的是一份不带任何说明的过期数据，
         *                     无法区分「按规则暂停」和「应用坏了」
         */
        private class OverlayState(val handled: Boolean, val pausedReason: String)

        internal fun performRender(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val sp = SPUtil.getSp(context)
            val overlay = applyStateOverlay(context, rv, showRetryOverlay = true)
            if (overlay.handled) return

            val model = sp.getString("model", "--") ?: "--"
            val deviceModel = sp.getString("device_model", model) ?: model
            val firmwareVer = sp.getString("firmware_ver", "") ?: ""
            val flow = sp.getString("flow", "--") ?: "--"
            val daily = sp.getString("daily_flow", "--") ?: "--"
            val signal = sp.getString("signal", "--") ?: "--"
            val temp = sp.getString("temp", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val batteryPercent = sp.getInt("battery_percent", -1)
            val charging = sp.getBoolean("battery_charging", false)
            val appVerCode = sp.getString("app_ver_code", "") ?: ""
            val cpu = sp.getString("cpu", "--") ?: "--"
            val mem = sp.getString("mem", "--") ?: "--"
            val netType = netTypeOf(context)  // 优先 AT（稳定）→ Goform 回退
            val internalStorage = sp.getString("internal_storage", "") ?: ""
            val updateTime = sp.getString("update_time", "--") ?: "--"

            // ===== 第一行：设备头部 + 信号 + 网络类型 + 电量 =====
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })
            // 固件版本格式：UFI v4.0.0.20260421 / GF v<wa_inner_version> / v<build_id>
            safeSetText(rv, R.id.tv_version, firmwareLabel(context, firmwareVer))

            // 版本代码，紧跟固件版本后
            safeSetText(rv, R.id.tv_app_ver_code,
                if (appVerCode.isNotEmpty()) "build$appVerCode" else "")

            // 信号格数矢量图标
            safeSetImageResource(rv, R.id.iv_signal_bars, signalIconRes(parseSignalLevel(signal)))

            // 电量矢量图标：充电时换成腔体内带闪电的那张（用户可在显示项里关掉充电指示）
            safeSetImageResource(
                rv, R.id.iv_battery,
                batteryIconRes(batteryPercent, charging && scope.show(WidgetPrefs.SHOW_CHARGING))
            )

            // 电量文本
            safeSetText(rv, R.id.tv_battery, batteryText(batteryPercent, battery))


            // ===== 第二行：流量大数字 =====
            safeSetText(rv, R.id.tv_daily, daily.replace("GB", "").trim())
            safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())

            // ===== 第三行：温度 + CPU + RAM + 信号质量 =====
            val tempClean = normalizeTempString(temp)
            safeSetText(rv, R.id.tv_temp, tempClean)

            val cpuClean = cpu.replace("%", "").trim()
            safeSetText(rv, R.id.tv_cpu, "CPU ${cpuClean}%")

            val memClean = mem.replace("%", "").trim()
            safeSetText(rv, R.id.tv_mem, "RAM ${memClean}%")

            // ===== 判断是否有有效的网络数据 =====
            val hasNetworkData = netType.isNotEmpty() && signal != "--"

            // ===== 第一行：网络制式图标 + 信号 dBm =====
            // 这里只定图标，显隐统一交给下面的 applyNetworkSlot（要叠加用户开关）
            if (hasNetworkData) {
                safeSetImageResource(rv, R.id.iv_network, networkIconRes(netType))
            }
            safeSetText(rv, R.id.tv_signal_dbm, signal)

            // ===== 路由器图标：此处 stopped 已在上层 early return，始终为 ic_router =====
            safeSetImageResource(rv, R.id.iv_router, R.drawable.ic_router)

            // ===== 第四行：时间戳 =====
            // 守卫暂停时把原因写在这里：否则用户只能看到一份不带说明的过期数据
            safeSetText(rv, R.id.tv_update_time, overlay.pausedReason.ifEmpty { updateTime })

            // 显隐设置：用户开关 AND 数据源能力。
            // 能力不支持时整槽位隐藏，而不是画一个字面 "--" —— 后者会让用户以为组件坏了。
            val caps = DeviceDataSourceRegistry.currentCapabilities(context)
            val showFlow = scope.show(WidgetPrefs.SHOW_FLOW)
            val showDaily = showFlow && Capability.DAILY_TRAFFIC.isSupported(caps)
            val showTemp = scope.show(WidgetPrefs.SHOW_TEMP) && Capability.TEMPERATURE.isSupported(caps)
            val showModel = scope.show(WidgetPrefs.SHOW_MODEL)
            val showVersion = scope.show(WidgetPrefs.SHOW_VERSION)
            val showSignal = scope.show(WidgetPrefs.SHOW_SIGNAL)
            val showBattery = scope.show(WidgetPrefs.SHOW_BATTERY)
            val showCpu = scope.show(WidgetPrefs.SHOW_CPU) && Capability.CPU.isSupported(caps)
            val showMem = scope.show(WidgetPrefs.SHOW_MEM) && Capability.MEMORY.isSupported(caps)
            val showTime = scope.show(WidgetPrefs.SHOW_TIME)
            val showDivider = scope.show(WidgetPrefs.SHOW_DIVIDER)

            safeSetVisibility(rv, R.id.tv_model, showModel)
            // 版本号与 build 号是同一件事的两半，跟着同一个开关走
            safeSetVisibility(rv, R.id.tv_version, showVersion)
            safeSetVisibility(rv, R.id.tv_app_ver_code, showVersion)
            safeSetVisibility(rv, R.id.tv_flow, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_label, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_unit, showFlow)
            safeSetVisibility(rv, R.id.tv_daily, showDaily)
            safeSetVisibility(rv, R.id.tv_daily_label, showDaily)
            safeSetVisibility(rv, R.id.tv_daily_unit, showDaily)

            // 温度
            safeSetVisibility(rv, R.id.tv_temp, showTemp)
            safeSetVisibility(rv, R.id.iv_temp, showTemp)

            // 信号
            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal)
            applyNetworkSlot(rv, showSignal, hasNetworkData)
            safeSetVisibility(rv, R.id.tv_signal_dbm, showSignal)

            safeSetVisibility(rv, R.id.iv_antenna, showSignal)

            // 电池
            safeSetVisibility(rv, R.id.iv_battery, showBattery)
            safeSetVisibility(rv, R.id.tv_battery, showBattery)


            // CPU
            safeSetVisibility(rv, R.id.tv_cpu, showCpu)
            safeSetVisibility(rv, R.id.iv_cpu, showCpu)

            // 内存
            safeSetVisibility(rv, R.id.tv_mem, showMem)
            safeSetVisibility(rv, R.id.iv_chip, showMem)

            // 更新时间
            safeSetVisibility(rv, R.id.tv_update_time, showTime)

            // 分割线
            safeSetVisibility(rv, R.id.divider_flow, showDivider)
        }

        /**
         * goform 专用 4×2 渲染。
         *
         * goform 直连拿不到温度/CPU/内存/当日流量，通用 4×2 在这个数据源下会有一半槽位是 `--`。
         * 这里换成 goform 真正有的维度：月流量当主数字，第三行放信号详情
         * （RSRP / SINR / 频段 / PCI，都来自 `atNetwork` 能力，goform 声明支持）。
         *
         * 布局刻意复用 widget_4x2 的结构性 id（widget_root / widget_bg_image / widget_bg_stroke /
         * widget_content / widget_error_* / divider_flow），这样主题着色可以完全共用
         * [applyWidgetTheme]，不需要再写一份。
         */
        internal fun performRenderGoform(context: Context, rv: RemoteViews, scope: WidgetScope) {
            val sp = SPUtil.getSp(context)
            val overlay = applyStateOverlay(context, rv, showRetryOverlay = true)
            if (overlay.handled) return

            val model = sp.getString("model", "--") ?: "--"
            val deviceModel = sp.getString("device_model", model) ?: model
            val firmwareVer = sp.getString("firmware_ver", "") ?: ""
            val flow = sp.getString("flow", "--") ?: "--"
            val signal = sp.getString("signal", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val netType = netTypeOf(context)
            val carrier = sp.getString("at_carrier", "") ?: ""
            val band = sp.getString("at_band", "") ?: ""
            val pci = sp.getInt("at_pci", 0)
            val sinr = sp.getInt("at_sinr", Int.MIN_VALUE)
            // 原始百分比而不是格式化串：判断「有没有电量数据」要看数值，
            // battery 串在无数据时是 "--"，不适合当判据
            val batteryPercent = sp.getInt("battery_percent", -1)
            val charging = sp.getBoolean("battery_charging", false)
            val updateTime = sp.getString("update_time", "--") ?: "--"

            // ===== 第一行：型号 + 固件 + 信号格 + 制式 + 电量 =====
            safeSetImageResource(rv, R.id.iv_router, R.drawable.ic_router)
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })
            // 固件前缀跟随数据源：这个形态虽然是为 goform 设计的，
            // 但 UFI-AXIS 也满足它要求的 atNetwork 能力，会被用户选来用
            safeSetText(rv, R.id.tv_version, firmwareLabel(context, firmwareVer))
            val showVersionGoform = scope.show(WidgetPrefs.SHOW_VERSION)
            safeSetVisibility(rv, R.id.tv_version, showVersionGoform)
            safeSetVisibility(rv, R.id.tv_app_ver_code, showVersionGoform)

            safeSetImageResource(rv, R.id.iv_signal_bars, signalIconRes(parseSignalLevel(signal)))

            safeSetImageResource(
                rv, R.id.iv_battery,
                batteryIconRes(batteryPercent, charging && scope.show(WidgetPrefs.SHOW_CHARGING))
            )
            safeSetText(rv, R.id.tv_battery, batteryText(batteryPercent, battery))


            val hasNetworkData = netType.isNotEmpty() && signal != "--"
            if (hasNetworkData) {
                safeSetImageResource(rv, R.id.iv_network, networkIconRes(netType))
            }

            // ===== 第二行：运营商/制式 + 月流量主数字 =====
            safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())
            safeSetText(rv, R.id.tv_carrier, carrier.ifEmpty { "未知运营商" })
            safeSetText(rv, R.id.tv_net_type, netType.ifEmpty { "无网络" })

            // ===== 第三行：信号详情。图标只做视觉锚点，指标名仍要写全 =====
            // RSRP/SINR/频段/PCI 不像温度、CPU 那样有约定俗成的图标，只给图标认不出来
            safeSetImageResource(rv, R.id.iv_antenna, R.drawable.ic_antenna)
            safeSetImageResource(rv, R.id.iv_sinr, R.drawable.ic_heartbeat)
            safeSetImageResource(rv, R.id.iv_band, R.drawable.ic_blur)
            safeSetImageResource(rv, R.id.iv_chip, R.drawable.ic_chip)
            safeSetText(rv, R.id.tv_rsrp, "RSRP " + signal.removeSuffix("dBm"))
            safeSetText(rv, R.id.tv_sinr, "SINR " + if (sinr == Int.MIN_VALUE) "--" else "$sinr")
            safeSetText(rv, R.id.tv_band, band.ifEmpty { "--" })
            safeSetText(rv, R.id.tv_pci, "PCI " + if (pci > 0) "$pci" else "--")

            // ===== 第四行：时间戳（守卫暂停时替换为原因）=====
            safeSetText(rv, R.id.tv_update_time, overlay.pausedReason.ifEmpty { updateTime })

            // ===== 显隐 =====
            // 除了用户开关，还要看「这一项到底有没有值」。能力声明是按 goform 协议清单
            // 写的，具体机型固件裁掉哪些字段只能运行时才知道 —— 声明有、实际没有时，
            // 只按开关渲染就会留下一排 "--"，看起来像坏了。
            val showSignal = scope.show(WidgetPrefs.SHOW_SIGNAL)
            val showBand = scope.show(WidgetPrefs.SHOW_BAND)
            val showCarrier = scope.show(WidgetPrefs.SHOW_CARRIER)
            val showFlow = scope.show(WidgetPrefs.SHOW_FLOW)
            val showModel = scope.show(WidgetPrefs.SHOW_MODEL)
            val showBattery = scope.show(WidgetPrefs.SHOW_BATTERY)
            val showTime = scope.show(WidgetPrefs.SHOW_TIME)
            val showDivider = scope.show(WidgetPrefs.SHOW_DIVIDER)

            val hasBattery = batteryPercent in 0..100
            val hasRsrp = signal != "--" && signal.isNotEmpty()
            val hasSinr = sinr != Int.MIN_VALUE
            val hasBand = band.isNotEmpty()
            val hasPci = pci > 0
            val hasCarrier = carrier.isNotEmpty()
            val hasAnyDetail = hasRsrp || hasSinr || hasBand || hasPci

            safeSetVisibility(rv, R.id.tv_model, showModel)
            safeSetVisibility(rv, R.id.tv_flow, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_label, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_unit, showFlow)
            safeSetVisibility(rv, R.id.divider_flow, showDivider && showFlow)

            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal)
            applyNetworkSlot(rv, showSignal, hasNetworkData)

            // 运营商没解析出来时只留制式，不显示「未知运营商」占位
            safeSetVisibility(rv, R.id.tv_carrier, showCarrier && hasCarrier)
            safeSetVisibility(rv, R.id.tv_net_type, showCarrier)

            // 图标与文本必须同进同退，否则关掉信号详情后会留下四个孤零零的图标
            safeSetVisibility(rv, R.id.iv_antenna, showBand && hasRsrp)
            safeSetVisibility(rv, R.id.tv_rsrp, showBand && hasRsrp)
            safeSetVisibility(rv, R.id.iv_sinr, showBand && hasSinr)
            safeSetVisibility(rv, R.id.tv_sinr, showBand && hasSinr)
            safeSetVisibility(rv, R.id.iv_band, showBand && hasBand)
            safeSetVisibility(rv, R.id.tv_band, showBand && hasBand)
            safeSetVisibility(rv, R.id.iv_chip, showBand && hasPci)
            safeSetVisibility(rv, R.id.tv_pci, showBand && hasPci)
            safeSetVisibility(rv, R.id.row_signal_detail, showBand && hasAnyDetail)

            // 本机固件没返回电量时整块隐藏，而不是显示 "--" 配一个空电池图标
            safeSetVisibility(rv, R.id.iv_battery, showBattery && hasBattery)
            safeSetVisibility(rv, R.id.tv_battery, showBattery && hasBattery)
            safeSetVisibility(rv, R.id.tv_update_time, showTime)
        }

        /** 4x1 条形布局数据渲染 */

        internal fun performRender4x1(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val sp = SPUtil.getSp(context)
            val overlay = applyStateOverlay(context, rv)
            if (overlay.handled) return

            val model = sp.getString("model", "--") ?: "--"
            val deviceModel = sp.getString("device_model", model) ?: model
            val flow = sp.getString("flow", "--") ?: "--"
            val signal = sp.getString("signal", "--") ?: "--"
            val temp = sp.getString("temp", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val netType = netTypeOf(context)  // 优先 AT（稳定）→ Goform 回退
            val hasNetworkData = netType.isNotEmpty() && signal != "--"
            val batteryPercent = sp.getInt("battery_percent", -1)
            val charging = sp.getBoolean("battery_charging", false)
            val updateTime = sp.getString("update_time", "--") ?: "--"

            // 路由器 + 型号
            safeSetImageResource(rv, R.id.iv_router, R.drawable.ic_router)
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })

            // 本月流量。单位写死在布局的 tv_flow_unit 里，这里剥掉重复的 GB
            safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())

            // 信号格数 + dBm
            safeSetImageResource(rv, R.id.iv_signal_bars, signalIconRes(parseSignalLevel(signal)))
            safeSetText(rv, R.id.tv_signal_dbm, signal)

            // 网络类型（显隐统一交给下面的 applyNetworkSlot）
            if (hasNetworkData) {
                safeSetImageResource(rv, R.id.iv_network, networkIconRes(netType))
            }

            // 电量图标：充电时换成腔体内带闪电的那张
            safeSetImageResource(
                rv, R.id.iv_battery,
                batteryIconRes(batteryPercent, charging && scope.show(WidgetPrefs.SHOW_CHARGING))
            )
            safeSetText(rv, R.id.tv_battery, batteryText(batteryPercent, battery))

            // 温度
            val tempClean = normalizeTempString(temp)
            safeSetText(rv, R.id.tv_temp, tempClean)

            // 更新时间
            safeSetText(rv, R.id.tv_update_time, overlay.pausedReason.ifEmpty { updateTime })

            // 4×1 独立显隐设置（类型层作用域，旧 show_*_4x1 裸 key 由 WidgetPrefs 回退兼容）
            val caps4x1 = DeviceDataSourceRegistry.currentCapabilities(context)
            val showModel = scope.show(WidgetPrefs.SHOW_MODEL)
            val showFlow = scope.show(WidgetPrefs.SHOW_FLOW)
            val showSignal = scope.show(WidgetPrefs.SHOW_SIGNAL)
            val showTemp = scope.show(WidgetPrefs.SHOW_TEMP) && Capability.TEMPERATURE.isSupported(caps4x1)
            val showBattery = scope.show(WidgetPrefs.SHOW_BATTERY)
            val showTime = scope.show(WidgetPrefs.SHOW_TIME)

            safeSetVisibility(rv, R.id.tv_model, showModel)
            safeSetVisibility(rv, R.id.iv_router, showModel)
            safeSetVisibility(rv, R.id.tv_flow, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_unit, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_label, showFlow)
            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal)
            safeSetVisibility(rv, R.id.tv_signal_dbm, showSignal)
            applyNetworkSlot(rv, showSignal, hasNetworkData)
            safeSetVisibility(rv, R.id.iv_temp, showTemp)

            safeSetVisibility(rv, R.id.tv_temp, showTemp)
            safeSetVisibility(rv, R.id.iv_battery, showBattery)
            safeSetVisibility(rv, R.id.tv_battery, showBattery)
            safeSetVisibility(rv, R.id.tv_update_time, showTime)
        }

        /**
         * goform 专用 4×1 渲染（布局 widget_goform_4x1）。
         *
         * 与 [performRender4x1] 的差别只有两处：温度槽位换成频段，流量标签后面多一个运营商。
         * goform 协议里没有温度字段，通用版在这个数据源下那一格永远是空的。
         */
        internal fun performRenderGoform4x1(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val sp = SPUtil.getSp(context)
            val overlay = applyStateOverlay(context, rv)
            if (overlay.handled) return

            val model = sp.getString("model", "--") ?: "--"
            val deviceModel = sp.getString("device_model", model) ?: model
            val flow = sp.getString("flow", "--") ?: "--"
            val signal = sp.getString("signal", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val netType = netTypeOf(context)
            val carrier = sp.getString("at_carrier", "") ?: ""
            val band = sp.getString("at_band", "") ?: ""
            val hasNetworkData = netType.isNotEmpty() && signal != "--"
            val batteryPercent = sp.getInt("battery_percent", -1)
            val charging = sp.getBoolean("battery_charging", false)
            val updateTime = sp.getString("update_time", "--") ?: "--"

            safeSetImageResource(rv, R.id.iv_router, R.drawable.ic_router)
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })

            safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())
            safeSetText(rv, R.id.tv_carrier, carrier)

            safeSetImageResource(rv, R.id.iv_signal_bars, signalIconRes(parseSignalLevel(signal)))
            safeSetText(rv, R.id.tv_signal_dbm, signal)
            if (hasNetworkData) {
                safeSetImageResource(rv, R.id.iv_network, networkIconRes(netType))
            }

            safeSetImageResource(
                rv, R.id.iv_battery,
                batteryIconRes(batteryPercent, charging && scope.show(WidgetPrefs.SHOW_CHARGING))
            )
            safeSetText(rv, R.id.tv_battery, batteryText(batteryPercent, battery))

            safeSetImageResource(rv, R.id.iv_band, R.drawable.ic_blur)
            safeSetText(rv, R.id.tv_band, band.ifEmpty { "--" })

            safeSetText(rv, R.id.tv_update_time, overlay.pausedReason.ifEmpty { updateTime })

            // ===== 显隐 =====
            // 能力声明按 goform 协议清单写的，具体机型固件裁掉哪些字段只有运行时知道，
            // 所以「有没有值」也要参与判断，否则会留下一排 "--"
            val showModel = scope.show(WidgetPrefs.SHOW_MODEL)
            val showFlow = scope.show(WidgetPrefs.SHOW_FLOW)
            val showSignal = scope.show(WidgetPrefs.SHOW_SIGNAL)
            val showCarrier = scope.show(WidgetPrefs.SHOW_CARRIER)
            val showBand = scope.show(WidgetPrefs.SHOW_BAND)
            val showBattery = scope.show(WidgetPrefs.SHOW_BATTERY)
            val showTime = scope.show(WidgetPrefs.SHOW_TIME)

            val hasBattery = batteryPercent in 0..100

            safeSetVisibility(rv, R.id.tv_model, showModel)
            safeSetVisibility(rv, R.id.iv_router, showModel)
            safeSetVisibility(rv, R.id.tv_flow, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_unit, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_label, showFlow)
            safeSetVisibility(rv, R.id.tv_carrier, showCarrier && carrier.isNotEmpty())
            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal)
            safeSetVisibility(rv, R.id.tv_signal_dbm, showSignal)
            applyNetworkSlot(rv, showSignal, hasNetworkData)
            // 图标与文本同进同退，否则关掉频段后会留一个孤零零的图标
            safeSetVisibility(rv, R.id.iv_band, showBand && band.isNotEmpty())
            safeSetVisibility(rv, R.id.tv_band, showBand && band.isNotEmpty())
            safeSetVisibility(rv, R.id.iv_battery, showBattery && hasBattery)
            safeSetVisibility(rv, R.id.tv_battery, showBattery && hasBattery)
            safeSetVisibility(rv, R.id.tv_update_time, showTime)
        }
        internal fun applyWidgetTheme4x1(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val appearance = appearanceOf(context, scope)
            val colors = resolveWidgetColors(context, appearance)
            applyChassis(context, rv, colors.pageBg, colors.divider, appearance)

            // ── 标签/附注色 ──
            // goform 变体独有的 id（tv_carrier）一起写进来：safeSet* 找不到 id 会静默跳过，
            // 所以两套布局可以共用这一份着色，不需要按变体分叉
            for (id in listOf(
                R.id.tv_flow_label, R.id.tv_flow_unit,
                R.id.tv_no_network, R.id.tv_update_time, R.id.tv_carrier
            )) {
                safeSetTextColor(rv, id, colors.text)
            }

            // ── 数值色 ──
            for (id in listOf(
                R.id.tv_model, R.id.tv_flow, R.id.tv_signal_dbm,
                R.id.tv_battery, R.id.tv_temp, R.id.tv_band
            )) {
                safeSetTextColor(rv, id, colors.data)
            }

            // ── 图标着色 ──
            for (id in listOf(
                R.id.iv_router, R.id.iv_signal_bars, R.id.iv_network,
                R.id.iv_battery, R.id.iv_temp, R.id.iv_band
            )) {
                safeSetImageViewTint(rv, id, colors.data)
            }

            // 4×1 独立字体大小。
            // 每一项的基准值必须和 widget_4x1.xml 里写的 textSize 一致：这里是「按比例缩放」，
            // 基准对不上就会出现「一动字号滑块版面整体跳一下」
            val fontSize4x1 = scope.int(WidgetPrefs.FONT_SIZE, 9).toFloat()

            val defaultBase4x1 = 9f
            if (fontSize4x1 != defaultBase4x1) {
                val ratio = fontSize4x1 / defaultBase4x1
                safeSetTextSize(rv, R.id.tv_model, 11f * ratio)
                safeSetTextSize(rv, R.id.tv_flow, 25f * ratio)
                safeSetTextSize(rv, R.id.tv_flow_unit, 11f * ratio)
                safeSetTextSize(rv, R.id.tv_flow_label, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_signal_dbm, 9f * ratio)
                safeSetTextSize(rv, R.id.tv_battery, 11f * ratio)
                safeSetTextSize(rv, R.id.tv_temp, 9f * ratio)
                safeSetTextSize(rv, R.id.tv_update_time, 9f * ratio)
                // goform 变体独有的两项，基准值取自 widget_goform_4x1.xml
                safeSetTextSize(rv, R.id.tv_carrier, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_band, 9f * ratio)
            }

            // ── 错误覆盖层着色 ──
            safeSetImageViewTint(rv, R.id.widget_error_icon, colors.data)
            safeSetTextColor(rv, R.id.widget_error_text, colors.text)
        }

        /**
         * 当前网络制式：优先 AT 透传的 `at_net_type`（稳定），空则回落 goform 的 `net_type`。
         *
         * 6 个渲染函数都要这一条回退链，抄 7 份的后果是改回退规则得改 7 处；
         * [StatusSummary] 已经在用同一写法，这里同样复用 [SPUtil.getAtNetType]。
         */
        private fun netTypeOf(context: Context): String =
            SPUtil.getAtNetType(context).ifEmpty {
                SPUtil.getSp(context).getString("net_type", "") ?: ""
            }

        /**
         * 型号右侧的版本号文案。
         *
         * 前缀跟随数据源，避免 goform 模式下仍标成 UFI。UFI-AXIS 报的是设备自己的
         * `build_id`，不属于任何一家工具的版本号，所以不加前缀。
         */
        private fun firmwareLabel(context: Context, firmwareVer: String): String {
            if (firmwareVer.isEmpty()) return ""
            val prefix = when (SPUtil.getDataSourceType(context)) {
                DataSourceType.GOFORM -> "GF "
                DataSourceType.UFI_AXIS -> ""
                DataSourceType.UFI_TOOLS -> "UFI "
            }
            return "${prefix}v$firmwareVer"
        }

        /** 从 RSRP dBm 信号值推算 1-5 格信号强度 */
        private fun parseSignalLevel(signal: String): Int {
            return try {
                val raw = signal.replace("dBm", "").trim().toIntOrNull() ?: 0
                // RSRP 应为负值；若为正值则取反（兼容部分设备返回绝对值的情况）
                val rssi = if (raw > 0) -raw else raw
                if (rssi >= 0) return 0   // 0 或无法解析 → 无信号
                when {
                    rssi > -85  -> 5   // 非常好
                    rssi >= -95 -> 4   // 良好
                    rssi >= -105 -> 3  // 一般 / 中等
                    rssi >= -115 -> 2  // 较差
                    else         -> 1   // 极差
                }
            } catch (_: Exception) {
                // 信号解析失败（非数字字符串如 "--"），返回 0 格
                0
            }
        }

        /*
         * safeSetXxx 说明（这一组的注释以前写成「id 不存在时抛异常，这里兜住」，不准确）：
         *
         * RemoteViews 的调用只是把动作入队，真正执行在桌面进程 apply 的时候，所以本进程的
         * try/catch 基本捕获不到任何东西。「布局里没有这个 id」之所以安全，靠的是框架侧
         * ReflectionAction 里的 findViewById() ?: return —— 静默跳过，与这里的 catch 无关。
         *
         * 由此有两条实际约束：
         *  1. 共用着色函数里加新 id 是安全的（缺 id 会被跳过），但方法名必须与目标 View 的
         *     真实类型匹配，否则错误发生在桌面进程，本地这层 catch 拦不住，表现为整块
         *     「载入时出现问题」。新增 id 时按每套布局核对一遍 View 类型。
         *  2. 反过来也意味着「设置了却没生效」不会有任何报错，漏写 id 只能靠肉眼比对布局。
         */

        /** 遥控方法：setTextViewText */
        private fun safeSetText(rv: RemoteViews, id: Int, text: String) {
            try { rv.setTextViewText(id, text) } catch (_: Exception) {
                // 兜底：只可能是 rv 本身状态异常（如已被回收），不是 id 缺失
            }
        }

        /** 遥控方法：setViewVisibility */
        private fun safeSetVisibility(rv: RemoteViews, id: Int, visible: Boolean) {
            try { rv.setViewVisibility(id, if (visible) View.VISIBLE else View.GONE) } catch (_: Exception) {
                // 同上
            }
        }

        /** 遥控方法：setImageViewResource */
        private fun safeSetImageResource(rv: RemoteViews, id: Int, resId: Int) {
            try { rv.setImageViewResource(id, resId) } catch (_: Exception) {
                // 同上
            }
        }

        /** 遥控方法：setTextColor */
        private fun safeSetTextColor(rv: RemoteViews, id: Int, color: Int) {
            try { rv.setTextColor(id, color) } catch (_: Exception) {
                // 同上
            }
        }

        /** 遥控方法：setTextViewTextSize */
        private fun safeSetTextSize(rv: RemoteViews, id: Int, sizeSp: Float) {
            try { rv.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp) } catch (_: Exception) {
                // RemoteViews: setTextViewTextSize 可能不被所有 Launcher 支持
            }
        }

        /** 遥控方法：反射 setColorFilter */
        private fun safeSetImageViewTint(rv: RemoteViews, id: Int, color: Int) {
            try { rv.setInt(id, "setColorFilter", color) } catch (_: Exception) {
                // 同上
            }
        }

        /**
         * 需要统一着色的图标 id。
         *
         * 静态主题与动态配色两条路径的图标集合完全一致（只有色值不同），所以共用这一份；
         * safeSetImageViewTint 对布局里不存在的 id 静默跳过，因此一份清单能覆盖全部 7 个布局。
         * 注意文字 id 不能同样合并 —— 动态配色把标签和数据值分成两组用不同色，静态路径是一把刷。
         */
        private val THEME_ICON_IDS = listOf(
            R.id.iv_router, R.id.iv_signal_bars, R.id.iv_network,
            R.id.iv_battery, R.id.iv_cpu, R.id.iv_chip,
            R.id.iv_antenna, R.id.iv_temp,
            // goform 布局第三行专有
            R.id.iv_sinr, R.id.iv_band
        )

        /**
         * 根据主题模式设置小组件背景和文字颜色（支持自定义背景图 + 透明度）。
         *
         * 外观默认取全局设置；该形态（或该实例）开了「独立外观」时由 [WidgetAppearance]
         * 整组切到作用域键，所以这里必须读 [scope]。
         * 所有写入都走 safeSetXxx，因此同一份实现能作用于字段不完全相同的多个 layout。
         */
        internal fun applyWidgetTheme(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val appearance = appearanceOf(context, scope)

            // ═══ 动态配色独立路径 ═══
            // 开启动态配色后，小组件颜色完全由壁纸色调 + 系统暗色模式决定，
            // 不受"跟随应用主题"、"小组件配色"等设置影响
            if (isWidgetDynamicActive(appearance)) {
                applyWidgetThemeDynamic(context, rv, appearance)
                return
            }

            val isDark = appearance.isDark
            val palette = ThemeColors.getById(
                context, appearance.colorThemeIndex, isWidget = true, appearance = appearance
            )

            // 根据浅/深选择色值
            val pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight
            val textColor = if (isDark) palette.textSecondaryDark else palette.textSecondaryLight
            val divider = if (isDark) palette.dividerDark else palette.dividerLight

            applyChassis(context, rv, pageBg, divider, appearance)

            // ── 文字色（统一）──
            // 含 goform 专用布局的字段：safeSetTextColor 在布局没有该 id 时静默跳过，
            // 所以同一份着色实现可以覆盖多种 layout
            for (id in listOf(
                R.id.tv_model, R.id.tv_version, R.id.tv_app_ver_code,
                R.id.tv_battery,
                R.id.tv_daily, R.id.tv_daily_label, R.id.tv_daily_unit,
                R.id.tv_flow, R.id.tv_flow_label, R.id.tv_flow_unit,
                R.id.tv_temp, R.id.tv_cpu, R.id.tv_mem,
                R.id.tv_signal_dbm, R.id.tv_no_network, R.id.tv_update_time,
                R.id.tv_carrier, R.id.tv_net_type,
                R.id.tv_rsrp, R.id.tv_sinr, R.id.tv_band, R.id.tv_pci
            )) {
                safeSetTextColor(rv, id, textColor)
            }

            // ── 分割线 ──
            rv.setInt(R.id.divider_flow, "setBackgroundColor", divider)

            // ── 图标着色（统一）──
            for (id in THEME_ICON_IDS) {
                safeSetImageViewTint(rv, id, textColor)
            }

            // ── 错误覆盖层着色 ──
            safeSetImageViewTint(rv, R.id.widget_error_icon, textColor)
            safeSetTextColor(rv, R.id.widget_error_text, textColor)
            safeSetTextColor(rv, R.id.widget_error_hint, textColor)
        }

        // ═══════════════════════════════════════════════════════════
        //  动态配色独立渲染路径（API 31+）
        //  当动态取色开启时，小组件颜色完全由壁纸主色 + 系统暗色模式决定，
        //  不受"跟随应用主题"、"显示模式"、"小组件配色"等设置影响
        // ═══════════════════════════════════════════════════════════

        /**
         * 判断该作用域是否启用了动态配色（API 31+ 且动态取色开关开启）。
         *
         * 开关来自 [WidgetAppearance]，所以「只给 2×2 开动态配色」这种配置才成立。
         */
        private fun isWidgetDynamicActive(appearance: WidgetAppearance): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ThemeColors.supportsDynamicColors()
                && appearance.dynamicColor
        }

        /** 直接读取系统暗色模式（不受应用/小组件主题设置影响） */
        private fun isSystemDarkMode(context: Context): Boolean {
            return ThemeColors.isSystemDark(context)
        }

        /**
         * 一次着色需要的四个色值。
         *
         * @param data 数据值/图标色。静态主题下与 [text] 同色，动态配色下取 dataHighlight
         */
        private class WidgetColors(
            val pageBg: Int,
            val text: Int,
            val data: Int,
            val divider: Int
        )

        /**
         * 解析当前应该用哪套颜色。
         *
         * 动态配色的判定必须收在这里：原先只有 4×2 的 [applyWidgetTheme] 查了
         * [isWidgetDynamicActive]，4×1 / 2×1 各自读「跟随应用主题」，
         * 结果用户开了动态配色，小尺寸组件却还是旧配色。
         *
         * [appearance] 决定读全局还是读该形态/实例的独立外观，见 [WidgetAppearance]。
         */
        private fun resolveWidgetColors(context: Context, appearance: WidgetAppearance): WidgetColors {
            if (isWidgetDynamicActive(appearance)) {
                // 动态配色完全由壁纸主色 + 系统暗色模式决定，
                // 不受「跟随应用主题」「显示模式」「小组件配色」影响
                val isDark = isSystemDarkMode(context)
                val palette = ThemeColors.buildDynamicPalette(context, appearance)
                return WidgetColors(
                    pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight,
                    // 动态配色用 textPrimary（高对比度），而非 textSecondary
                    text = if (isDark) palette.textPrimaryDark else palette.textPrimaryLight,
                    data = if (isDark) palette.dataHighlightDark else palette.dataHighlightLight,
                    divider = if (isDark) palette.dividerDark else palette.dividerLight
                )
            }
            val isDark = appearance.isDark
            val palette = ThemeColors.getById(
                context, appearance.colorThemeIndex, isWidget = true, appearance = appearance
            )
            val text = if (isDark) palette.textSecondaryDark else palette.textSecondaryLight
            return WidgetColors(
                pageBg = if (isDark) palette.pageBgDark else palette.pageBgLight,
                text = text,
                // 静态主题不区分数据值与标签，两者同色
                data = text,
                divider = if (isDark) palette.dividerDark else palette.dividerLight
            )
        }

        /** 作用域 → 外观。单独抽出来是为了让一次 themer 调用只解析一遍 */
        private fun appearanceOf(context: Context, scope: WidgetScope): WidgetAppearance {
            // 背景位图必须按该实例的真实宽高比生成：布局里是 fitXY，比例不对就是拉伸变形。
            // 优先用系统给的实测尺寸（用户缩放过组件也能跟上），拿不到才退回标称比例
            val measured = WidgetRegistry.measuredSizeDp(context, scope.appWidgetId)
            val aspect = measured?.let { (mw, mh) -> mw.toFloat() / mh }
                ?: WidgetRegistry.byKind(scope.kind)?.nominalAspect
                ?: 2f
            val (w, h) = WidgetRegistry.bitmapSizeFor(aspect)
            // 实测 dp 一并传下去：圆角半径要按「位图边长 / 组件 dp」折算才不会被 fitXY 缩放走样。
            // 标称尺寸偏差太大（2×1 标称 110dp、实际约 160dp），拿不到实测就传 0 走 density 兜底
            return WidgetAppearance.of(
                context, scope.kind, scope.appWidgetId,
                bmpW = w, bmpH = h,
                widgetWDp = measured?.first ?: 0,
                widgetHDp = measured?.second ?: 0,
            )
        }

        /**
         * 组件「外壳」着色：圆角裁剪 + 背景图/兜底底色 + 透明度 + 描边。
         *
         * 四个尺寸的这段逻辑完全一致，抄四份的直接后果就是新增开关时漏改，
         * 所以收敛到这里。各尺寸只保留自己的文字/图标着色与字号。
         */
        private fun applyChassis(
            context: Context,
            rv: RemoteViews,
            pageBg: Int,
            divider: Int,
            appearance: WidgetAppearance
        ) {
            // ── 圆角裁剪兜底开关（部分国产桌面自动加圆角时用户可关闭）──
            val shouldClip = appearance.clipToOutline
            val cornerRadiusDp = if (shouldClip) 10f else 0f
            val strokeRes = if (shouldClip) R.drawable.bg_widget_stroke
                            else R.drawable.bg_widget_stroke_rect
            val fallbackBgRes = if (shouldClip) R.drawable.bg_widget_mask
                                else R.drawable.bg_widget_mask_rect

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    rv.setBoolean(R.id.widget_root, "setClipToOutline", shouldClip)
                } catch (_: Exception) {
                    // setClipToOutline 走反射调用，个别 ROM 会抛，静默忽略
                }
            }

            val alpha = (appearance.bgOpacity / 100f * 255).toInt()
            val bgBitmap = getOrCreateBgBitmap(context, appearance, pageBg, cornerRadiusDp)
            if (bgBitmap != null) {
                rv.setImageViewBitmap(R.id.widget_bg_image, bgBitmap)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", Color.TRANSPARENT)
            } else {
                // 兜底：Bitmap 创建失败时用 drawable 着色
                rv.setImageViewResource(R.id.widget_bg_image, fallbackBgRes)
                rv.setInt(R.id.widget_bg_image, "setColorFilter", pageBg)
            }
            rv.setInt(R.id.widget_bg_image, "setImageAlpha", alpha)

            rv.setImageViewResource(R.id.widget_bg_stroke, strokeRes)
            rv.setInt(R.id.widget_bg_stroke, "setColorFilter", divider)
            rv.setInt(R.id.widget_bg_stroke, "setImageAlpha", alpha)
        }

        /**
         * 4×2 动态配色独立着色：壁纸主色 + 系统暗色模式。
         * 文字色使用 textPrimary 保证可读性，核心数据值使用 dataHighlight
         * 增强视觉层次感。
         */
        private fun applyWidgetThemeDynamic(
            context: Context,
            rv: RemoteViews,
            appearance: WidgetAppearance
        ) {
            val colors = resolveWidgetColors(context, appearance)
            val textColor = colors.text
            val dataColor = colors.data

            applyChassis(context, rv, colors.pageBg, colors.divider, appearance)

            // ── 文字色：标签类使用 dataColor 与图标保持同色（参考图标写法）──
            for (id in listOf(
                R.id.tv_model, R.id.tv_version, R.id.tv_app_ver_code,
                R.id.tv_no_network,
                R.id.tv_daily_label, R.id.tv_daily_unit,
                R.id.tv_flow_label, R.id.tv_flow_unit,
                R.id.tv_update_time,
                R.id.tv_carrier, R.id.tv_net_type
            )) {
                safeSetTextColor(rv, id, dataColor)
            }
            // 核心数据值（流量、温度、CPU、内存、电量、信号）
            for (id in listOf(
                R.id.tv_daily, R.id.tv_flow,
                R.id.tv_temp, R.id.tv_cpu, R.id.tv_mem,
                R.id.tv_battery, R.id.tv_signal_dbm,
                R.id.tv_rsrp, R.id.tv_sinr, R.id.tv_band, R.id.tv_pci
            )) {
                safeSetTextColor(rv, id, dataColor)
            }

            // ── 分割线（使用 dataColor 与图标同色，参考图标写法修复）──
            rv.setInt(R.id.divider_flow, "setBackgroundColor", dataColor)

            // ── 图标着色（使用 dataHighlight 与数据值保持一致，体现动态取色）──
            for (id in THEME_ICON_IDS) {
                safeSetImageViewTint(rv, id, dataColor)
            }

            // ── 错误覆盖层着色（图标用 dataHighlight，文字用 textPrimary）──
            safeSetImageViewTint(rv, R.id.widget_error_icon, dataColor)
            safeSetTextColor(rv, R.id.widget_error_text, textColor)
            safeSetTextColor(rv, R.id.widget_error_hint, textColor)
        }

        /** 从电量百分比推算 0-4 格电量图标等级 */
        /**
         * 电池图标：直接按 `battery_percent` 数值取格数。
         *
         * 不要回去解析 `battery` 那个展示串 —— 它只保证是 "85%" 或 "--"，
         * 一旦上游改成带后缀的文案，`toIntOrNull()` 就恒为 null，图标会永远停在空电池那一格。
         *
         * @param charging 充电指示。true 时整体换成腔体内带闪电的 [R.drawable.ic_battery_charging]，
         *                 而不是在旁边再画一个 ⚡ 文本 —— emoji 无法被 setColorFilter 着色，
         *                 主题/动态配色一换就和其他图标不同色。具体电量由旁边的百分比文本承担
         */
        private fun batteryIconRes(percent: Int, charging: Boolean): Int = when {
            charging -> R.drawable.ic_battery_charging
            percent >= 90 -> R.drawable.ic_battery_4
            percent >= 70 -> R.drawable.ic_battery_3
            percent >= 40 -> R.drawable.ic_battery_2
            percent >= 15 -> R.drawable.ic_battery_1
            else -> R.drawable.ic_battery_0
        }

        /**
         * 电量文本：只写百分比，充电状态由 [batteryIconRes] 换图表达。
         *
         * 不要往这里拼充电文案：文案长度会随充电状态跳变，把首行挤到换行，
         * 首行一高、整列内容就超出 4×2 的可视高度，最后一行的更新时间直接被顶出组件外
         * —— 表现为「更新时间不显示」。
         *
         * @param raw 无 `battery_percent` 缓存时的兜底串（老版本升级上来的第一轮渲染）
         */
        private fun batteryText(percent: Int, raw: String): String =
            if (percent >= 0) "$percent%" else raw

        /**
         * 信号格数图标：入参是 [parseSignalLevel] 的 0-5 结果。
         *
         * 四种尺寸原先各抄了一份同样的 when 表，改一处漏三处，所以收敛到这里。
         */
        private fun signalIconRes(level: Int): Int = when (level) {
            1 -> R.drawable.ic_signal_1
            2 -> R.drawable.ic_signal_2
            3 -> R.drawable.ic_signal_3
            4 -> R.drawable.ic_signal_4
            5 -> R.drawable.ic_signal_5
            else -> R.drawable.ic_signal_0
        }

        /**
         * 网络制式图标。
         *
         * 判定顺序不能动：`"4G+"` 同时含子串 `"4G"`，把 4G 分支提到 4G+ 前面
         * 会让 4G+ 永远显示成 4G。5G 优先是因为部分设备会报 `"5G NSA"`。
         * 兜底给 4G 而不是「未知」—— 无制式数据的场景在上层就走 tv_no_network 了。
         */
        private fun networkIconRes(netType: String): Int = when {
            netType.contains("5G", true) -> R.drawable.ic_network_5g
            netType.contains("4G+", true) || netType.contains("LTE+", true) -> R.drawable.ic_network_4g_plus
            netType.contains("4G", true) || netType.contains("LTE", true) -> R.drawable.ic_network_4g
            netType.contains("3G", true) || netType.contains("WCDMA", true) -> R.drawable.ic_network_3g
            netType.contains("2G", true) || netType.contains("GSM", true) -> R.drawable.ic_network_2g
            else -> R.drawable.ic_network_4g
        }

        /**
         * 网络槽位显隐：图标与「无网络」占位文字互斥，用户关掉该槽位时两者都隐藏。
         *
         * @param show 用户在设置里是否开启了这个槽位
         * @param hasNetworkData 是否拿到了可信的制式数据
         */
        private fun applyNetworkSlot(rv: RemoteViews, show: Boolean, hasNetworkData: Boolean) {
            safeSetVisibility(rv, R.id.iv_network, show && hasNetworkData)
            safeSetVisibility(rv, R.id.tv_no_network, show && !hasNetworkData)
        }

        /**
         * 大字槽位的一次取值：数值 / 单位 / 标签，分别写进 tv_flow / tv_flow_unit / tv_flow_label。
         *
         * 数值与单位刻意分开：单位用小一号字号贴在大数字右下，整串塞进 tv_flow 会让
         * 「12.34GB」和「-95dBm」的视觉重量完全不同。
         */
        private class CenterValue(val value: String, val unit: String, val label: String)

        /**
         * 当前启用的大字候选指标，顺序与 [WidgetRegistry.CENTER_METRIC_FIELDS] 一致。
         *
         * 能力不支持的项直接排除，否则用户开了「大字 · CPU 占用」但数据源不给 CPU，
         * 轮播到那一项就是一个不会变的 `--`。
         */
        private fun enabledCenterKeys(context: Context, scope: WidgetScope): List<String> {
            val caps = DeviceDataSourceRegistry.currentCapabilities(context)
            val centerKeys = WidgetRegistry.CENTER_METRIC_FIELDS.map { it.key }.toSet()
            // 候选取自「当前数据源实际会用的那套变体」，这样双击轮播顺序 == 设置页列出的顺序。
            // 拿不到 spec（理论上不会）时退回全量声明
            val candidates = WidgetRegistry.byKind(scope.kind)
                ?.variantFor(caps)?.fields?.filter { it.key in centerKeys }
                ?: WidgetRegistry.CENTER_METRIC_FIELDS
            val available = candidates.filter { it.requires?.isSupported(caps) ?: true }
            val picked = available.filter { scope.show(it.key, it.default) }
            // 一项都没剩时兜底到第一项：用户可能只勾了「温度」这类当前数据源没有的指标，
            // 返回空会让 1×1 中间整块消失，桌面上看起来像组件坏了
            return picked.ifEmpty { available.take(1) }.map { it.key }
        }

        /** 单个指标的取值。数值一律剥掉单位后缀，单位由 [CenterValue.unit] 单独承担 */
        private fun centerValueOf(context: Context, key: String): CenterValue {
            val sp = SPUtil.getSp(context)
            fun strip(raw: String, suffix: String) =
                raw.replace(suffix, "", ignoreCase = true).trim().ifEmpty { "--" }
            return when (key) {
                WidgetPrefs.CENTER_FLOW ->
                    CenterValue(strip(sp.getString("flow", "--") ?: "--", "GB"), "GB", "本月流量")
                WidgetPrefs.CENTER_DAILY ->
                    CenterValue(strip(sp.getString("daily_flow", "--") ?: "--", "GB"), "GB", "今日流量")
                WidgetPrefs.CENTER_SIGNAL ->
                    CenterValue(strip(sp.getString("signal", "--") ?: "--", "dBm"), "dBm", "信号强度")
                WidgetPrefs.CENTER_BATTERY -> {
                    val pct = sp.getInt("battery_percent", -1)
                    CenterValue(if (pct >= 0) "$pct" else "--", "%", "电池电量")
                }
                WidgetPrefs.CENTER_TEMP -> CenterValue(
                    strip(normalizeTempString(sp.getString("temp", "--") ?: "--"), "°C"),
                    "°C", "设备温度"
                )
                WidgetPrefs.CENTER_CPU ->
                    CenterValue(strip(sp.getString("cpu", "--") ?: "--", "%"), "%", "CPU 占用")
                WidgetPrefs.CENTER_MEM ->
                    CenterValue(strip(sp.getString("mem", "--") ?: "--", "%"), "%", "内存占用")
                else -> CenterValue("--", "", "")
            }
        }

        /**
         * 把大字轮播推进 [delta] 项。
         *
         * 只作用于类型层（`appWidgetId = null`）：点击 PendingIntent 是按 provider 建的，
         * 广播里拿不到「被点的是哪个实例」，所以同形态的多个实例会一起切。
         *
         * @param delta 正数往后、负数往前。三击时用 -1 撤销第二击造成的轮播
         * @return true 表示确实切了；启用项少于 2 个时返回 false，让调用方回落到别的行为
         */
        fun stepCenterMetric(context: Context, kind: String, delta: Int): Boolean {
            val scope = WidgetScope(context, kind)
            val keys = enabledCenterKeys(context, scope)
            if (keys.size < 2) return false
            val next = (scope.int(WidgetPrefs.CENTER_INDEX, 0) + delta).mod(keys.size)
            WidgetPrefs.setInt(context, kind, WidgetPrefs.CENTER_INDEX, next)
            // force：轮播只动了 center_index，若走哈希去重会被当成「数据没变」直接跳过
            renderAllWidgets(context, force = true)
            return true
        }

        /** 2×1 迷你小组件：中间可切换的大字 + 右上角信号/制式/电量图标 */
        internal fun performRender2x1(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val sp = SPUtil.getSp(context)
            if (applyStateOverlay(context, rv).handled) return

            val signal = sp.getString("signal", "--") ?: "--"
            val netType = netTypeOf(context)  // 优先 AT（稳定）→ Goform 回退
            val hasNetworkData = netType.isNotEmpty() && signal != "--"
            val batteryPercent = sp.getInt("battery_percent", -1)
            val charging = sp.getBoolean("battery_charging", false)

            // ===== 中间大字：取当前轮播位置对应的那一项 =====
            val centerKeys = enabledCenterKeys(context, scope)
            val center = centerKeys.getOrNull(
                if (centerKeys.isEmpty()) -1
                else scope.int(WidgetPrefs.CENTER_INDEX, 0).mod(centerKeys.size)
            )?.let { centerValueOf(context, it) }

            // 单位是不是贴在数值后面。
            // 1×1 把「数值 / 单位 / 标签」拆成三行，是为了「GB」这种两字符单位并排时
            // 会把大字挤出可见区；℃ / % 这类单字符单位并排完全放得下，单独占一行既浪费
            // 一行高度，也让「45」和「℃」看着像两个不相干的数
            val inlineUnit = center != null && center.unit.length <= 1 && center.unit.isNotEmpty()

            safeSetText(
                rv, R.id.tv_flow,
                if (center == null) "--"
                else if (inlineUnit) "${center.value}${center.unit}"
                else center.value
            )
            safeSetText(rv, R.id.tv_flow_unit, if (inlineUnit) "" else (center?.unit ?: ""))
            safeSetText(rv, R.id.tv_flow_label, center?.label ?: "")

            // 一项都没选时整块隐藏，而不是留一个孤零零的 "--"
            val showCenter = center != null
            safeSetVisibility(rv, R.id.tv_flow, showCenter)
            safeSetVisibility(
                rv, R.id.tv_flow_unit,
                center != null && !inlineUnit && center.unit.isNotEmpty()
            )
            safeSetVisibility(rv, R.id.tv_flow_label, showCenter)

            // ===== 右上角图标：1×1 固定显示，不读 show_* 开关 =====
            // 设置页已经不给这三项开关（见 WidgetRegistry 的 fixedNote），如果这里还按
            // scope.show() 渲染，老版本里被关掉的用户就永远看不到图标、且没有入口打开
            safeSetImageResource(rv, R.id.iv_signal_bars, signalIconRes(parseSignalLevel(signal)))
            safeSetImageResource(rv, R.id.iv_battery, batteryIconRes(batteryPercent, charging))
            if (hasNetworkData) {
                safeSetImageResource(rv, R.id.iv_network, networkIconRes(netType))
            }
            safeSetVisibility(rv, R.id.iv_signal_bars, true)
            // 制式槽位仍按「有没有数据」隐藏 —— 没数据时留个空图标位比隐藏更难看
            applyNetworkSlot(rv, true, hasNetworkData)
            // 电量同理：goform 直连有一部分机型固件整组裁掉 battery_* 字段，
            // 这时留一个空电池壳子会被当成「电量 0」，不如隐藏
            safeSetVisibility(rv, R.id.iv_battery, batteryPercent in 0..100)
        }

        /** 2×1 迷你小组件主题着色 */
        internal fun applyWidgetTheme2x1(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val appearance = appearanceOf(context, scope)
            val colors = resolveWidgetColors(context, appearance)
            applyChassis(context, rv, colors.pageBg, colors.divider, appearance)

            // 标签/单位用 text，大字数值用 data —— 静态主题下两者同色，动态配色下才拉开层次
            for (id in listOf(R.id.tv_flow_label, R.id.tv_flow_unit, R.id.tv_no_network)) {
                safeSetTextColor(rv, id, colors.text)
            }
            safeSetTextColor(rv, R.id.tv_flow, colors.data)

            // 图标着色
            for (id in listOf(R.id.iv_signal_bars, R.id.iv_network, R.id.iv_battery)) {
                safeSetImageViewTint(rv, id, colors.data)
            }

            // 1×1 独立字体大小。基准值与 widget_1x1.xml 的 textSize 保持一致
            val fontSize2x1 = scope.int(WidgetPrefs.FONT_SIZE, 9).toFloat()
            val defaultBase2x1 = 9f
            if (fontSize2x1 != defaultBase2x1) {
                val ratio = fontSize2x1 / defaultBase2x1
                safeSetTextSize(rv, R.id.tv_flow, 24f * ratio)
                safeSetTextSize(rv, R.id.tv_flow_unit, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_flow_label, 8f * ratio)
            }

            // 错误覆盖层着色
            safeSetImageViewTint(rv, R.id.widget_error_icon, colors.data)
        }

        /**
         * 2×2 方块小组件：型号 + 信号 + 本月/今日流量 + 电量 + 温度 + 更新时间。
         *
         * 与 4×1 的取舍差别：2×2 有纵向空间，所以把流量做成大数字主角
         * （这也是用户最常盯的一项），信号/电量/温度退成一行小字。
         */
        internal fun performRender2x2(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val sp = SPUtil.getSp(context)
            val overlay = applyStateOverlay(context, rv, showRetryOverlay = true)
            if (overlay.handled) return

            val model = sp.getString("model", "--") ?: "--"
            val deviceModel = sp.getString("device_model", model) ?: model
            val flow = sp.getString("flow", "--") ?: "--"
            val daily = sp.getString("daily_flow", "--") ?: "--"
            val signal = sp.getString("signal", "--") ?: "--"
            val temp = sp.getString("temp", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val batteryPercent = sp.getInt("battery_percent", -1)
            val charging = sp.getBoolean("battery_charging", false)
            // 优先 AT（稳定）→ Goform 回退
            val netType = netTypeOf(context)
            val hasNetworkData = netType.isNotEmpty() && signal != "--"
            val updateTime = sp.getString("update_time", "--") ?: "--"

            safeSetImageResource(rv, R.id.iv_router, R.drawable.ic_router)
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })

            safeSetImageResource(rv, R.id.iv_signal_bars, signalIconRes(parseSignalLevel(signal)))
            if (hasNetworkData) {
                safeSetImageResource(rv, R.id.iv_network, networkIconRes(netType))
            }
            safeSetText(rv, R.id.tv_signal_dbm, signal)

            // 单位已经写死在布局里（tv_flow_unit / tv_daily_unit），数值这里剥掉重复的 GB
            safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())
            safeSetText(rv, R.id.tv_daily, daily.replace("GB", "").trim())

            // 电量图标：充电时换成腔体内带闪电的那张
            safeSetImageResource(
                rv, R.id.iv_battery,
                batteryIconRes(batteryPercent, charging && scope.show(WidgetPrefs.SHOW_CHARGING))
            )
            safeSetText(rv, R.id.tv_battery, batteryText(batteryPercent, battery))

            safeSetText(rv, R.id.tv_temp, normalizeTempString(temp))

            // 守卫暂停时把原因写在这里，否则用户只能看到一份不带说明的过期数据
            safeSetText(rv, R.id.tv_update_time, overlay.pausedReason.ifEmpty { updateTime })

            val caps = DeviceDataSourceRegistry.currentCapabilities(context)
            val showModel = scope.show(WidgetPrefs.SHOW_MODEL)
            val showSignal = scope.show(WidgetPrefs.SHOW_SIGNAL)
            val showFlow = scope.show(WidgetPrefs.SHOW_FLOW)
            val showDaily = scope.show(WidgetPrefs.SHOW_DAILY) &&
                Capability.DAILY_TRAFFIC.isSupported(caps)
            val showBattery = scope.show(WidgetPrefs.SHOW_BATTERY)
            val showTemp = scope.show(WidgetPrefs.SHOW_TEMP) &&
                Capability.TEMPERATURE.isSupported(caps)
            val showTime = scope.show(WidgetPrefs.SHOW_TIME)
            val showDivider = scope.show(WidgetPrefs.SHOW_DIVIDER)

            safeSetVisibility(rv, R.id.iv_router, showModel)
            safeSetVisibility(rv, R.id.tv_model, showModel)

            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal)
            safeSetVisibility(rv, R.id.tv_signal_dbm, showSignal)
            applyNetworkSlot(rv, showSignal, hasNetworkData)

            safeSetVisibility(rv, R.id.tv_flow, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_label, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_unit, showFlow)
            safeSetVisibility(rv, R.id.tv_daily, showDaily)
            safeSetVisibility(rv, R.id.tv_daily_label, showDaily)
            safeSetVisibility(rv, R.id.tv_daily_unit, showDaily)
            // 流量整块关掉时分隔线上下都没内容了，留着就是一条无意义的横线
            safeSetVisibility(rv, R.id.divider_flow, showDivider && (showFlow || showDaily))

            safeSetVisibility(rv, R.id.iv_battery, showBattery)
            safeSetVisibility(rv, R.id.tv_battery, showBattery)
            safeSetVisibility(rv, R.id.iv_temp, showTemp)
            safeSetVisibility(rv, R.id.tv_temp, showTemp)
            safeSetVisibility(rv, R.id.tv_update_time, showTime)
        }

        /**
         * goform 专用 2×2 渲染（布局 widget_goform_2x2）。
         *
         * 通用 2×2 底部两行是「今日流量」和「温度 / dBm / 时间」，goform 协议这两样都没有
         * （流量只有月累计），在这个数据源下等于白占两行。换成运营商 + 制式 + SINR、
         * 频段 + RSRP + 时间。
         */
        internal fun performRenderGoform2x2(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val sp = SPUtil.getSp(context)
            val overlay = applyStateOverlay(context, rv, showRetryOverlay = true)
            if (overlay.handled) return

            val model = sp.getString("model", "--") ?: "--"
            val deviceModel = sp.getString("device_model", model) ?: model
            val flow = sp.getString("flow", "--") ?: "--"
            val signal = sp.getString("signal", "--") ?: "--"
            val battery = sp.getString("battery", "--") ?: "--"
            val netType = netTypeOf(context)
            val carrier = sp.getString("at_carrier", "") ?: ""
            val band = sp.getString("at_band", "") ?: ""
            val sinr = sp.getInt("at_sinr", Int.MIN_VALUE)
            val hasNetworkData = netType.isNotEmpty() && signal != "--"
            val batteryPercent = sp.getInt("battery_percent", -1)
            val charging = sp.getBoolean("battery_charging", false)
            val updateTime = sp.getString("update_time", "--") ?: "--"

            // ===== 顶栏 =====
            safeSetImageResource(rv, R.id.iv_router, R.drawable.ic_router)
            safeSetText(rv, R.id.tv_model, deviceModel.ifEmpty { model })
            safeSetImageResource(rv, R.id.iv_signal_bars, signalIconRes(parseSignalLevel(signal)))
            if (hasNetworkData) {
                safeSetImageResource(rv, R.id.iv_network, networkIconRes(netType))
            }
            safeSetImageResource(
                rv, R.id.iv_battery,
                batteryIconRes(batteryPercent, charging && scope.show(WidgetPrefs.SHOW_CHARGING))
            )
            safeSetText(rv, R.id.tv_battery, batteryText(batteryPercent, battery))

            // ===== 中间大字 =====
            safeSetText(rv, R.id.tv_flow, flow.replace("GB", "").trim())

            // ===== 底部两行 =====
            safeSetText(rv, R.id.tv_carrier, carrier)
            safeSetText(rv, R.id.tv_net_type, netType.ifEmpty { "无网络" })
            safeSetText(rv, R.id.tv_sinr, "SINR " + if (sinr == Int.MIN_VALUE) "--" else "$sinr")
            safeSetImageResource(rv, R.id.iv_band, R.drawable.ic_blur)
            safeSetText(rv, R.id.tv_band, band.ifEmpty { "--" })
            safeSetText(rv, R.id.tv_signal_dbm, signal)
            safeSetText(rv, R.id.tv_update_time, overlay.pausedReason.ifEmpty { updateTime })

            // ===== 显隐 =====
            val showModel = scope.show(WidgetPrefs.SHOW_MODEL)
            val showSignal = scope.show(WidgetPrefs.SHOW_SIGNAL)
            val showFlow = scope.show(WidgetPrefs.SHOW_FLOW)
            val showCarrier = scope.show(WidgetPrefs.SHOW_CARRIER)
            val showBand = scope.show(WidgetPrefs.SHOW_BAND)
            val showBattery = scope.show(WidgetPrefs.SHOW_BATTERY)
            val showTime = scope.show(WidgetPrefs.SHOW_TIME)
            val showDivider = scope.show(WidgetPrefs.SHOW_DIVIDER)

            val hasBattery = batteryPercent in 0..100
            val hasSinr = sinr != Int.MIN_VALUE
            val hasBand = band.isNotEmpty()

            safeSetVisibility(rv, R.id.iv_router, showModel)
            safeSetVisibility(rv, R.id.tv_model, showModel)

            safeSetVisibility(rv, R.id.iv_signal_bars, showSignal)
            safeSetVisibility(rv, R.id.tv_signal_dbm, showSignal)
            applyNetworkSlot(rv, showSignal, hasNetworkData)

            safeSetVisibility(rv, R.id.tv_flow, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_label, showFlow)
            safeSetVisibility(rv, R.id.tv_flow_unit, showFlow)
            safeSetVisibility(rv, R.id.divider_flow, showDivider && showFlow)

            // 运营商没解析出来时只留制式，不显示占位文案
            safeSetVisibility(rv, R.id.tv_carrier, showCarrier && carrier.isNotEmpty())
            safeSetVisibility(rv, R.id.tv_net_type, showCarrier)
            // 图标与文本同进同退，否则关掉信号详情后会留一个孤零零的频段图标
            safeSetVisibility(rv, R.id.tv_sinr, showBand && hasSinr)
            safeSetVisibility(rv, R.id.iv_band, showBand && hasBand)
            safeSetVisibility(rv, R.id.tv_band, showBand && hasBand)

            safeSetVisibility(rv, R.id.iv_battery, showBattery && hasBattery)
            safeSetVisibility(rv, R.id.tv_battery, showBattery && hasBattery)
            safeSetVisibility(rv, R.id.tv_update_time, showTime)
        }

        /** 2×2 方块小组件主题着色 */
        internal fun applyWidgetTheme2x2(context: Context, rv: RemoteViews, scope: WidgetScope) {

            val appearance = appearanceOf(context, scope)
            val colors = resolveWidgetColors(context, appearance)
            applyChassis(context, rv, colors.pageBg, colors.divider, appearance)

            // 标签类（"本月" / "今日" / "GB" / 时间）用 text，数值与图标用 data ——
            // 静态主题下两者同色，动态配色下才拉开层次。
            // goform 变体独有的 id 一起写进来：safeSet* 找不到 id 会静默跳过，两套布局共用这份着色
            for (id in listOf(
                R.id.tv_flow_label, R.id.tv_flow_unit,
                R.id.tv_daily_label, R.id.tv_daily_unit,
                R.id.tv_no_network, R.id.tv_update_time,
                R.id.tv_net_type, R.id.tv_sinr
            )) {
                safeSetTextColor(rv, id, colors.text)
            }
            for (id in listOf(
                R.id.tv_model, R.id.tv_signal_dbm, R.id.tv_flow,
                R.id.tv_daily, R.id.tv_battery, R.id.tv_temp,
                R.id.tv_carrier, R.id.tv_band
            )) {
                safeSetTextColor(rv, id, colors.data)
            }

            for (id in listOf(
                R.id.iv_router, R.id.iv_signal_bars, R.id.iv_network,
                R.id.iv_battery, R.id.iv_temp, R.id.iv_band
            )) {
                safeSetImageViewTint(rv, id, colors.data)
            }

            rv.setInt(R.id.divider_flow, "setBackgroundColor", colors.divider)

            // 2×2 独立字体大小：滑块默认 9 为 1 倍，各项基准值与 widget_2x2.xml 的 textSize 一致
            val fontSize = scope.int(WidgetPrefs.FONT_SIZE, 9).toFloat()
            val defaultBase = 9f
            if (fontSize != defaultBase) {
                val ratio = fontSize / defaultBase
                safeSetTextSize(rv, R.id.tv_model, 12f * ratio)
                safeSetTextSize(rv, R.id.tv_signal_dbm, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_flow, 30f * ratio)
                safeSetTextSize(rv, R.id.tv_flow_label, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_flow_unit, 12f * ratio)
                safeSetTextSize(rv, R.id.tv_daily, 14f * ratio)
                safeSetTextSize(rv, R.id.tv_daily_label, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_daily_unit, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_battery, 12f * ratio)
                safeSetTextSize(rv, R.id.tv_temp, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_update_time, 10f * ratio)
                // goform 变体独有的四项，基准值取自 widget_goform_2x2.xml
                safeSetTextSize(rv, R.id.tv_carrier, 12f * ratio)
                safeSetTextSize(rv, R.id.tv_net_type, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_sinr, 10f * ratio)
                safeSetTextSize(rv, R.id.tv_band, 10f * ratio)
            }

            // 错误覆盖层着色
            safeSetImageViewTint(rv, R.id.widget_error_icon, colors.data)
            safeSetTextColor(rv, R.id.widget_error_text, colors.text)
            safeSetTextColor(rv, R.id.widget_error_hint, colors.text)
        }

        /**
         * 确保小组件周期性后台刷新任务已注册。
         * 使用 KEEP 策略：如果已有定时任务（被主 Activity 注册过）则不动，
         * 如果没有则创建。这样小组件完全独立于主应用进程运行。
         */
        fun ensurePeriodicWorker(context: Context) {
            try {
                WifiWorker.schedulePeriodic(
                    context, SPUtil.getRefreshInterval(context), keepExisting = true
                )
            } catch (e: Exception) {
                Log.w(TAG, "ensurePeriodicWorker failed: ${e.message}", e)
            }
        }

        /**
         * 守卫放行时立即触发一次采集，用于「条件恢复的瞬间补一刷」。
         *
         * 与 [BaseWifiWidget.triggerWorker] 的区别：不设 reconnecting 标记
         * （那是给「用户点了刷新」用的加载覆盖层，系统事件触发时不该弹）。
         *
         * 必须走 [WifiGuard.evaluate]：这里是新增的采集入口，绕过守卫就等于给
         * 「息屏暂停 / 指定 Wi-Fi」开了后门。
         *
         * @return true 表示已入队
         */
        fun requestImmediateRefresh(context: Context, reason: String): Boolean {
            return try {
                val guard = WifiGuard.evaluate(context)
                if (!guard.allowed) {
                    DebugLogger.d(TAG, "requestImmediateRefresh($reason) 被守卫拦下: $guard")
                    // 守卫状态可能刚发生变化（如刚息屏），重渲染让暂停原因显示出来
                    renderAllWidgets(context, force = true)
                    return false
                }
                DebugLogger.logSys(TAG, "requestImmediateRefresh: $reason")
                WifiWorker.enqueueOneShot(context)
                true
            } catch (e: Exception) {
                Log.w(TAG, "requestImmediateRefresh failed: ${e.message}")
                false
            }
        }

    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // 通过 renderAllWidgets 统一走 renderLock，避免与 Worker 并发的 Bitmap 竞态
        renderAllWidgets(context, force = true)
        ensurePeriodicWorker(context)
        triggerWorker(context)
    }

    /** 第一个小组件被添加到桌面时确保定时刷新已注册 */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ensurePeriodicWorker(context)
    }

    /**
     * 实例被移除时清理它的实例级配置。
     *
     * 系统会回收并复用 appWidgetId，不清理的话新组件会莫名继承上一个组件的配置，
     * 同时 SP 里的 `widget.<kind>.<id>.*` 会无限堆积。
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val kind = WidgetRegistry.byProvider(this::class.java)?.kind ?: return
        for (id in appWidgetIds) {
            WidgetPrefs.clearInstance(context, kind, id)
        }
        // 实例的独立背景与独立历史随上面的清理一起消失，只被它引用的图片文件就成了孤儿。
        // 逐个追踪容易漏，按「当前还有没有键引用」全量扫一遍
        SPUtil.sweepUnusedWidgetBgFiles(context)
    }

    /** 小组件尺寸变化时自动重绘以适应新尺寸 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        val newWidth = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 248)
        appendLog(context, "尺寸变化 → ${newWidth}dp，重新渲染")
        // 通过 renderAllWidgets 统一走 renderLock，避免 Bitmap 竞态
        renderAllWidgets(context, force = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_REFRESH) return

        // provider 的 receiver 必须 exported（否则系统投递不到 APPWIDGET_UPDATE），
        // 所以只比对 action 等于任何应用都能伪造这条广播去污染连击计数、切设备配置档、
        // 反复唤醒采集。校验串存在 MODE_PRIVATE 的 SP 里，外部读不到
        if (intent.getStringExtra(EXTRA_TAP_TOKEN) != SPUtil.getWidgetTapToken(context)) return

        // RemoteViews 没有连击手势，只能靠相邻点击广播之间的时间差累计连击数。
        // 计数必须是这里的第一件事：manifest 声明的 receiver 广播是串行投递的，
        // 上一下没处理完下一下就进不来，处理越久相邻时间差越大、越容易掉出判定窗口。
        // 改造前第一下就同步做整轮渲染 + WorkManager 入队，这正是「双击要点很多次
        // 才成功」的根因，所以现在 onReceive 里只做计数和排队，重活一律不占坑。
        val taps = SPUtil.bumpWidgetTapCount(context)
        val kind = WidgetRegistry.byProvider(this::class.java)?.kind

        // 这一下让上一下排的单击刷新失去意义，先撤掉；撤不掉（进程换了）也没关系，
        // 动作执行前还会用连击数复核一次
        pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
        pendingSingleTap = null

        when {
            // 双击：轮播大字。手势落在哪个组件上，就先动那个组件自己的显示
            taps == 2 -> {
                if (kind != null && stepCenterMetric(context, kind, 1)) {
                    appendLog(context, "双击切换大字指标")
                }
                return
            }
            // 三击：先把第二击的轮播退回去，再切配置档 —— 否则用户三击换设备时
            // 大字会顺带跳一格，看着像误触
            taps == 3 -> {
                if (kind != null) stepCenterMetric(context, kind, -1)
                if (SPUtil.getWidgetTripleTapSwitch(context)) {
                    val next = DeviceProfiles.cycleNext(context)
                    appendLog(
                        context,
                        if (next != null) "三击切换配置档 → ${DeviceProfiles.displayName(context, next)}"
                        else "三击切换已跳过：可循环的配置档不足 2 个"
                    )
                }
                return
            }
            // 四击及以后：连击已经没有对应动作，直接丢弃，别再排一次抓取
            taps > 3 -> return
        }

        // 单击：延后一小段再刷新，把这段时间让给可能的第二下（见 SINGLE_TAP_DELAY_MS）。
        // 排队期间进程被回收会导致这次刷新丢掉，但单击刷新是幂等的、再点一下即可，
        // 拿它换连击的可靠性划算得多。
        // 捕获 applicationContext 而不是广播给的 context：后者是 receiver 受限上下文，
        // onReceive 返回后不该再拿它做事，也不能被静态字段长期持有。
        val appCtx = context.applicationContext
        val action = Runnable {
            pendingSingleTap = null
            // 复核：这一串已经变成连击了，对应动作早就执行过，单击的那份必须放弃
            if (SPUtil.getWidgetTapCount(appCtx) > 1) return@Runnable
            appendLog(appCtx, "点击刷新触发")
            triggerWorker(appCtx)
        }
        pendingSingleTap = action
        tapHandler.postDelayed(action, SINGLE_TAP_DELAY_MS)
    }

    protected fun triggerWorker(context: Context) {
        try {
            // 指定 Wi-Fi 守卫拦停时点击不触发抓取，也不进入 reconnecting，
            // 否则会出现"正在重试..."但永远不会成功的死状态。
            val guard = WifiGuard.evaluate(context)
            if (!guard.allowed) {
                appendLog(context, "点击刷新已跳过：${WifiGuard.blockedReason(guard)}")
                SPUtil.setReconnecting(context, false)
                renderAllWidgets(context, force = true)
                return
            }
            // 设置重试状态标记，立即刷新小组件显示加载覆盖层，提示用户刷新已触发
            SPUtil.setReconnecting(context, true)
            renderAllWidgets(context, force = true)
            // 不在此处重置失败状态，否则若后续渲染被触发会显示旧缓存数据后再变回断联。
            // Worker 内部有独立的自恢复逻辑 + 清除 reconnecting 标记。
            WifiWorker.enqueueOneShot(context)
        } catch (_: Exception) {
            // WorkManager: 在小组件 onUpdate/onReceive 中调用，捕获如未初始化等异常
            Log.w(TAG, "triggerWorker failed: WorkManager may not be available")
        }
    }

}

open class WifiWidget4x2 : BaseWifiWidget(R.layout.widget_4x2)

/**
 * 影子组件：与 [WifiWidget4x2] 功能完全相同，但在桌面选择器中不显示名称。
 *
 * 通过 [WidgetLabelToggle] 切换原始/影子组件的 enabled 状态，
 * 强制桌面启动器重新读取组件元数据（android:label），实现标签隐藏/显示。
 * 继承自 [WifiWidget4x2]，所有渲染、更新、点击逻辑完全复用。
 */
class WifiWidget4x2NoLabel : WifiWidget4x2()

/** 2×1 迷你版（现声明为 1×1）。渲染由注册表分发。open 是为了给影子组件继承 */
/**
 * 1×1 迷你版。渲染由注册表分发，open 是为了给影子组件继承。
 *
 * 类名里的 2x1 是历史包袱：Manifest 的 receiver 名与 SP 键前缀都按它存，改名等于丢用户配置。
 */
open class WifiWidget2x1 : BaseWifiWidget(R.layout.widget_1x1)

/** 4×1 条形版。渲染由注册表分发。open 是为了给影子组件继承 */
open class WifiWidget4x1 : BaseWifiWidget(R.layout.widget_4x1)

/** 2×2 方块版。渲染由注册表分发。open 是为了给影子组件继承 */
open class WifiWidget2x2 : BaseWifiWidget(R.layout.widget_2x2)

// 影子组件：每个形态都要有自己的一份。
// 「隐藏小组件名称」靠的是「主 receiver 与影子 receiver 的 enabled 互斥切换」，
// 桌面从 receiver 的 android:label 读名字，一个 receiver 只能配一个 label —— 所以
// 少一个影子就少一个能隐藏名称的形态（此前只有 4×2 有，于是只有 4×2 生效）。
class WifiWidget2x1NoLabel : WifiWidget2x1()
class WifiWidget4x1NoLabel : WifiWidget4x1()
class WifiWidget2x2NoLabel : WifiWidget2x2()

