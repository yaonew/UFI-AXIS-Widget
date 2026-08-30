package com.ufi_axis_widget

import android.app.Dialog
import android.content.BroadcastReceiver
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ufi_axis_widget.util.AnimationUtil
import com.ufi_axis_widget.util.CommonDialogHelper
import com.ufi_axis_widget.util.CommonSettingsItemHelper
import com.ufi_axis_widget.util.DataSourceType
import com.ufi_axis_widget.util.DeviceProfiles
import com.ufi_axis_widget.util.NetUtil


import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.ThemeChangeNotifier
import com.ufi_axis_widget.util.ThemeColors
import com.ufi_axis_widget.util.ThemeUtil
import com.ufi_axis_widget.util.ToastStyle
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry
import com.ufi_axis_widget.util.source.GoformDataSource
import com.ufi_axis_widget.util.source.UfiAxisDataSource
import com.ufi_axis_widget.widget.BaseWifiWidget
import com.ufi_axis_widget.worker.WifiWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.ufi_axis_widget.util.ToastUtil

class ConfigModifyActivity : AppCompatActivity() {

    private var themeChangeReceiver: BroadcastReceiver? = null

    // ==================== 当前值缓存 ====================
    private var dataSource: DataSourceType = DataSourceType.UFI_TOOLS
    private var deviceAddress: String = ""
    private var rawToken: String = ""
    private var deviceInfoPath: String = ""
    private var atCommandPath: String = ""
    private var goformCommandPath: String = ""
    private var secretKey: String = ""

