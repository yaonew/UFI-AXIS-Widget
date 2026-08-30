package com.ufi_axis_widget

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.ufi_axis_widget.service.BackgroundMonitorService
import com.ufi_axis_widget.util.*
import com.ufi_axis_widget.util.DebugLogger.Category
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry

import com.ufi_axis_widget.view.ThemeSlider

class NotificationSettingsActivity : AppCompatActivity() {

    private var notifEnabled = false

    // 流量提醒
    private var notifyDailyFlow = false
    private var notifyDailyFlowThresholdGb = 0f
    private var notifyMonthlyFlow = false
    private var notifyMonthlyFlowThresholdGb = 0f

    // 设备状态提醒
    private var notifyTemp = false
    private var notifyTempThreshold = 0
    private var notifyCpu = false
    private var notifyCpuThreshold = 0
    private var notifyDeviceOnline = false
    private var notifyBattery = false
    private var notifyBatteryThreshold = 0
    private var notifyMemory = false
    private var notifyMemoryThreshold = 0

    // 监控设置
    private var monitorIntervalSec = 60

    private var activeDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_notification_settings)
        // ThemeUtil.applyTheme 内部已调用 BackgroundUtil.initActivity → applyWindowBackground
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        DebugLogger.log(Category.SYS, TAG_INIT, "onCreate start")

        initMasterItem()
        initFlowDaily()
        initFlowMonthly()
        initDeviceOnline()
        initDeviceTemp()
        initDeviceCpu()
        initDeviceBattery()
        initDeviceMemory()
        initMonitorInterval()
        initQuietHours()
        initBackgroundKeepAliveEntry()
        initSettingsContainerVisibility()
        applyDataSourceAvailability()

        DebugLogger.log(Category.SYS, TAG_INIT, "onCreate complete")
    }

    /**
     * 按当前数据源能力置灰不可用的监控项。
     *
     * goform 直连协议里没有温度 / CPU / 内存 / 当日流量这几个字段，`GoformDataSource`
     * 填的是字面 `"--"`；`NotificationHelper` 的 parse 系列遇到 `"--"` 一律返回 null
     * 直接 return —— 也就是开关打开也永远不会触发，属于静默失效。这里改成显式置灰，
     * 点击给出原因，避免用户以为是 bug。
     *
     * 提示文案只说「当前数据源不提供」+「换一个支持的数据源」，不点名具体是哪个源
     * —— 数据源是可扩展的，写死目标名字每加一个源都要回来改这里。
     *
     * 必须在各 `initXxx()` 之后调用（它们会绑定点击监听），且要在 onResume 复算，
     * 因为数据源可以在别的页面切换。
     */
    private fun applyDataSourceAvailability() {
        val caps = DeviceDataSourceRegistry.currentCapabilities(this)
        val sourceName = SPUtil.getDataSourceType(this).displayName

        fun apply(viewId: Int, supported: Boolean, metric: String, rebind: () -> Unit) {
            val item = findViewById<View>(viewId) ?: return
            if (supported) {
                CommonSettingsItemHelper.markSupported(item)
                rebind()
            } else {
                CommonSettingsItemHelper.markUnsupported(
                    item,
                    title = "$metric 监控不可用",
                    reason = "当前数据源「$sourceName」不提供$metric 数据，该阈值永远不会触发。" +
                        "请在「配置修改」里换用支持该项的数据源。"
                )
            }
        }

        // 今日流量不在此列：数据源没有日流量字段时，TrafficRecordManager 会用月累计
        // 跨天做差推导出今日用量并缓存，checkDailyFlow 会回退读那个缓存，阈值照样能触发
        apply(R.id.item_device_temp, caps.temperature, "设备温度", ::initDeviceTemp)
        apply(R.id.item_device_cpu, caps.cpu, "CPU 占用", ::initDeviceCpu)
        apply(R.id.item_device_memory, caps.memory, "内存占用", ::initDeviceMemory)

    }

    override fun onResume() {
        super.onResume()
        // ThemeUtil.applyTheme 内部已调用 BackgroundUtil.initActivity → applyWindowBackground
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
        updateAllSubtitles()
        initSettingsContainerVisibility()
        applyDataSourceAvailability()
        // 每次回到页面时检测通知渠道是否被国产 ROM 降级
        checkChannelImportanceAndGuide()
    }

    override fun onPause() {
        super.onPause()
        // 离开页面时将内存日志落盘，防止进程被杀丢失日志
        DebugLogger.flushToFile()
    }

    override fun onDestroy() {
        // 防止 Activity 销毁时弹窗未关闭导致 WindowLeaked 异常
        try { activeDialog?.dismiss() } catch (_: Exception) {}
        activeDialog = null
        super.onDestroy()
    }

    private fun updateAllSubtitles() {
        updateMasterSubtitle()
        updateFlowDailySubtitle()
        updateFlowMonthlySubtitle()
        updateDeviceOnlineSubtitle()
        updateDeviceTempSubtitle()
        updateDeviceCpuSubtitle()
        updateDeviceBatterySubtitle()
        updateDeviceMemorySubtitle()
        updateMonitorIntervalSubtitle()
        updateKeepAliveEntrySubtitle()
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ==================== 1. 总开关（含权限检查） ====================

    private fun initMasterItem() {
        notifEnabled = SPUtil.getNotificationEnabled(this)
        val masterView = findViewById<ViewGroup>(R.id.item_notification_master)
        CommonSettingsItemHelper.setupSwitchItem(
            itemView = masterView,
            iconRes = R.drawable.ic_notification,
            label = "启用通知提醒",
            subtitle = if (notifEnabled) "已开启" else "已关闭",
            initialChecked = notifEnabled,
            onToggle = { checked ->
                if (checked && !NotificationHelper.hasPermission(this@NotificationSettingsActivity)) {
                    // 未授予通知权限 → 回退开关、弹窗引导授权
                    revertMasterSwitch(masterView)
                    showPermissionRequiredDialog()
                } else {
                    notifEnabled = checked
                    SPUtil.setNotificationEnabled(this, checked)
                    updateMasterSubtitle()
                    updateKeepAliveEntrySubtitle()
                    animateSettingsContainer(checked)
                    BackgroundMonitorService.syncState(this)
                    // 同步刷新 Doze 穿透闹钟
                    if (checked) {
                        com.ufi_axis_widget.service.AlarmReceiver.scheduleNext(this)
                    } else {
                        com.ufi_axis_widget.service.AlarmReceiver.cancel(this)
                    }
                    // 开关打开时提示用户配置后台保活
                    if (checked) {
                        showKeepAlivePrompt()
                    }
                }
            }
        )
    }

    /** 强制回退总开关到关闭状态（权限未授予时调用） */
    private fun revertMasterSwitch(masterView: ViewGroup) {
        val thumb = masterView.findViewById<View>(R.id.common_switch_thumb) ?: return
        val track = masterView.findViewById<View>(R.id.common_switch_track) ?: return
        // 滑块归位
        thumb.animate().translationX(0f).setDuration(200).start()
        // 背景还原为关闭色
        val offColor = ThemeColors.accentSecondary(this)
        (track.background as? GradientDrawable)?.setColor(offColor)
        // 状态回退
        notifEnabled = false
        SPUtil.setNotificationEnabled(this, false)
        updateMasterSubtitle()
        updateKeepAliveEntrySubtitle()
        animateSettingsContainer(false)
        BackgroundMonitorService.syncState(this)
        com.ufi_axis_widget.service.AlarmReceiver.cancel(this)
    }

    /** 未授权时弹出引导对话框 */
    private fun showPermissionRequiredDialog() {
        activeDialog = CommonDialogHelper.showCommonDialog(
            context = this,
            title = "需要通知权限",
            iconRes = R.drawable.ic_notification,
            onFill = { content ->
                content.addView(TextView(this).apply {
                    text = "系统通知是本应用的核心功能。\n\n请授予通知权限，以便在流量超标、设备异常时及时收到系统推送。"
                    textSize = 14f
                    setTextColor(ThemeColors.textPrimary(this@NotificationSettingsActivity))
                    setLineSpacing(0f, 1.4f)
                })
            },
            primaryBtnText = "去授权",
            onPrimaryClick = { dialog ->
                dialog.dismiss()
                NotificationHelper.requestPermission(this, REQUEST_NOTIFICATION_PERMISSION)
            },
            secondaryBtnText = "暂不"
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予 → 开启总开关
                notifEnabled = true
                SPUtil.setNotificationEnabled(this, true)
                updateMasterSubtitle()
                updateKeepAliveEntrySubtitle()
                animateSettingsContainer(true)
                BackgroundMonitorService.syncState(this)
                com.ufi_axis_widget.service.AlarmReceiver.scheduleNext(this)
                showKeepAlivePrompt()
            }
            // 重新初始化 master item，使 ThemeUtil.setupSwitch 内部的 isChecked 状态
            // 与 SP 值和视觉状态保持同步，避免因 revertMasterSwitch 导致的内部状态不一致
            initMasterItem()
        }
    }

    // ─── 国产 ROM 通知渠道降级检测 ───

    /**
     * 检测通知渠道是否被系统降级为静默通知，并弹窗引导用户修复。
     *
     * 国产 ROM（ColorOS / MIUI / EMUI / OriginOS 等）常在用户不知情的情况下
     * 将通知渠道降级为"静默通知"，导致无横幅、无铃声。
     * 此方法检测到降级后弹窗引导用户跳转系统设置手动开启。
     */
    private fun checkChannelImportanceAndGuide() {
        if (!notifEnabled) return // 通知未开启，无需检测
        if (NotificationHelper.isChannelImportanceSufficient(this)) return // 渠道正常

        // 避免重复弹窗：用 SP 记录是否已提示过
        val sp = SPUtil.getSp(this)
        val alreadyGuided = sp.getBoolean("channel_importance_guided", false)
        if (alreadyGuided) return

        sp.edit().putBoolean("channel_importance_guided", true).apply()

        activeDialog = PopupViewUtil.showConfirmDialog(
            this,
            title = "通知权限不完整",
            message = "检测到系统已将通知渠道降级为\"静默通知\"。\n\n" +
                    "这通常发生在国产 ROM（ColorOS / MIUI / EMUI 等）上，" +
                    "系统默认关闭了横幅弹出和铃声提醒。\n\n" +
                    "点击\"去设置\"后，请确保开启以下选项：\n" +
                    "· 允许通知 / 横幅通知\n" +
                    "· 铃声和震动\n" +
                    "· 锁屏通知",
            primaryBtnText = "去设置",
            secondaryBtnText = "稍后",
            onConfirm = {
                NotificationHelper.openChannelSettings(this)
            }
        )
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 201
        private const val TAG_INIT = "NotifSettings_Init"
    }

    private fun updateMasterSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_notification_master)
                ?.findViewById<TextView>(R.id.common_switch_subtitle)
                ?.apply {
                    text = if (notifEnabled) "已开启" else "已关闭"
                    visibility = View.VISIBLE
                }
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update master subtitle failed: ${e.message}") }
    }

    // ==================== 设置内容容器显隐 ====================

    /**
     * 打开/关闭总开关时，带动画显示/隐藏所有设置项。
     * 淡入：300ms DecelerateInterpolator
     * 淡出：250ms AccelerateInterpolator，结束后 GONE
     */
    private fun animateSettingsContainer(show: Boolean) {
        val container = findViewById<View>(R.id.layout_notification_content) ?: return
        container.animate().cancel()
        if (show) {
            container.visibility = View.VISIBLE
            container.alpha = 0f
            container.animate()
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            container.animate()
                .alpha(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    container.visibility = View.GONE
                    container.alpha = 1f
                }
                .start()
        }
    }

    /** 初始化设置内容容器显隐（无动画，用于首次加载） */
    private fun initSettingsContainerVisibility() {
        findViewById<View>(R.id.layout_notification_content)?.visibility =
            if (notifEnabled) View.VISIBLE else View.GONE
    }

    // ==================== 2. 今日流量提醒 ====================

    private fun initFlowDaily() {
        notifyDailyFlow = SPUtil.getNotifyDailyFlow(this)
        notifyDailyFlowThresholdGb = SPUtil.getNotifyDailyFlowThreshold(this) / 1_073_741_824f
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_flow_daily),
            iconRes = R.drawable.ic_antenna,
            title = "今日流量提醒",
            subtitle = getFlowDailySubtitle(),
            onClick = { showFlowDailyDialog() }
        )
    }

    private fun getFlowDailySubtitle(): String =
        if (notifyDailyFlow) "今日: ${notifyDailyFlowThresholdGb.toInt()}GB" else "已关闭"

    private fun updateFlowDailySubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_flow_daily)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getFlowDailySubtitle()
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update daily flow subtitle failed: ${e.message}") }
    }

    private fun showFlowDailyDialog() {
        showFlowThresholdDialog(
            title = "今日流量提醒",
            iconRes = R.drawable.ic_antenna,
            switchChecked = notifyDailyFlow,
            currentValue = notifyDailyFlowThresholdGb,
            unit = "GB",
            sliderMin = 1f, sliderMax = 100f,
            presets = listOf(1, 5, 10, 50, 100),
            hasRangeLimit = false,
            onToggle = { checked ->
                notifyDailyFlow = checked
                SPUtil.setNotifyDailyFlow(this, checked)
                updateFlowDailySubtitle()
            },
            onThresholdChange = { value ->
                notifyDailyFlowThresholdGb = value
                SPUtil.setNotifyDailyFlowThreshold(this, (value * 1_073_741_824).toLong())
                updateFlowDailySubtitle()
            }
        )
    }

    // ==================== 3. 本月流量提醒 ====================

    private fun initFlowMonthly() {
        notifyMonthlyFlow = SPUtil.getNotifyMonthlyFlow(this)
        notifyMonthlyFlowThresholdGb = SPUtil.getNotifyMonthlyFlowThreshold(this) / 1_073_741_824f
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_flow_monthly),
            iconRes = R.drawable.ic_antenna,
            title = "本月流量提醒",
            subtitle = getFlowMonthlySubtitle(),
            onClick = { showFlowMonthlyDialog() }
        )
    }

    private fun getFlowMonthlySubtitle(): String =
        if (notifyMonthlyFlow) "本月: ${notifyMonthlyFlowThresholdGb.toInt()}GB" else "已关闭"

    private fun updateFlowMonthlySubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_flow_monthly)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getFlowMonthlySubtitle()
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update monthly flow subtitle failed: ${e.message}") }
    }

    private fun showFlowMonthlyDialog() {
        showFlowThresholdDialog(
            title = "本月流量提醒",
            iconRes = R.drawable.ic_antenna,
            switchChecked = notifyMonthlyFlow,
            currentValue = notifyMonthlyFlowThresholdGb,
            unit = "GB",
            sliderMin = 10f, sliderMax = 500f,
            presets = listOf(10, 50, 100, 200),
            hasRangeLimit = false,
            onToggle = { checked ->
                notifyMonthlyFlow = checked
                SPUtil.setNotifyMonthlyFlow(this, checked)
                updateFlowMonthlySubtitle()
            },
            onThresholdChange = { value ->
                notifyMonthlyFlowThresholdGb = value
                SPUtil.setNotifyMonthlyFlowThreshold(this, (value * 1_073_741_824).toLong())
                updateFlowMonthlySubtitle()
            }
        )
    }

    // ==================== 通用：流量弹窗（自定义输入 + 预设芯片） ====================

    private fun showFlowThresholdDialog(
        title: String,
        iconRes: Int,
        switchChecked: Boolean,
        currentValue: Float,
        unit: String,
        sliderMin: Float,
        sliderMax: Float,
        presets: List<Int>,
        hasRangeLimit: Boolean = true,
        onToggle: (Boolean) -> Unit,
        onThresholdChange: (Float) -> Unit
    ) {
        val dialog = CommonDialogHelper.createAnimatedDialog(this) { activeDialog = null }
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(iconRes)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        // 开关行
        content.addView(createInlineSwitch("启用 $title", null, switchChecked, onToggle))

        // 当前值标签
        val valueLabel = TextView(this).apply {
            text = "${currentValue.toInt()} $unit"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.textPrimary(this@NotificationSettingsActivity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(valueLabel)

        // 预设芯片（快捷选择，先声明供 onSelect 引用）
        var updatePresets: (Int) -> Unit = {}
        val (presetRow, updater) = CommonDialogHelper.createPresetRow(
            context = this,
            values = presets,
            formatLabel = { "$it$unit" },
            currentValue = currentValue.toInt(),
            onSelect = { value ->
                valueLabel.text = "$value $unit"
                onThresholdChange(value.toFloat())
                updatePresets(value)
            }
        )
        updatePresets = updater
        content.addView(presetRow)

        // 自定义输入面板（默认隐藏，点击"自定义"按钮展开）
        val customPanel = CommonDialogHelper.createInputPanel(
            context = this,
            hint = if (hasRangeLimit) "输入 $sliderMin-$sliderMax $unit" else "自定义提醒阈值 ($unit)",
            validate = { text ->
                val v = text.toFloatOrNull()
                when {
                    v == null -> "请输入有效数字"
                    !hasRangeLimit -> if (v < 0) "请输入有效数字" else null
                    v < sliderMin || v > sliderMax -> "请输入 $sliderMin-$sliderMax 之间的值"
                    else -> null
                }
            },
            onConfirm = { text ->
                val v = text.toFloat()
                valueLabel.text = "${v.toInt()} $unit"
                onThresholdChange(v)
                updatePresets(v.toInt())
            }
        )
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // --- 按钮 ---
        val btnPrimary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确认"
        btnPrimary.setOnClickListener { dialog.dismiss() }

        val btnSecondary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = View.VISIBLE
        btnSecondary.text = "自定义"
        btnSecondary.setOnClickListener {
            val showing = customPanel.visibility == View.VISIBLE
            CommonDialogHelper.animatePanelVisibility(customPanel, !showing) {
                if (!showing) {
                    customPanel.findViewWithTag<EditText>("custom_input_field")?.let { et ->
                        if (!hasRangeLimit || (currentValue > 0 && (currentValue < sliderMin || currentValue > sliderMax))) {
                            et.setText(currentValue.toInt().toString())
                        }
                        et.requestFocus()
                    }
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeDialog = dialog
        dialog.show()
    }

    // ==================== 4. 温度过高提醒 ====================

    private fun initDeviceTemp() {
        notifyTemp = SPUtil.getNotifyTemp(this)
        notifyTempThreshold = SPUtil.getNotifyTempThreshold(this)
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_device_temp),
            iconRes = R.drawable.ic_temp,
            title = "温度过高提醒",
            subtitle = getDeviceTempSubtitle(),
            onClick = { showDeviceTempDialog() }
        )
    }

    private fun getDeviceTempSubtitle(): String =
        if (notifyTemp) "${notifyTempThreshold}°C" else "已关闭"

    private fun updateDeviceTempSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_device_temp)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getDeviceTempSubtitle()
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update device temp subtitle failed: ${e.message}") }
    }

    private fun showDeviceTempDialog() {
        showSliderThresholdDialog(
            title = "温度过高提醒",
            iconRes = R.drawable.ic_temp,
            switchChecked = notifyTemp,
            currentValue = notifyTempThreshold.toFloat(),
            unit = "°C",
            sliderMin = 30f, sliderMax = 100f, sliderStep = 1f, tickStep = 5f,
            presets = listOf(40, 50, 60, 70, 80),
            subtitle = "超过设定温度时通知",
            onToggle = { notifyTemp = it; SPUtil.setNotifyTemp(this, it); updateDeviceTempSubtitle() },
            onThresholdChange = { v -> notifyTempThreshold = v.toInt(); SPUtil.setNotifyTempThreshold(this, v.toInt()); updateDeviceTempSubtitle() }
        )
    }

    // ==================== 5. CPU 异常占用提醒 ====================

    private fun initDeviceCpu() {
        notifyCpu = SPUtil.getNotifyCpu(this)
        notifyCpuThreshold = SPUtil.getNotifyCpuThreshold(this)
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_device_cpu),
            iconRes = R.drawable.ic_cpu,
            title = "CPU 异常占用提醒",
            subtitle = getDeviceCpuSubtitle(),
            onClick = { showDeviceCpuDialog() }
        )
    }

    private fun getDeviceCpuSubtitle(): String =
        if (notifyCpu) "${notifyCpuThreshold}%" else "已关闭"

    private fun updateDeviceCpuSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_device_cpu)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getDeviceCpuSubtitle()
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update device CPU subtitle failed: ${e.message}") }
    }

    private fun showDeviceCpuDialog() {
        showSliderThresholdDialog(
            title = "CPU 异常占用提醒",
            iconRes = R.drawable.ic_cpu,
            switchChecked = notifyCpu,
            currentValue = notifyCpuThreshold.toFloat(),
            unit = "%",
            sliderMin = 20f, sliderMax = 100f, sliderStep = 1f, tickStep = 10f,
            presets = listOf(30, 50, 70, 90, 100),
            subtitle = "超过阈值时通知",
            onToggle = { notifyCpu = it; SPUtil.setNotifyCpu(this, it); updateDeviceCpuSubtitle() },
            onThresholdChange = { v -> notifyCpuThreshold = v.toInt(); SPUtil.setNotifyCpuThreshold(this, v.toInt()); updateDeviceCpuSubtitle() }
        )
    }

    // ==================== 6. 设备上下线提醒（直接开关） ====================

    private fun initDeviceOnline() {
        notifyDeviceOnline = SPUtil.getNotifyDeviceOnline(this)
        CommonSettingsItemHelper.setupSwitchItem(
            itemView = findViewById(R.id.item_device_online),
            iconRes = R.drawable.ic_router,
            label = "设备上下线提醒",
            subtitle = if (notifyDeviceOnline) "已开启" else "已关闭",
            initialChecked = notifyDeviceOnline,
            onToggle = { checked ->
                notifyDeviceOnline = checked
                SPUtil.setNotifyDeviceOnline(this, checked)
                updateDeviceOnlineSubtitle()
            }
        )
    }

    private fun updateDeviceOnlineSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_device_online)
                ?.findViewById<TextView>(R.id.common_switch_subtitle)
                ?.text = if (notifyDeviceOnline) "已开启" else "已关闭"
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update device online subtitle failed: ${e.message}") }
    }

    // ==================== 7. 电量过低提醒 ====================

    private fun initDeviceBattery() {
        notifyBattery = SPUtil.getNotifyBattery(this)
        notifyBatteryThreshold = SPUtil.getNotifyBatteryThreshold(this)
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_device_battery),
            iconRes = R.drawable.ic_battery_0,
            title = "电量过低提醒",
            subtitle = getDeviceBatterySubtitle(),
            onClick = { showDeviceBatteryDialog() }
        )
    }

    private fun getDeviceBatterySubtitle(): String =
        if (notifyBattery) "${notifyBatteryThreshold}%" else "已关闭"

    private fun updateDeviceBatterySubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_device_battery)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getDeviceBatterySubtitle()
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update device battery subtitle failed: ${e.message}") }
    }

    private fun showDeviceBatteryDialog() {
        showSliderThresholdDialog(
            title = "电量过低提醒",
            iconRes = R.drawable.ic_battery_0,
            switchChecked = notifyBattery,
            currentValue = notifyBatteryThreshold.toFloat(),
            unit = "%",
            sliderMin = 10f, sliderMax = 50f, sliderStep = 1f, tickStep = 10f,
            presets = listOf(10, 20, 30, 40, 50),
            subtitle = "低于阈值时通知",
            onToggle = { notifyBattery = it; SPUtil.setNotifyBattery(this, it); updateDeviceBatterySubtitle() },
            onThresholdChange = { v -> notifyBatteryThreshold = v.toInt(); SPUtil.setNotifyBatteryThreshold(this, v.toInt()); updateDeviceBatterySubtitle() }
        )
    }

    // ==================== 8. 内存占用过高提醒 ====================

    private fun initDeviceMemory() {
        notifyMemory = SPUtil.getNotifyMemory(this)
        notifyMemoryThreshold = SPUtil.getNotifyMemoryThreshold(this)
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_device_memory),
            iconRes = R.drawable.ic_chip,
            title = "内存占用过高提醒",
            subtitle = getDeviceMemorySubtitle(),
            onClick = { showDeviceMemoryDialog() }
        )
    }

    private fun getDeviceMemorySubtitle(): String =
        if (notifyMemory) "${notifyMemoryThreshold}%" else "已关闭"

    private fun updateDeviceMemorySubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_device_memory)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getDeviceMemorySubtitle()
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update device memory subtitle failed: ${e.message}") }
    }

    private fun showDeviceMemoryDialog() {
        showSliderThresholdDialog(
            title = "内存占用过高提醒",
            iconRes = R.drawable.ic_chip,
            switchChecked = notifyMemory,
            currentValue = notifyMemoryThreshold.toFloat(),
            unit = "%",
            sliderMin = 50f, sliderMax = 100f, sliderStep = 1f, tickStep = 10f,
            presets = listOf(50, 60, 70, 80, 90),
            subtitle = "超过阈值时通知",
            onToggle = { notifyMemory = it; SPUtil.setNotifyMemory(this, it); updateDeviceMemorySubtitle() },
            onThresholdChange = { v -> notifyMemoryThreshold = v.toInt(); SPUtil.setNotifyMemoryThreshold(this, v.toInt()); updateDeviceMemorySubtitle() }
        )
    }

    // ==================== 9. 监控检查间隔 ====================

    private fun initMonitorInterval() {
        monitorIntervalSec = SPUtil.getMonitorIntervalSec(this)
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_monitor_interval),
            iconRes = R.drawable.ic_clock_bolt,
            title = "监控检查间隔",
            subtitle = getMonitorIntervalSubtitle(),
            onClick = { showMonitorIntervalDialog() }
        )
    }

    private fun getMonitorIntervalSubtitle(): String {
        return when {
            monitorIntervalSec < 60 -> "${monitorIntervalSec}秒"
            monitorIntervalSec % 60 == 0 -> "${monitorIntervalSec / 60}分钟"
            else -> "${monitorIntervalSec / 60}分${monitorIntervalSec % 60}秒"
        }
    }

    private fun updateMonitorIntervalSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_monitor_interval)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getMonitorIntervalSubtitle()
        } catch (e: Exception) { DebugLogger.w("NotificationSettingsActivity", "update monitor interval subtitle failed: ${e.message}") }
    }

    private fun showMonitorIntervalDialog() {
        showSliderThresholdDialog(
            title = "监控检查间隔",
            iconRes = R.drawable.ic_clock_bolt,
            switchChecked = true,
            currentValue = monitorIntervalSec.toFloat(),
            unit = "秒",
            sliderMin = 15f, sliderMax = 600f, sliderStep = 5f, tickStep = 60f,
            presets = listOf(30, 60, 120, 300),
            subtitle = "间隔越小越精准，但更耗电",
            showSwitch = false,
            onToggle = {},
            onThresholdChange = { v ->
                monitorIntervalSec = (v / 5).toInt() * 5  // 对齐到 5 的倍数
                SPUtil.setMonitorIntervalSec(this, monitorIntervalSec)
                updateMonitorIntervalSubtitle()
            }
        )
    }

    // ==================== 10. 免打扰时段 ====================
    //
    // 只静音通知，不停止采集：夜间停采会让 TrafficRecordManager 的「单日结清」推导
    // 缺采样点，跨天结清位置漂移，日用量直接算错。所以本项落在 NotificationHelper
    // 的总入口，而不是 WifiGuard。

    private fun initQuietHours() {
        val itemView = findViewById<View>(R.id.item_quiet_hours)
        CommonSettingsItemHelper.setupSwitchItem(
            itemView = itemView,
            iconRes = R.drawable.ic_moon,
            label = "免打扰时段",
            subtitle = getQuietHoursSubtitle(),
            initialChecked = SPUtil.getQuietHoursEnabled(this)
        ) { checked ->
            SPUtil.setQuietHoursEnabled(this, checked)
        }
        // 开关只占右侧滑块，点整行进去调起止时间
        itemView.setOnClickListener { showQuietHoursDialog() }
    }

    private fun getQuietHoursSubtitle(): String {
        val start = NotificationHelper.formatMinuteOfDay(SPUtil.getQuietHoursStart(this))
        val end = NotificationHelper.formatMinuteOfDay(SPUtil.getQuietHoursEnd(this))
        val crossDay = SPUtil.getQuietHoursStart(this) > SPUtil.getQuietHoursEnd(this)
        return "$start – $end${if (crossDay) "（次日）" else ""} 静音"
    }

    private fun updateQuietHoursSubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_quiet_hours)
                ?.findViewById<TextView>(R.id.common_switch_subtitle)
                ?.text = getQuietHoursSubtitle()
        } catch (e: Exception) {
            DebugLogger.w("NotificationSettingsActivity", "update quiet hours subtitle failed: ${e.message}")
        }
    }

    /**
     * 起止时间合成一条区间滑条：两个滑块直接表达「从几点到几点」，
     * 着色段就是免打扰时段 —— 滑块之间即所选范围，没有第二种解读。
     *
     * 不支持跨零点：00:00–24:00 这根轴上没法把「次日 07:00」画在 23:00 的右边，
     * 以前用取反着色表达跨天，结果是「设的时段没上色、没设的反倒上色」，
     * 比跨天本身更让人困惑。历史上存下来的跨天值（start > end）打开时会被当作未设置处理。
     */
    private fun showQuietHoursDialog() {
        val dialog = CommonDialogHelper.createAnimatedDialog(this) { activeDialog = null }
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "免打扰时段"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_moon)
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.addView(TextView(this).apply {
            text = "着色段即静音时段，采集与流量记录照常。起止相同视为未设置。"
            textSize = 12f
            alpha = 0.6f
            setTextColor(ThemeColors.textPrimary(this@NotificationSettingsActivity))
            setPadding(dp2px(4), 0, dp2px(4), dp2px(8))
        })

        val storedStart = SPUtil.getQuietHoursStart(this)
        val storedEnd = SPUtil.getQuietHoursEnd(this)
        // 旧版本可能存着跨天区间（start > end），本界面已不支持这种形态。
        // 直接交换会把「23:00→次日07:00」读成「07:00–23:00」，正好反了用户的本意，
        // 所以当作未设置、回落到默认值，由用户重新指定。
        val crossDayLegacy = storedStart > storedEnd
        var lower = (if (crossDayLegacy) SPUtil.DEFAULT_QUIET_START else storedStart).coerceIn(0, 1435)
        var upper = (if (crossDayLegacy) SPUtil.DEFAULT_QUIET_END else storedEnd).coerceIn(0, 1435)

        val valueLabel = TextView(this).apply {
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.textPrimary(this@NotificationSettingsActivity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun refreshLabel() {
            valueLabel.text = "${NotificationHelper.formatMinuteOfDay(lower)} – " +
                NotificationHelper.formatMinuteOfDay(upper)
        }

        fun persist() {
            SPUtil.setQuietHoursStart(this, lower)
            SPUtil.setQuietHoursEnd(this, upper)
            updateQuietHoursSubtitle()
        }

        val slider = ThemeSlider(this).also { s ->
            s.rangeMode = true
            s.minValue = 0f
            s.maxValue = 1435f
            s.stepSize = 5f
            s.startValue = lower.toFloat()
            s.endValue = upper.toFloat()
            s.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            s.onRangeChanging = { a, b ->
                lower = minOf(a, b).toInt(); upper = maxOf(a, b).toInt()
                refreshLabel()
            }
            s.onRangeChange = { a, b ->
                lower = minOf(a, b).toInt(); upper = maxOf(a, b).toInt()
                refreshLabel()
                persist()
            }
        }
        // 每 6 小时一个刻度，24 个整点标签会糊成一片
        ThemedSliderUtil.setupSliderTickMarks(slider, 360f) {
            NotificationHelper.formatMinuteOfDay(it.toInt())
        }

        content.addView(valueLabel)
        content.addView(slider)
        refreshLabel()
        // legacy 跨天值被回落成默认值，得立刻落盘，否则用户直接点「完成」会以为保留了原设置
        if (crossDayLegacy) persist()

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "完成"
            setOnClickListener { dialog.dismiss() }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeDialog = dialog
        dialog.show()
    }

    // ==================== 通用：滑条弹窗（带预设 + 自定义输入） ====================

    private fun showSliderThresholdDialog(
        title: String,
        iconRes: Int,
        switchChecked: Boolean,
        currentValue: Float,
        unit: String,
        sliderMin: Float,
        sliderMax: Float,
        sliderStep: Float,
        tickStep: Float,
        presets: List<Int>,
        subtitle: String? = null,
        showSwitch: Boolean = true,
        onToggle: (Boolean) -> Unit,
        onThresholdChange: (Float) -> Unit
    ) {
        val dialog = CommonDialogHelper.createAnimatedDialog(this) { activeDialog = null }
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(iconRes)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        // 开关行（部分弹窗如监控间隔不需要开关）
        if (showSwitch) {
            content.addView(createInlineSwitch(title, subtitle, switchChecked, onToggle))
        }

        // 当前值标签
        val valueLabel = TextView(this).apply {
            text = "${currentValue.toInt()} $unit"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.textPrimary(this@NotificationSettingsActivity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(valueLabel)

        // 用局部变量避免与 ThemeSlider.onValueChange 属性名冲突
        val thresholdCallback = onThresholdChange

        // 预设芯片更新函数（需提前声明，供滑条回调引用）
        var updatePresets: (Int) -> Unit = {}

        // 滑条
        val slider = ThemeSlider(this).also { s ->
            s.minValue = sliderMin
            s.maxValue = sliderMax
            s.stepSize = sliderStep
            s.currentValue = currentValue.coerceIn(sliderMin, sliderMax)
            s.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            s.onValueChanging = { v -> valueLabel.text = "${v.toInt()} $unit" }
            s.onValueChange = { v ->
                valueLabel.text = "${v.toInt()} $unit"
                thresholdCallback(v)
                updatePresets(v.toInt())
            }
        }
        ThemedSliderUtil.setupSliderTickMarks(slider, tickStep) { "${it.toInt()}$unit" }
        content.addView(slider)

        // 预设芯片（供滑条回调引用的 updatePresets 已在前面声明）
        val (presetRow, updater) = CommonDialogHelper.createPresetRow(
            context = this,
            values = presets,
            formatLabel = { "$it$unit" },
            currentValue = currentValue.toInt(),
            onSelect = { value ->
                slider.currentValue = value.toFloat()
            }
        )
        updatePresets = updater
        content.addView(presetRow)

        // 自定义输入面板（默认隐藏，点击"自定义"按钮展开）
        val customPanel = CommonDialogHelper.createInputPanel(
            context = this,
            hint = "输入 $sliderMin-$sliderMax $unit",
            validate = { text ->
                val v = text.toFloatOrNull()
                when {
                    v == null -> "请输入有效数字"
                    v < sliderMin || v > sliderMax -> "请输入 $sliderMin-$sliderMax 之间的值"
                    else -> null
                }
            },
            onConfirm = { text ->
                val v = text.toFloat()
                slider.currentValue = v
            }
        )
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // --- 按钮 ---
        val btnPrimary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确认"
        btnPrimary.setOnClickListener { dialog.dismiss() }

        val btnSecondary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = View.VISIBLE
        btnSecondary.text = "自定义"
        btnSecondary.setOnClickListener {
            val showing = customPanel.visibility == View.VISIBLE
            CommonDialogHelper.animatePanelVisibility(customPanel, !showing) {
                if (!showing) {
                    customPanel.findViewWithTag<EditText>("custom_input_field")?.let { et ->
                        if (currentValue > 0 && (currentValue < sliderMin || currentValue > sliderMax)) {
                            et.setText(currentValue.toInt().toString())
                        }
                        et.requestFocus()
                    }
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeDialog = dialog
        dialog.show()
    }

    // ==================== 弹窗内公共组件 ====================

    private fun createInlineSwitch(
        label: String,
        subtitle: String? = null,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        return CommonSettingsItemHelper.createSwitchRow(
            context = this,
            label = label,
            subtitle = subtitle,
            initialChecked = checked,
            onToggle = onToggle
        )
    }

    // ==================== 10. 后台保活配置入口 ====================

    private fun initBackgroundKeepAliveEntry() {
        try {
            val entryView = findViewById<ViewGroup>(R.id.item_background_keep_alive) ?: return
            CommonSettingsItemHelper.setupSettingItem(
                itemView = entryView,
                iconRes = R.drawable.ic_heartbeat,
                title = "后台保活配置",
                subtitle = getKeepAliveEntrySubtitle(),
                onClick = {
                    startActivity(android.content.Intent(this, BackgroundKeepAliveActivity::class.java))
                }
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG_INIT, "init keep-alive entry FAILED: ${e::class.java.simpleName}: ${e.message}", force = true)
        }
    }

    private fun getKeepAliveEntrySubtitle(): String {
        val bgEnabled = SPUtil.getBackgroundServiceEnabled(this)
        return if (bgEnabled) "前台保活已开启 · 点击管理" else "前台服务、电池优化、自启动 · 点击配置"
    }

    private fun updateKeepAliveEntrySubtitle() {
        try {
            findViewById<ViewGroup>(R.id.item_background_keep_alive)
                ?.findViewById<TextView>(R.id.common_item_subtitle)
                ?.text = getKeepAliveEntrySubtitle()
        } catch (e: Exception) {
            DebugLogger.w("NotificationSettingsActivity", "update keep-alive entry subtitle failed: ${e.message}")
        }
    }

    /** 通知总开关开启时，弹窗提示用户配置后台保活 */
    private fun showKeepAlivePrompt() {
        val bgEnabled = SPUtil.getBackgroundServiceEnabled(this)
        val msgText = buildString {
            appendLine("通知功能已开启。")
            appendLine("由于系统后台限制，通知可能存在 1~5 分钟的延迟。为获得最佳体验，建议：")
            appendLine("• 开启前台保活服务，防止进程被系统回收")
            appendLine("• 加入电池优化白名单，避免被省电策略终止")
            appendLine("• 允许自启动权限，确保开机后自动恢复")
            if (!bgEnabled) {
                appendLine()
                appendLine("当前前台保活服务尚未开启，建议前往配置。")
            }
        }

        activeDialog = CommonDialogHelper.showCommonDialog(
            context = this,
            title = "后台保活建议",
            iconRes = R.drawable.ic_rocket,
            onFill = { content ->
                content.addView(TextView(this).apply {
                    text = msgText
                    textSize = 14f
                    setTextColor(ThemeColors.textPrimary(this@NotificationSettingsActivity))
                    setLineSpacing(0f, 1.4f)
                })
            },
            primaryBtnText = "前往配置",
            onPrimaryClick = { dialog ->
                dialog.dismiss()
                startActivity(android.content.Intent(this, BackgroundKeepAliveActivity::class.java))
            },
            secondaryBtnText = "知道了"
        )
    }
}
