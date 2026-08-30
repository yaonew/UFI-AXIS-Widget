package com.ufi_axis_widget.util.widget

import android.app.Activity
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.ufi_axis_widget.R
import com.ufi_axis_widget.util.CommonDialogHelper
import com.ufi_axis_widget.util.ThemeColors
import com.ufi_axis_widget.util.ThemeUtil
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry
import com.ufi_axis_widget.view.ThemeSlider
import com.ufi_axis_widget.widget.WidgetRegistry
import com.ufi_axis_widget.widget.WidgetSpec

/**
 * 「某个小组件形态的显示项」编辑弹窗。
 *
 * 开关列表完全由 [WidgetSpec.fields] 生成，所以新增一种小组件不需要碰这个文件。
 * 设置页（类型层，`appWidgetId = null`）与桌面配置入口（实例层，传真实 id）共用同一份实现，
 * 避免出现「两个入口行为不一致」这种典型的复制粘贴 bug。
 */
object WidgetFieldsDialog {

    /**
     * @param appWidgetId 非 null 时写入实例层，只影响这一个已放置的小组件
     * @param onSaved     保存后回调（调用方负责刷新副标题、重绘小组件）
     * @param onDismiss   弹窗关闭（含取消）后回调，配置 Activity 用它结束自己
     */
    fun show(
        activity: Activity,
        spec: WidgetSpec,
        appWidgetId: Int? = null,
        onSaved: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        val caps = DeviceDataSourceRegistry.currentCapabilities(activity)
        // 显示项跟着当前数据源实际会渲染的那套变体走，否则列出的开关和桌面上看到的对不上
        val variant = spec.variantFor(caps)
        val fields = variant.fields
        val dialog = CommonDialogHelper.createAnimatedDialog(activity)
        dialog.setContentView(R.layout.layout_common_dialog)

        val scopeLabel = if (appWidgetId != null) "（仅此组件）" else ""
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = spec.displayName + scopeLabel
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_eye)
        CommonDialogHelper.applyThemeToDialogRoot(activity, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        val states = fields.associate { field ->
            field.key to WidgetPrefs.getBool(activity, spec.kind, field.key, field.default, appWidgetId)
        }.toMutableMap()

        val grid = GridLayout(activity).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(grid)

        for (field in fields) {
            val supported = field.requires?.isSupported(caps) ?: true
            val switchWrapper = activity.layoutInflater.inflate(R.layout.layout_common_switch, grid, false)
            switchWrapper.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                val m = dp(activity, 4)
                setMargins(m, m, m, m)
            }
            switchWrapper.findViewById<TextView>(R.id.common_switch_label).apply {
                textSize = 12f
                // 副标题拼进同一个 TextView，而不是给 layout_common_switch 加一行控件：
                // 那个布局还有 6 处其他调用方，为这里改公共布局是把影响面放大。
                // FieldSpec.subtitle 写的都是「无数据时自动隐藏」这类关键说明，不显示等于白写
                text = if (field.subtitle.isNullOrEmpty()) {
                    field.label
                } else {
                    SpannableStringBuilder(field.label).apply {
                        val start = length
                        append("\n").append(field.subtitle)
                        setSpan(RelativeSizeSpan(0.78f), start, length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(
                            ForegroundColorSpan(ThemeColors.textSecondary(activity)),
                            start, length, SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
            ThemeUtil.setupSwitch(switchWrapper, states[field.key] == true) { isChecked ->
                states[field.key] = isChecked
            }
            // 能力不支持的项打开也不会显示（渲染层同样按能力隐藏），留着可点只会让人以为坏了
            if (!supported) {
                // 必须禁到 track 上：点击监听是 setupSwitch 装在 track 这个子 View 上的，
                // 把父容器 disable 掉并不会阻止子 View 收到点击 —— 灰着还能拨。
                switchWrapper.findViewById<View>(R.id.common_switch_track)?.isEnabled = false
                switchWrapper.isEnabled = false
                switchWrapper.alpha = 0.4f
            }

            grid.addView(switchWrapper)
        }

        CommonDialogHelper.applyThemeToViewTree(grid, activity)

        // 固定槽位说明：这个形态少了哪些开关、为什么少，必须写清楚，
        // 否则「4×2 有电量开关、1×1 没有」会被当成 bug
        variant.fixedNote?.let { note ->
            content.addView(TextView(activity).apply {
                text = note
                setTextColor(ThemeColors.textSecondary(activity))
                textSize = 11f
                alpha = 0.8f
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 10) }
            })
        }


        // 字体大小：仅声明了 hasFontSize 的形态有
        var currentFontSize =
            WidgetPrefs.getInt(activity, spec.kind, WidgetPrefs.FONT_SIZE, 9, appWidgetId).toFloat()
        if (spec.hasFontSize) {
            val fontSizeLabel = TextView(activity).apply {
                text = "字体大小: ${currentFontSize.toInt()}sp"
                textSize = 14f
                setTextColor(ThemeColors.textPrimary(activity))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 16) }
            }
            content.addView(fontSizeLabel)

            content.addView(ThemeSlider(activity).apply {
                minValue = 6f
                maxValue = 14f
                stepSize = 1f
                currentValue = currentFontSize
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44)
                )
                onValueChange = { v ->
                    currentFontSize = v
                    fontSizeLabel.text = "字体大小: ${v.toInt()}sp"
                }
            })
        }

        val missing = WidgetRegistry.missingCapabilities(spec, caps)
        if (missing.isNotEmpty()) {
            content.addView(TextView(activity).apply {
                text = "当前数据源「${DeviceDataSourceRegistry.current(activity).type.displayName}」" +
                    "下这些槽位无数据：${missing.joinToString("、") { it.label }}。" +
                    "对应项已置灰并在组件上隐藏；如需完整信息请更换数据源，或改用适配当前数据源的组件。"
                setTextColor(ThemeColors.textSecondary(activity))
                textSize = 11f
                alpha = 0.8f
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 12) }
            })
        }

        dialog.findViewById<LinearLayout>(R.id.common_dialog_button_container).visibility = View.VISIBLE
        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "确定"
            setOnClickListener {
                for (field in fields) {
                    WidgetPrefs.setBool(
                        activity, spec.kind, field.key, states[field.key] == true, appWidgetId
                    )
                }
                if (spec.hasFontSize) {
                    WidgetPrefs.setInt(
                        activity, spec.kind, WidgetPrefs.FONT_SIZE,
                        currentFontSize.toInt(), appWidgetId
                    )
                }
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        onDismiss?.let { cb -> dialog.setOnDismissListener { cb() } }

        CommonDialogHelper.setupDialogWindow(activity, dialog)
        dialog.show()
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