    // ==================== 活跃弹窗引用 ====================
    private var activeDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@ConfigModifyActivity, ThemeUtil.PageType.FORM)
            refreshAllSubtitles()
        }
        setContentView(R.layout.activity_config_modify)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        loadCurrentValues()
        initAllItems()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
        loadCurrentValues()
        refreshAllSubtitles()
        // 从配置档页返回时地址可能整套换了，新地址未必探测过协议
        triggerProtocolProbe()
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    // ==================== 数据加载 ====================

    private fun loadCurrentValues() {
        dataSource = SPUtil.getDataSourceType(this)
        deviceAddress = SPUtil.getDeviceAddress(this)
        rawToken = SPUtil.getRawToken(this)
        deviceInfoPath = SPUtil.getDeviceInfoPath(this)
        atCommandPath = SPUtil.getAtCommandPath(this)
        goformCommandPath = SPUtil.getGoformCommandPath(this)
        secretKey = SPUtil.getSecretKey(this)
    }

    // ==================== 初始化设置项 ====================

    /**
     * 配置三个设置项。标题与副标题都随当前数据源变化，
     * 所以数据源切换后直接重新调用本方法即可完成「智能显示」。
     */
    private fun initAllItems() {
        // 设备配置档：放在最前面，因为它决定了下面几项改的是哪台设备
        val profileId = DeviceProfiles.activeId(this)
        val profileCount = DeviceProfiles.ids(this).size
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_device_profile),
            iconRes = R.drawable.ic_filter,
            title = "设备配置档",
            subtitle = "${DeviceProfiles.displayName(this, profileId)} · 共 $profileCount 个",
            onClick = ::openProfilesPage
        )

        // 数据源：始终显示，副标题给出当前源与能力差异提示
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_data_source),
            iconRes = R.drawable.ic_antenna,
            title = "数据源",
            subtitle = "${dataSource.displayName} · ${dataSourceHint(dataSource)}",
            onClick = ::showDataSourceDialog
        )



        // 基础连接：副标题给出「这个源实际会去连哪里」，
        // 地址按源分槽以后，不显示的话用户根本不知道当前源填过没有
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_basic_config),
            iconRes = R.drawable.ic_router,
            title = "基础连接",
            subtitle = basicConfigSubtitle(),
            onClick = ::showBasicConfigDialog
        )

        // 高级配置：只对 UFI-TOOLS 有意义（端点路径/签名密钥/AT 平台）。
        // goform 需要的端口与口令已经合进「基础连接」，再留一个入口就是重复。
        // UFI-AXIS 的接口路径由 core 固定，没有可调项。
        val advancedItem = findViewById<View>(R.id.item_advanced_config)
        if (dataSource != DataSourceType.UFI_TOOLS) {
            advancedItem.visibility = View.GONE
        } else {
            advancedItem.visibility = View.VISIBLE
            CommonSettingsItemHelper.setupSettingItem(
                advancedItem,
                iconRes = R.drawable.ic_chip,
                title = "高级配置",
                showSubtitle = false,
                onClick = ::showAdvancedConfigDialog
            )
        }
    }

    private fun refreshAllSubtitles() {
        loadCurrentValues()
        initAllItems()
    }

    /** 「这个源实际会去连哪里」：地址来自各源自己的槽，端口/凭据状态也各不相同 */
    private fun basicConfigSubtitle(): String = when (dataSource) {
        DataSourceType.GOFORM ->
            "${SPUtil.getDeviceHost(this)}:${SPUtil.getGoformPort(this)}"
        DataSourceType.UFI_AXIS -> {
            val paired = SPUtil.getUfiAxisToken(this).isNotEmpty()
            "${SPUtil.getDeviceHost(this)}:${SPUtil.getUfiAxisPort(this)} · " +
                if (paired) "已配对" else "未配对"
        }
        DataSourceType.UFI_TOOLS -> deviceAddress
    }

    // ==================== 设备配置档 ====================

    /**
     * 打开配置档管理页。
     *
     * 从弹窗改成独立页面：多配置的关键信息是「每档连的是谁」，
     * 弹窗一行文字放不下数据源 + 地址，列表页才看得清。
     * 切换后的刷新由 [onResume] 负责。
     */
    private fun openProfilesPage() {
        startActivity(android.content.Intent(this, DeviceProfilesActivity::class.java))
    }

    // ==================== 数据源切换 ====================

    /** 各数据源一行能力摘要，设置项副标题与弹窗选项共用，避免两处文案说法不一致 */
    private fun dataSourceHint(type: DataSourceType): String = when (type) {
        DataSourceType.UFI_TOOLS -> "信息完整"
        DataSourceType.GOFORM -> "无温度/CPU/内存/存储"
        DataSourceType.UFI_AXIS -> "字段已归一化"
    }

    /**
     * 数据源选择弹窗：双栏网格，与其它选择类弹窗同一套观感。
     *
     * 单元格里只放一行能力摘要 —— 双栏宽度下写满「设备上需装 XXX，温度/CPU/内存/存储齐全」
     * 会折成三四行。顶部那句「各源凭据独立保存」对三个选项都成立，说一次就够。
     */
    private fun showDataSourceDialog() {
        activeDialog?.takeIf { it.isShowing }?.dismiss()

        val textPrimary = ThemeColors.textPrimary(this)

        activeDialog = CommonDialogHelper.showSelectionDialog(
            context = this,
            title = "数据源",
            iconRes = R.drawable.ic_antenna,
            onFill = { content, dialog ->
                content.addView(TextView(this).apply {
                    text = "每个数据源的地址、端口与凭据各自独立保存，来回切换不会丢失。" +
                        "切换后会直接打开新数据源的「基础连接」，填完才能取到数据。"
                    textSize = 12f
                    alpha = 0.6f
                    setTextColor(textPrimary)
                    setPadding(dp2px(4), 0, dp2px(4), dp2px(10))
                })

                val grid = CommonDialogHelper.addTwoColumnGrid(this, content)
                DataSourceType.entries.forEach { type ->
                    grid.addView(
                        CommonDialogHelper.asGridCell(
                            this,
                            CommonDialogHelper.buildOptionView(
                                context = this,
                                label = type.displayName,
                                subtitle = dataSourceHint(type),
                                selected = type == dataSource,
                                onClick = {
                                    dialog.dismiss()
                                    applyDataSourceChange(type)
                                }
                            )
                        )
                    )
                }
            }
        )
    }



    private fun applyDataSourceChange(type: DataSourceType) {
        if (type == dataSource) return
        val previous = dataSource
        commitDataSource(type)
        ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "已切换到 ${type.displayName}")
        // 每个源要填的东西不一样（AXIS 要配对密码、UFI-TOOLS 要口令），
        // 切完不引导用户就只能看着「采集失败」猜哪里没配，这里直接把新源的表单顶上来。
        // 点取消 = 放弃这次切换，把数据源退回去，否则「取消」了但源已经变了。
        showBasicConfig(onCancel = {
            commitDataSource(previous)
            ToastUtil.showDropToast(this, ToastStyle.INFO, "已取消，仍使用 ${previous.displayName}")
        })
    }

    /** 落库数据源并让界面与小组件跟上 */
    private fun commitDataSource(type: DataSourceType) {
        // 地址与协议现在按源分槽，所以不再动协议：新源自己的探测结果自己管，
        // 没写过的话 getDeviceProtocol 会回退到 auto，onConfigChanged 里会重探。
        SPUtil.setDataSourceType(this, type)
        GoformDataSource.invalidateSession()
        dataSource = type
        loadCurrentValues()
        initAllItems()
        // 换源必须强制重渲染：小组件的版本前缀等文案跟着数据源变，
        // 但这些不参与数据哈希，走普通渲染会被去重吞掉，新源采集失败时用户完全看不到变化。
        onConfigChanged(forceRender = true)
    }

    // ==================== 基础连接弹窗（地址 + 口令） ====================

    /** 入口点击用：普通打开，没有「取消即回退」的语义 */
    private fun showBasicConfigDialog() = showBasicConfig(null)

    /**
     * @param onCancel 点取消 / 返回键关闭时的回调。切换数据源后弹出的表单靠它把数据源退回去 ——
     *   否则用户点了取消，数据源却已经换了，界面和预期完全对不上。
     */
    private fun showBasicConfig(onCancel: (() -> Unit)?) {
        if (dataSource == DataSourceType.UFI_AXIS) {
            showAxisBasicConfigDialog(onCancel)
            return
        }
        val isGoform = dataSource == DataSourceType.GOFORM
        // 两个源的第二个字段含义不同：UFI-TOOLS 是服务口令，goform 是设备后台口令。
        // 存储位置也不同，所以标签和保存目标都要跟着数据源走。
        val currentPwd = if (isGoform) SPUtil.getGoformPassword(this) else rawToken
        val currentPort = SPUtil.getGoformPort(this)

        val fields = listOf(
            DialogField(
                label = "设备连接地址",
                // goform 走设备 Web 后台，端口由下面的「后台端口」决定（默认 80），
                // 地址里再带 UFI-TOOLS 的 2333 只会让人误以为要填那个端口
                currentValue = if (isGoform) SPUtil.getDeviceHost(this) else deviceAddress,
                hint = if (isGoform) "只填 IP 或域名，不带端口" else "留空则不修改",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = if (isGoform) "后台登录口令" else "认证口令",
                currentValue = if (isGoform && currentPwd == SPUtil.DEFAULT_GOFORM_PASSWORD) "" else currentPwd,
                hint = "留空则不修改",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        ) + if (isGoform) listOf(
            // 端口原本在「Goform 高级配置」里，但 goform 只有端口 + 口令两项，
            // 单独占一个入口纯属重复，合到基础连接里一次填完。
            DialogField(
                label = "后台端口",
                currentValue = if (currentPort == SPUtil.DEFAULT_GOFORM_PORT) "" else currentPort.toString(),
                hint = "默认 ${SPUtil.DEFAULT_GOFORM_PORT}",
                inputType = InputType.TYPE_CLASS_NUMBER
            ),
            // goform 协议没有产品型号字段，只能从固件版本串里猜，而那里装的往往是基带模块
            // 型号（MU300 这类）而不是产品名（F50）。手动填一次是唯一可靠的办法。
            DialogField(
                label = "设备名称",
                currentValue = SPUtil.getDeviceDisplayName(this),
                hint = "留空则从固件版本推断，例如 F50",
                inputType = InputType.TYPE_CLASS_TEXT
            )
        ) else emptyList()

        showMultiEditDialog(
            title = "基础连接",
            icon = R.drawable.ic_router,
            fields = fields,
            onCancel = onCancel,
            onSave = { values ->
                var changed = false
                // 留空则不修改
                val typedAddress = values[0].trim()
                // goform 下把协议头与端口一律剥掉：端口以「后台端口」为准，
                // 两处都能填端口必然打架
                val newAddress = if (isGoform && typedAddress.isNotEmpty()) {
                    typedAddress.substringAfter("://").substringBefore("/").substringBefore(":")
                } else typedAddress
                if (newAddress.isNotEmpty() && newAddress != deviceAddress) {
                    deviceAddress = newAddress
                    SPUtil.setDeviceAddress(this, newAddress)
                    changed = true
                }
                val newPwd = values[1].trim()
                if (newPwd.isNotEmpty() && newPwd != currentPwd) {
                    if (isGoform) {
                        SPUtil.setGoformPassword(this, newPwd)
                        GoformDataSource.invalidateSession()
                    } else {
                        rawToken = newPwd
                        SPUtil.saveRawToken(this, newPwd)
                        SPUtil.saveAuthToken(this, NetUtil.sha256(newPwd))
                    }
                    changed = true
                }
                if (isGoform && values.size > 3) {
                    // 端口留空即回到默认端口，所以这里允许清空
                    val newPort = values[2].trim().toIntOrNull() ?: SPUtil.DEFAULT_GOFORM_PORT
                    if (newPort != currentPort) {
                        SPUtil.setGoformPort(this, newPort)
                        // 端口变了旧 session 必然失效
                        GoformDataSource.invalidateSession()
                        changed = true
                    }
                    // 设备名称允许清空（清空即回到自动推断），所以不套「留空不改」的规则
                    val newName = values[3].trim()
                    if (newName != SPUtil.getDeviceDisplayName(this)) {
                        SPUtil.setDeviceDisplayName(this, newName)
                        changed = true
                    }
                }
                if (changed) onConfigChanged()
            }
        )
    }

    // ==================== 基础连接（UFI-AXIS） ====================

    /**
     * UFI-AXIS 的基础连接。
     *
     * 它不用口令换 Token，而是走配对流程：首次采集时自动 `/pairing/info` +
     * `/pairing/confirm` 换取 Bearer Token。这里能填的只有配对时需要的东西，
     * 所以单独一个弹窗，不跟 UFI-TOOLS / goform 的字段混在一起。
     */
    private fun showAxisBasicConfigDialog(onCancel: (() -> Unit)?) {
        val currentPort = SPUtil.getUfiAxisPort(this)
        val currentPwd = SPUtil.getUfiAxisPairPassword(this)

        val fields = listOf(
            DialogField(
                label = "设备连接地址",
                currentValue = SPUtil.getDeviceHost(this),
                hint = "只填 IP 或域名，不带端口",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = "服务端口",
                currentValue = if (currentPort == SPUtil.DEFAULT_UFI_AXIS_PORT) "" else currentPort.toString(),
                hint = "默认 ${SPUtil.DEFAULT_UFI_AXIS_PORT}",
                inputType = InputType.TYPE_CLASS_NUMBER
            ),
            DialogField(
                label = "配对密码",
                currentValue = currentPwd,
                hint = "默认 ${SPUtil.DEFAULT_UFI_AXIS_PASSWORD}",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            ),
            DialogField(
                label = "设备名称",
                currentValue = SPUtil.getDeviceDisplayName(this),
                hint = "留空则用 core 上报的型号",
                inputType = InputType.TYPE_CLASS_TEXT
            )
        )

        showMultiEditDialog(
            title = "基础连接",
            icon = R.drawable.ic_router,
            fields = fields,
            onCancel = onCancel,
            onSave = { values ->
                var changed = false
                // 地址/端口/密码任一变化，旧 Token 都不再对应同一台 core，必须重新配对
                var needRepair = false

                val typedAddress = values[0].trim()
                    .substringAfter("://").substringBefore("/").substringBefore(":")
                if (typedAddress.isNotEmpty() && typedAddress != SPUtil.getDeviceHost(this)) {
                    deviceAddress = typedAddress
                    SPUtil.setDeviceAddress(this, typedAddress)
                    changed = true
                    needRepair = true
                }

                val newPort = values[1].trim().toIntOrNull() ?: SPUtil.DEFAULT_UFI_AXIS_PORT
                if (newPort != currentPort) {
                    SPUtil.setUfiAxisPort(this, newPort)
                    changed = true
                    needRepair = true
                }

                // 留空即回到出厂默认密码，所以不套「留空不改」的规则
                val newPwd = values[2].trim().ifEmpty { SPUtil.DEFAULT_UFI_AXIS_PASSWORD }
                if (newPwd != currentPwd) {
                    SPUtil.setUfiAxisPairPassword(this, newPwd)
                    changed = true
                    needRepair = true
                }

                val newName = values[3].trim()
                if (newName != SPUtil.getDeviceDisplayName(this)) {
                    SPUtil.setDeviceDisplayName(this, newName)
                    changed = true
                }

                if (needRepair) UfiAxisDataSource.invalidatePairing(this)
                if (changed) onConfigChanged()
            }
        )
    }

    // ==================== 高级配置（红色警告弹窗 → 编辑弹窗） ====================

    private fun showAdvancedConfigDialog() {
        activeDialog?.takeIf { it.isShowing }?.dismiss()
        activeDialog = CommonDialogHelper.showWarningConfirmDialog(
            context = this,
            title = "警告",
            message = "正常情况切勿修改高级配置\n错误配置将导致设备功能异常",
            confirmText = "继续修改",
            onConfirm = ::showAdvancedConfigDialogInternal
        )
    }

    private fun showAdvancedConfigDialogInternal() {
        val fields = listOf(

            DialogField(
                label = "设备信息接口",
                currentValue = if (deviceInfoPath == SPUtil.DEFAULT_DEVICE_INFO_PATH) "" else deviceInfoPath,
                hint = "默认 ${SPUtil.DEFAULT_DEVICE_INFO_PATH}",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = "AT 命令接口",
                currentValue = if (atCommandPath == SPUtil.DEFAULT_AT_COMMAND_PATH) "" else atCommandPath,
                hint = "默认 ${SPUtil.DEFAULT_AT_COMMAND_PATH}",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = "Goform 命令接口",
                currentValue = if (goformCommandPath == SPUtil.DEFAULT_GOFORM_COMMAND_PATH) "" else goformCommandPath,
                hint = "默认 ${SPUtil.DEFAULT_GOFORM_COMMAND_PATH}",
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            ),
            DialogField(
                label = "签名密钥",
                currentValue = if (secretKey == SPUtil.DEFAULT_SECRET_KEY) "" else secretKey,
                hint = "默认 ${SPUtil.DEFAULT_SECRET_KEY}",
                inputType = InputType.TYPE_CLASS_TEXT
            ),
            DialogField(
                label = "设备平台 (AT 解析)",
                currentValue = SPUtil.getCachedPlatform(this).ifEmpty { "auto" },
                hint = "auto / spreadtrum / quectel",
                inputType = InputType.TYPE_CLASS_TEXT
            )
        )

        showMultiEditDialog(
            title = "高级配置",
            icon = R.drawable.ic_chip,
            fields = fields,
            onRestoreDefaults = {
                // 恢复所有高级字段为默认值
                deviceInfoPath = ""
                SPUtil.setDeviceInfoPath(this, "")
                atCommandPath = ""
                SPUtil.setAtCommandPath(this, "")
                goformCommandPath = ""
                SPUtil.setGoformCommandPath(this, "")
                secretKey = ""
                SPUtil.setSecretKey(this, "")
                SPUtil.setCachedPlatform(this, "")
                SPUtil.invalidateResponseCaches(this)
                onConfigChanged()
            },
            onSave = { values ->
                deviceInfoPath = values[0].trim()
                SPUtil.setDeviceInfoPath(this, deviceInfoPath)
                atCommandPath = values[1].trim()
                SPUtil.setAtCommandPath(this, atCommandPath)
                goformCommandPath = values[2].trim()
                SPUtil.setGoformCommandPath(this, goformCommandPath)
                secretKey = values[3].trim()
                SPUtil.setSecretKey(this, secretKey)

                // 接口路径或密钥变更，清除所有响应缓存以确保下轮使用新路径
                SPUtil.invalidateResponseCaches(this)

                val platform = values[4].trim().lowercase()
                SPUtil.setCachedPlatform(this, if (platform == "auto") "" else platform)

                onConfigChanged()
            }
        )
    }

    // ==================== 多字段 EditText 弹窗 ====================

    private data class DialogField(
        val label: String,
        val currentValue: String,
        val hint: String,
        val inputType: Int
    )

    private fun showMultiEditDialog(
        title: String,
        icon: Int,
        fields: List<DialogField>,
        onRestoreDefaults: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onSave: (List<String>) -> Unit
    ) {
        activeDialog?.takeIf { it.isShowing }?.dismiss()
        activeDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(icon)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val editTexts = mutableListOf<EditText>()

        for ((index, field) in fields.withIndex()) {
            // 字段标签
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dp2px(12)
                    bottomMargin = dp2px(4)
                }
                text = field.label
                setTextColor(textPrimary)
                textSize = 13f
            }
            content.addView(label)

            // 输入框
            val etInput = CommonSettingsItemHelper.createThemedEditText(
                this,
                hint = field.hint,
                text = field.currentValue,
                inputType = field.inputType
            )

            // 针对平台选择特殊处理：禁止手动输入，点击弹出选择列表
            if (field.label.contains("设备平台")) {
                CommonSettingsItemHelper.setupDropdownOnEditText(
                    etInput,
                    options = arrayOf("auto (自动探测)", "spreadtrum (展讯)", "quectel (移远)"),
                    values = arrayOf("auto", "spreadtrum", "quectel"),
                    currentValue = etInput.text.toString()
                )
            }

            content.addView(etInput)
            editTexts.add(etInput)
        }

        // 按钮区域
        val btnContainer = dialog.findViewById<LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE

        // 保存与取消要互斥：保存后关闭弹窗不该再触发「取消即回退」
        var committed = false

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "保存"
            setOnClickListener {
                val values = editTexts.map { it.text.toString() }
                committed = true
                onSave(values)
                refreshAllSubtitles()
                ToastUtil.showDropToast(this@ConfigModifyActivity, ToastStyle.SUCCESS, "$title 已保存")
                dialog.dismiss()
            }
        }

        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener {
                if (!committed) {
                    committed = true
                    onCancel?.invoke()
                }
                dialog.dismiss()
            }
        }

        // 返回键 / 点弹窗外关闭，语义上同样是「取消」
        dialog.setOnCancelListener {
            if (!committed) {
                committed = true
                onCancel?.invoke()
            }
        }

        // 恢复默认按钮
        if (onRestoreDefaults != null) {
            val btnRestore = CommonSettingsItemHelper.createRestoreDefaultsButton(
                this@ConfigModifyActivity
            ) {
                onRestoreDefaults()
                refreshAllSubtitles()
                ToastUtil.showDropToast(this@ConfigModifyActivity, ToastStyle.SUCCESS, "已恢复为默认配置")
                dialog.dismiss()
            }
            btnContainer.addView(btnRestore)
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeDialog = dialog
        dialog.show()

        // 自动聚焦首个输入框
        editTexts.firstOrNull()?.postDelayed({
            editTexts.first().requestFocus()
            editTexts.first().setSelection(editTexts.first().text.length)
        }, 150)
    }

    /** 配置变更后的统一处理 */
    private fun onConfigChanged(forceRender: Boolean = false) {
        WifiWorker.resetFailureState(this)
        triggerProtocolProbe()
        BaseWifiWidget.renderAllWidgets(this, force = forceRender)
        // 地址/端口/配对状态都写在「基础连接」的副标题上，改完必须立刻反映出来
        refreshAllSubtitles()
    }

    // ==================== 协议探测 ====================

    private fun triggerProtocolProbe() {
        if (!SPUtil.needsProtocolProbe(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = DeviceDataSourceRegistry.current(this@ConfigModifyActivity)
                .probeProtocol(this@ConfigModifyActivity)

            if (result != null) {
                SPUtil.setDeviceProtocol(this@ConfigModifyActivity, result)
                android.util.Log.d("ConfigModify", "Protocol auto-detected: $result")
            }
        }
    }

    // ==================== 工具方法 ====================

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
