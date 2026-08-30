package com.ufi_axis_widget

import android.content.BroadcastReceiver
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.ufi_axis_widget.util.AnimationUtil
import com.ufi_axis_widget.util.CommonDialogHelper
import com.ufi_axis_widget.util.CommonSettingsItemHelper
import com.ufi_axis_widget.util.DeviceProfiles
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.ThemeChangeNotifier
import com.ufi_axis_widget.util.ThemeColors
import com.ufi_axis_widget.util.ThemeUtil
import com.ufi_axis_widget.util.ToastStyle
import com.ufi_axis_widget.util.ToastUtil
import com.ufi_axis_widget.util.TrafficRecordManager

/**
 * 设备配置档管理页。
 *
 * 从弹窗改成独立页面：多配置的核心信息是「每档连的是谁」，
 * 弹窗里只能塞一行文字，列表页可以把数据源和地址直接摊开，一眼看清。
 *
 * 点一下卡片就切换（当前档带 ✓ 图标），重命名/删除放在卡片长按里 ——
 * 主操作是切换，管理是低频动作，不该占据同等视觉权重。
 */
class DeviceProfilesActivity : AppCompatActivity() {

    private var themeChangeReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyTheme(this@DeviceProfilesActivity, ThemeUtil.PageType.FORM)
            render()
        }
        setContentView(R.layout.activity_device_profiles)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_profile_create)) { showCreateDialog() }
        render()
    }

    override fun onDestroy() {
        themeChangeReceiver?.let { ThemeChangeNotifier.unregister(this, it) }
        super.onDestroy()
    }

    // ==================== 渲染 ====================

    private fun render() {
        findViewById<TextView>(R.id.profiles_hint).text =
            "按档隔离：数据源、地址、口令、API 路径、设备名、Wi-Fi 白名单、套餐额度与账期。\n" +
            "全局共享：主题、刷新间隔、通知阈值、免打扰、省电暂停。"

        renderList()
    }

    private fun renderList() {
        val container = findViewById<LinearLayout>(R.id.profiles_container)
        container.removeAllViews()

        val activeId = DeviceProfiles.activeId(this)
        for (id in DeviceProfiles.ids(this)) {
            val card = LayoutInflater.from(this)
                .inflate(R.layout.layout_common_setting_item, container, false) as ViewGroup

            val isActive = id == activeId
            CommonSettingsItemHelper.setupSettingItem(
                itemView = card,
                iconRes = if (isActive) R.drawable.ic_check else R.drawable.ic_router,
                title = DeviceProfiles.displayName(this, id) + if (isActive) "（当前）" else "",
                subtitle = DeviceProfiles.summaryOf(this, id),
                onClick = { if (!isActive) switchTo(id) }
            )
            // 当前档没有可执行的主操作，弱化一下，免得用户反复点
            card.alpha = if (isActive) 0.75f else 1f
            card.findViewById<View>(R.id.common_item_arrow)?.visibility =
                if (isActive) View.INVISIBLE else View.VISIBLE
            card.setOnLongClickListener {
                showManageDialog(id)
                true
            }
            container.addView(card)
        }
    }

    // ==================== 操作 ====================

    private fun switchTo(id: String) {
        // 丢 session、清累加器、清展示缓存、重排周期任务都在 activate 里
        DeviceProfiles.activate(this, id)
        render()
        ToastUtil.showDropToast(
            this, ToastStyle.SUCCESS, "已切换到 ${DeviceProfiles.displayName(this, id)}"
        )
    }

    private fun showManageDialog(id: String) {
        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cornerRadius = 12f * resources.displayMetrics.density
        val isDefault = id == DeviceProfiles.DEFAULT_ID

        CommonDialogHelper.showSelectionDialog(
            context = this,
            title = DeviceProfiles.displayName(this, id),
            iconRes = R.drawable.ic_router,
            onFill = { content, dialog ->
                content.addView(TextView(this).apply {
                    text = DeviceProfiles.summaryOf(this@DeviceProfilesActivity, id)
                    textSize = 12f
                    alpha = 0.6f
                    setTextColor(textPrimary)
                    setPadding(dp2px(4), 0, dp2px(4), dp2px(12))
                })

                if (id != DeviceProfiles.activeId(this)) {
                    content.addView(CommonDialogHelper.buildOptionView(
                        context = this,
                        label = "切换到此档",
                        textPrimary = textPrimary,
                        accent = accent,
                        cornerRadius = cornerRadius
                    ) {
                        dialog.dismiss()
                        switchTo(id)
                    })
                }

                content.addView(CommonDialogHelper.buildOptionView(
                    context = this,
                    label = "重命名",
                    textPrimary = textPrimary,
                    accent = accent,
                    cornerRadius = cornerRadius
                ) {
                    dialog.dismiss()
                    showRenameDialog(id)
                })

                // 默认档不允许删除：它承载着老版本升级上来的无前缀数据
                if (!isDefault) {
                    content.addView(CommonDialogHelper.buildOptionView(
                        context = this,
                        label = "删除此档",
                        subtitle = "只删连接配置，流量历史保留",
                        textPrimary = textPrimary,
                        accent = accent,
                        cornerRadius = cornerRadius
                    ) {
                        dialog.dismiss()
                        confirmDelete(id)
                    })
                }
            }
        )
    }

    private fun showCreateDialog() {
        showNameDialog(
            title = "新建配置档",
            initial = "",
            hint = "例如 F50、随身 WiFi"
        ) { name ->
            val id = DeviceProfiles.create(this, name.ifEmpty { "新设备" }, copyFromActive = true)
            switchTo(id)
        }
    }

    private fun showRenameDialog(id: String) {
        showNameDialog(
            title = "重命名配置档",
            initial = DeviceProfiles.displayName(this, id),
            hint = "留空则用默认名称"
        ) { name ->
            DeviceProfiles.rename(this, id, name)
            render()
        }
    }

    private fun confirmDelete(id: String) {
        CommonDialogHelper.showWarningConfirmDialog(
            context = this,
            title = "删除配置档",
            message = "将删除「${DeviceProfiles.displayName(this, id)}」的全部连接配置\n" +
                "该设备的流量历史也会一并清除",
            confirmText = "删除",
            onConfirm = {
                // 流量记录现在按档隔离，档删掉后这些记录再没有入口能看到，得跟着清掉
                TrafficRecordManager.clearProfile(id)
                // 删的正好是当前档时 delete 内部会自动切回默认档
                DeviceProfiles.delete(this, id)
                // 循环名单里的残留 id 要一起清掉，否则三击循环会命中一个不存在的档
                val alive = DeviceProfiles.ids(this).toSet()
                SPUtil.setWidgetCycleProfiles(
                    this, SPUtil.getWidgetCycleProfiles(this).filter { it in alive }.toSet()
                )
                render()
                ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "已删除")
            }
        )
    }

    /** 单字段命名弹窗 */
    private fun showNameDialog(
        title: String,
        initial: String,
        hint: String,
        onSave: (String) -> Unit
    ) {
        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon)
            .setImageResource(R.drawable.ic_filter)
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val input = CommonSettingsItemHelper.createThemedEditText(
            this, hint = hint, text = initial, inputType = InputType.TYPE_CLASS_TEXT
        )
        dialog.findViewById<LinearLayout>(R.id.common_dialog_content).addView(input)

        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.VISIBLE
        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "保存"
            setOnClickListener {
                onSave(input.text.toString().trim())
                dialog.dismiss()
            }
        }
        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        dialog.show()
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
