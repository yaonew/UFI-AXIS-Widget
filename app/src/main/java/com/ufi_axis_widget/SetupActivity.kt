package com.ufi_axis_widget

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ufi_axis_widget.util.DataSourceType

import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.NetUtil
import com.ufi_axis_widget.util.PopupViewUtil

import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.ScaleTouchListener
import com.ufi_axis_widget.util.ThemeChangeNotifier
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

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
    }

    private var themeChangeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(SPUtil.getNightMode(this))
        super.onCreate(savedInstanceState)
        DebugLogger.init(this)

        try {
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@SetupActivity, ThemeUtil.PageType.FORM)
        }
        setContentView(R.layout.activity_setup)

            // 数据源选择：下拉菜单（不可手动输入）
            val itemSource = findViewById<View>(R.id.item_data_source)
            ThemeUtil.setupInputField(
                itemSource, "数据来源", "决定用哪种方式读取设备信息", "",
                android.text.InputType.TYPE_NULL
            )
            val etSource = itemSource.findViewById<EditText>(R.id.common_input_edit_text).apply {
                isFocusable = false
                isClickable = true
                isCursorVisible = false
            }
            val tvSourceHint = findViewById<TextView>(R.id.tv_source_hint)
            var selectedSource = SPUtil.getDataSourceType(this)


            // 设备地址输入框
            val itemAddress = findViewById<View>(R.id.item_device_address)
            ThemeUtil.setupInputField(itemAddress, "设备连接地址", "支持 IP:端口 或 域名", "例如 192.168.0.1:2333", android.text.InputType.TYPE_TEXT_VARIATION_URI)
            val etDeviceAddress = itemAddress.findViewById<EditText>(R.id.common_input_edit_text)

            // 认证口令输入框
            val itemToken = findViewById<View>(R.id.item_token)
            ThemeUtil.setupInputField(
                itemToken, "认证口令", "设备登录口令，留空则使用 admin", "输入你的登录口令",
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            val etToken = itemToken.findViewById<EditText>(R.id.common_input_edit_text)

            fun renderSourceUi() {
                etSource.setText(selectedSource.displayName)
                when (selectedSource) {

                    DataSourceType.UFI_TOOLS -> {
                        tvSourceHint.text = "经设备上的 UFI-TOOLS 服务读取，可获得温度、CPU、内存、存储等完整信息"
                        ThemeUtil.setupInputField(
                            itemAddress, "设备连接地址", "支持 IP:端口 或 域名",
                            "例如 192.168.0.1:${SPUtil.DEFAULT_DEVICE_ADDRESS.substringAfter(':')}",
                            android.text.InputType.TYPE_TEXT_VARIATION_URI
                        )
                        ThemeUtil.setupInputField(
                            itemToken, "认证口令", "UFI-TOOLS 登录口令，留空则使用 admin", "输入你的登录口令",
                            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        )
                    }
                    DataSourceType.GOFORM -> {
                        tvSourceHint.text = "直连设备原生后台，无需安装 UFI-TOOLS；但温度、CPU、内存、存储、当日流量将显示「暂无数据」"
                        ThemeUtil.setupInputField(
                            itemAddress, "设备连接地址", "只取主机部分，端口固定用后台端口",
                            "例如 192.168.0.1", android.text.InputType.TYPE_TEXT_VARIATION_URI
                        )
                        ThemeUtil.setupInputField(
                            itemToken, "后台登录口令", "设备管理页面的登录口令，留空则使用 admin", "输入设备后台口令",
                            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        )
                    }
                    DataSourceType.UFI_AXIS -> {
                        tvSourceHint.text = "经设备上的 UFI-AXIS core 服务读取，首次连接自动配对换取访问凭据，字段已归一化"
                        ThemeUtil.setupInputField(
                            itemAddress, "设备连接地址", "只取主机部分，端口固定用 ${SPUtil.DEFAULT_UFI_AXIS_PORT}",
                            "例如 192.168.0.1", android.text.InputType.TYPE_TEXT_VARIATION_URI
                        )
                        ThemeUtil.setupInputField(
                            itemToken, "配对密码", "core 出厂默认 ${SPUtil.DEFAULT_UFI_AXIS_PASSWORD}，留空即用它",
                            "输入 core 的配对密码",
                            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        )
                    }
                }
            }

            etSource.setOnClickListener {
                val types = DataSourceType.entries
                PopupViewUtil.showDropDownMenu(
                    etSource,
                    options = types.map { it.displayName }.toTypedArray(),
                    currentIndex = types.indexOf(selectedSource),
                    onSelect = { index ->
                        selectedSource = types[index]
                        renderSourceUi()
                    }
                )
            }
            renderSourceUi()


            // 恢复已有配置
            val savedAddress = SPUtil.getDeviceAddress(this)
            val savedToken = SPUtil.getRawToken(this)
            if (savedToken != "admin") {
                etToken.setText(savedToken)
            }
            if (savedAddress != SPUtil.DEFAULT_DEVICE_ADDRESS) {
                etDeviceAddress.setText(savedAddress)
            }

            findViewById<View>(R.id.btn_setup_confirm).apply {
                findViewById<TextView>(R.id.common_btn_text).text = "保存并开始使用"
                setOnClickListener {
                    val typed = etDeviceAddress.text.toString().trim()
                    // UFI-AXIS 的端口由「服务端口」单独保存，地址里带端口只会打架
                    val address = if (selectedSource == DataSourceType.UFI_AXIS && typed.isNotEmpty()) {
                        typed.substringAfter("://").substringBefore("/").substringBefore(":")
                    } else typed.ifEmpty { SPUtil.DEFAULT_DEVICE_ADDRESS }
                    val rawInput = etToken.text.toString().trim()
                    // AXIS 的配对密码始终必填，留空即用 core 的出厂默认密码
                    val token = if (selectedSource == DataSourceType.UFI_AXIS)
                        rawInput.ifEmpty { SPUtil.DEFAULT_UFI_AXIS_PASSWORD }
                    else rawInput.ifEmpty { "admin" }

                    SPUtil.setDataSourceType(this@SetupActivity, selectedSource)
                    SPUtil.setDeviceAddress(this@SetupActivity, address)
                    // 口令只写选中源对应的键：两个源的口令语义不同
                    // （UFI-TOOLS 用 sha256 后的 Authorization，goform 需要明文参与 LD 挑战），
                    // 且避免把同一个凭据在 SP 里多存一份可被备份导出的副本。
                    when (selectedSource) {
                        DataSourceType.UFI_TOOLS -> {
                            SPUtil.saveRawToken(this@SetupActivity, token)
                            SPUtil.saveAuthToken(this@SetupActivity, NetUtil.sha256(token))
                        }
                        DataSourceType.GOFORM -> {
                            SPUtil.setGoformPassword(this@SetupActivity, token)
                            GoformDataSource.invalidateSession()
                        }
                        DataSourceType.UFI_AXIS -> {
                            SPUtil.setUfiAxisPairPassword(this@SetupActivity, token)
                            // 地址/密码是全新的，旧配对（如果有）必然对不上
                            UfiAxisDataSource.invalidatePairing(this@SetupActivity)
                        }
                    }
                    SPUtil.setFirstRun(this@SetupActivity, false)

                    // 初始化完成 → 重置失败状态
                    WifiWorker.resetFailureState(this@SetupActivity)

                    // 后台自动探测协议（域名填的 http 还是 https）
                    triggerProtocolProbe()

                    BaseWifiWidget.renderAllWidgets(this@SetupActivity)
                    ToastUtil.showDropToast(this@SetupActivity, ToastStyle.SUCCESS, "配置已保存")
                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                }
                setOnTouchListener(ScaleTouchListener())
            }

            findViewById<View>(R.id.tv_skip).setOnClickListener {
                SPUtil.setFirstRun(this, false)
                BaseWifiWidget.renderAllWidgets(this)
                ToastUtil.showDropToast(this, ToastStyle.INFO, "已使用默认配置")
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SetupActivity onCreate crashed: ${e.message}", e)

            // 兜底：跳过配置直接用默认值
            SPUtil.setFirstRun(this, false)
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "配置界面异常", "已使用默认配置")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    /** 后台自动探测协议（HTTPS 优先 → HTTP 回退），结果存入 SP */
    private fun triggerProtocolProbe() {
        if (!SPUtil.needsProtocolProbe(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = DeviceDataSourceRegistry.current(this@SetupActivity)
                .probeProtocol(this@SetupActivity)

            if (result != null) {
                SPUtil.setDeviceProtocol(this@SetupActivity, result)
                Log.d(TAG, "Protocol auto-detected: $result")
            } else {
                Log.d(TAG, "Protocol probe failed, using default")
            }
        }
    }
}
