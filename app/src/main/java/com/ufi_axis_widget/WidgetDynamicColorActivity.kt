package com.ufi_axis_widget

import android.content.BroadcastReceiver
import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ufi_axis_widget.util.AnimationUtil
import com.ufi_axis_widget.util.CommonDialogHelper
import com.ufi_axis_widget.util.CommonSettingsItemHelper
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.ThemeChangeNotifier
import com.ufi_axis_widget.util.ThemeColors
import com.ufi_axis_widget.util.ThemeUtil
import com.ufi_axis_widget.util.ToastUtil
import com.ufi_axis_widget.util.ToastStyle
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.widget.AppearanceScope
import com.ufi_axis_widget.util.widget.WidgetAppearance
import com.ufi_axis_widget.util.widget.WidgetPrefs
import com.ufi_axis_widget.widget.BaseWifiWidget

class WidgetDynamicColorActivity : AppCompatActivity() {

    private var themeChangeReceiver: BroadcastReceiver? = null

    /** 当前正在设置哪个组件的外观。全局默认时所有读写都落在 SPUtil 上 */
    private val apScope = AppearanceScope()

    private var activeScopeDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)
        setContentView(R.layout.activity_widget_dynamic_color)

        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            AnimationUtil.applyCircleRevealPulse(this@WidgetDynamicColorActivity) {
                ThemeUtil.applyThemeSync(this@WidgetDynamicColorActivity, ThemeUtil.PageType.SETTINGS_LIST)
            }
        }

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        initAppearanceScopeItem()
        initDynamicColorItem()
        initDynamicContrastItem()
        initDynamicAdvancedItem()
        initDynamicColorSourceItem()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)
        // 离开本页期间组件可能被删掉，先修正作用域再刷新各项
        updateAppearanceScopeUi()
        // 每次恢复时重新检查背景图状态
        updateDynamicBackgroundLockState()
        updateDynamicContrastSubtitle()
        updateDynamicAdvancedSubtitle()
        updateDynamicColorSourceSubtitle()
    }

    override fun onDestroy() {
        try { activeScopeDialog?.dismiss() } catch (_: Exception) {}
        activeScopeDialog = null
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    // ==================== 外观作用域：当前正在设置哪个组件 ====================
    //
    // 与「小组件设置」页共用 AppearanceScope：两页改的是同一组键，
    // 各自实现一遍作用域判断，漏判的表现是「在这一页改的永远是全局」。

    private fun initAppearanceScopeItem() {
        // 实例作用域下首次改动会自动开启独立外观，这里同步开关和提示，
        // 否则用户不知道开关已被打开
        apScope.onAutoOverride = {
            updateAppearanceScopeUi()
            ToastUtil.showDropToast(this, ToastStyle.INFO, "已为${apScope.name(this)}开启独立外观")
        }
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_appearance_scope),
            iconRes = R.drawable.ic_widget_large,
            title = "正在设置",
            showSubtitle = true,
            subtitle = apScope.name(this),
            onClick = ::showAppearanceScopeDialog
        )

        CommonSettingsItemHelper.setupSwitchItem(
            itemView = findViewById(R.id.item_appearance_override),
            iconRes = R.drawable.ic_widget_small,
            label = "单独设置外观",
            subtitle = "关闭时跟随全局默认",
            initialChecked = apScope.isOverridden(this)
        ) { checked ->
            if (apScope.kind == null) return@setupSwitchItem
            apScope.setOverride(this, checked)
            reloadDynamicValues()
            updateAppearanceScopeUi()
            BaseWifiWidget.renderAllWidgets(this, force = true)
        }

        updateAppearanceScopeUi()
    }

    /** 刷新作用域相关文案与「单独设置」开关显隐 */
    private fun updateAppearanceScopeUi() {
        val placed = apScope.placed(this)
        // 正在设置的组件可能已从桌面移除，回落到全局后各项要重读
        if (apScope.resetIfGone(this)) reloadDynamicValues()

        try {
            findViewById<View>(R.id.item_appearance_scope)
                .findViewById<TextView>(R.id.common_item_subtitle)?.text = apScope.name(this)
        } catch (e: Exception) {
            DebugLogger.w("WidgetDynamicColorActivity", "updateAppearanceScopeUi failed: ${e.message}")
        }

        // 桌面上一个组件都没有时「给哪个组件单独设置」无从谈起，两行都隐藏
        findViewById<View>(R.id.item_appearance_scope).visibility =
            if (placed.isEmpty()) View.GONE else View.VISIBLE

        val overrideItem = findViewById<View>(R.id.item_appearance_override)
        if (apScope.kind == null || placed.isEmpty()) {
            // 全局作用域没有「单独设置」可言，整行隐藏而不是置灰：置灰会被当成暂时不可用
            overrideItem.visibility = View.GONE
        } else {
            overrideItem.visibility = View.VISIBLE
            ThemeUtil.setSwitchVisualSilently(overrideItem, apScope.isOverridden(this))
            overrideItem.findViewById<TextView>(R.id.common_switch_subtitle)?.apply {
                text = if (apScope.isOverridden(this@WidgetDynamicColorActivity)) "这个组件使用自己的外观"
                       else "关闭时跟随全局默认"
                visibility = View.VISIBLE
            }
        }
    }

    private fun showAppearanceScopeDialog() {
        activeScopeDialog?.takeIf { it.isShowing }?.dismiss()
        activeScopeDialog = apScope.showPicker(this) {
            reloadDynamicValues()
            updateAppearanceScopeUi()
            ToastUtil.showDropToast(this, ToastStyle.INFO, "正在设置：${apScope.name(this)}")
        }
    }

    /** 切换作用域或开关独立外观后，把界面上的动态配色各项重新读一遍 */
    private fun reloadDynamicValues() {
        updateDynamicBackgroundLockState()
        updateDynamicContrastSubtitle()
        updateDynamicAdvancedSubtitle()
        updateDynamicColorSourceSubtitle()
    }

    // ── 按作用域读写。全局值同时充当作用域键缺失时的默认值，表现为「继承全局」──

    private fun apDynamic(): Boolean =
        apScope.bool(this, WidgetPrefs.DYNAMIC_COLOR) { SPUtil.getWidgetDynamicColor(it) }

    private fun apSetDynamic(v: Boolean) =
        apScope.setBool(this, WidgetPrefs.DYNAMIC_COLOR, v) { c, x -> SPUtil.setWidgetDynamicColor(c, x) }

    private fun apContrast(): Int =
        apScope.int(this, WidgetPrefs.DYNAMIC_CONTRAST) { SPUtil.getWidgetDynamicContrast(it) }

    private fun apSetContrast(v: Int) =
        apScope.setInt(this, WidgetPrefs.DYNAMIC_CONTRAST, v) { c, x -> SPUtil.setWidgetDynamicContrast(c, x) }

    private fun apAdvanced(): Boolean =
        apScope.bool(this, WidgetPrefs.DYNAMIC_ADVANCED) { SPUtil.getWidgetDynamicAdvanced(it) }

    private fun apSetAdvanced(v: Boolean) =
        apScope.setBool(this, WidgetPrefs.DYNAMIC_ADVANCED, v) { c, x -> SPUtil.setWidgetDynamicAdvanced(c, x) }

    private fun apLightBg(): Int = apScope.int(this, WidgetPrefs.DYN_ADV_LIGHT_BG) { SPUtil.getDynAdvLightBg(it) }
    private fun apSetLightBg(v: Int) =
        apScope.setInt(this, WidgetPrefs.DYN_ADV_LIGHT_BG, v) { c, x -> SPUtil.setDynAdvLightBg(c, x) }

    private fun apLightTxt(): Int = apScope.int(this, WidgetPrefs.DYN_ADV_LIGHT_TXT) { SPUtil.getDynAdvLightTxt(it) }
    private fun apSetLightTxt(v: Int) =
        apScope.setInt(this, WidgetPrefs.DYN_ADV_LIGHT_TXT, v) { c, x -> SPUtil.setDynAdvLightTxt(c, x) }

    private fun apDarkBg(): Int = apScope.int(this, WidgetPrefs.DYN_ADV_DARK_BG) { SPUtil.getDynAdvDarkBg(it) }
    private fun apSetDarkBg(v: Int) =
        apScope.setInt(this, WidgetPrefs.DYN_ADV_DARK_BG, v) { c, x -> SPUtil.setDynAdvDarkBg(c, x) }

    private fun apDarkTxt(): Int = apScope.int(this, WidgetPrefs.DYN_ADV_DARK_TXT) { SPUtil.getDynAdvDarkTxt(it) }
    private fun apSetDarkTxt(v: Int) =
        apScope.setInt(this, WidgetPrefs.DYN_ADV_DARK_TXT, v) { c, x -> SPUtil.setDynAdvDarkTxt(c, x) }

    private fun apSatBoost(): Int = apScope.int(this, WidgetPrefs.DYN_ADV_SAT_BOOST) { SPUtil.getDynAdvSatBoost(it) }
    private fun apSetSatBoost(v: Int) =
        apScope.setInt(this, WidgetPrefs.DYN_ADV_SAT_BOOST, v) { c, x -> SPUtil.setDynAdvSatBoost(c, x) }

    private fun apSource(): Int =
        apScope.int(this, WidgetPrefs.DYNAMIC_SOURCE) { SPUtil.getWidgetDynamicColorSource(it) }

    private fun apSetSource(v: Int) =
        apScope.setInt(this, WidgetPrefs.DYNAMIC_SOURCE, v) { c, x -> SPUtil.setWidgetDynamicColorSource(c, x) }

    private fun apSetFollowApp(v: Boolean) =
        apScope.setBool(this, WidgetPrefs.FOLLOW_APP_THEME, v) { c, x -> SPUtil.setWidgetFollowAppTheme(c, x) }

    /** 用户是否已为当前作用域设置并启用了小组件背景图 */
    private fun hasWidgetBgImage(): Boolean {
        val uri = apScope.str(this, WidgetPrefs.BG_IMAGE_URI) { SPUtil.getWidgetBgImageUri(it) }
        val enabled = apScope.bool(this, WidgetPrefs.BG_IMAGE_ENABLED) { SPUtil.getWidgetBgImageEnabled(it) }
        return uri.isNotBlank() && enabled
    }

    /** 检查背景图状态，没设背景时禁用动态配色并提示 */
    private fun updateDynamicBackgroundLockState() {
        val hasBg = hasWidgetBgImage()
        val item = findViewById<View>(R.id.item_widget_dynamic_color)
        val track = item.findViewById<View>(R.id.common_switch_track)
        val subtitle = item.findViewById<android.widget.TextView>(R.id.common_switch_subtitle)

        if (!hasBg) {
            apSetDynamic(false)
            subtitle?.apply {
                text = "请先返回「小组件设置」中设置背景图片后再启用"
                visibility = View.VISIBLE
            }
            // 如果开关视觉上处于 ON，通过 setChecked 引用同步为 OFF
            @Suppress("UNCHECKED_CAST")
            val setChecked = track?.tag as? ((Boolean) -> Unit)
            setChecked?.invoke(false)
            track?.isEnabled = false
            track?.alpha = 0.4f
            updateDynamicColorVisibility(false)
        } else {
            subtitle?.visibility = View.GONE
            track?.isEnabled = true
            track?.alpha = 1f
            // 切作用域后开关视觉要跟着当前作用域的值走，否则会留着上一个组件的状态
            val on = apDynamic()
            ThemeUtil.setSwitchVisualSilently(item, on)
            updateDynamicColorVisibility(on)
        }
    }

    // ==================== 1. 动态配色 Material You（开关） ====================
    private fun initDynamicColorItem() {
        val dynamicColorItem = findViewById<View>(R.id.item_widget_dynamic_color)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            dynamicColorItem.visibility = View.GONE
            return
        }

        val hasBg = hasWidgetBgImage()

        CommonSettingsItemHelper.setupSwitchItem(
            itemView = dynamicColorItem,
            iconRes = R.drawable.ic_dynamic_colors,
            label = "动态配色 (Material You)",
            subtitle = if (hasBg) "根据壁纸主色自动适配文字和图标颜色" else "请先返回「小组件设置」中设置背景图片后再启用",
            initialChecked = hasBg && apDynamic(),
            onToggle = { checked ->
                // 每次回调都重新检查背景状态，防止用户从设置页修改后此处仍用旧值
                if (!hasWidgetBgImage()) {
                    // 背景已被关闭，静默回退开关视觉（不触发回调）
                    ThemeUtil.setSwitchVisualSilently(dynamicColorItem, false)
                    return@setupSwitchItem
                }
                // 检测所有可能的主题冲突：跟随主题 / 手动深浅色 / 手动配色（都按当前作用域取值）
                val followApp = apScope.bool(this, WidgetPrefs.FOLLOW_APP_THEME) { SPUtil.getWidgetFollowAppTheme(it) }
                val themeMode = apScope.str(this, WidgetPrefs.THEME_MODE) { SPUtil.getWidgetTheme(it) }
                val colorTheme = apScope.int(this, WidgetPrefs.COLOR_THEME) { SPUtil.getWidgetColorThemeIndex(it) }
                val hasThemeConflict = checked && (
                    followApp || themeMode != "follow_app" || colorTheme != 0
                )
                if (hasThemeConflict) {
                    CommonDialogHelper.showWarningConfirmDialog(
                        context = this,
                        title = "互斥提醒",
                        message = "开启「动态配色」将自动关闭「跟随应用主题」及手动配色设置，由壁纸颜色独立控制配色方案。三种配色模式只能开启一种。",
                        confirmText = "继续开启",
                        cancelText = "取消",
                        onConfirm = {
                            apSetDynamic(true)
                            apSetFollowApp(false)
                            updateDynamicColorVisibility(true)
                            BaseWifiWidget.renderAllWidgets(this, force = true)
                            // 静默恢复开关视觉为 ON（不触发回调，避免重复弹窗）
                            ThemeUtil.setSwitchVisualSilently(dynamicColorItem, true)
                        }
                    )
                    // 用户尚未确认，静默回退开关视觉（不触发回调）
                    ThemeUtil.setSwitchVisualSilently(dynamicColorItem, false)
                    return@setupSwitchItem
                }
                apSetDynamic(checked)
                updateDynamicColorVisibility(checked)
                BaseWifiWidget.renderAllWidgets(this, force = true)
            }
        )

        if (!hasBg) {
            val track = dynamicColorItem.findViewById<View>(R.id.common_switch_track)
            track?.isEnabled = false
            track?.alpha = 0.4f
        }

        updateDynamicColorVisibility(hasBg && apDynamic())
    }


    private fun updateDynamicColorVisibility(enabled: Boolean) {
        val show = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        findViewById<View>(R.id.item_widget_dynamic_contrast).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.item_widget_dynamic_advanced).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.item_widget_dynamic_color_source).visibility = if (show) View.VISIBLE else View.GONE
    }

    // ==================== 2. 动态配色对比度 ====================
    private fun initDynamicContrastItem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_widget_dynamic_contrast),
            iconRes = R.drawable.ic_eye,
            title = "动态配色对比度",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDynamicContrastDialog
        )
        updateDynamicContrastSubtitle()
    }

    private fun updateDynamicContrastSubtitle() {
        val levelName = when (apContrast()) {
            0 -> "柔和"; 1 -> "标准"; 2 -> "强烈"; else -> "标准"
        }
        try {
            findViewById<View>(R.id.item_widget_dynamic_contrast)
                .findViewById<android.widget.TextView>(R.id.common_item_subtitle)?.text = levelName
        } catch (e: Exception) { DebugLogger.w("WidgetDynamicColorActivity", "set contrast subtitle failed: ${e.message}") }
    }

    private fun showDynamicContrastDialog() {
        val currentLevel = apContrast()
        val density = resources.displayMetrics.density
        val cornerRadius = 12f * density

        CommonDialogHelper.showSelectionDialog(
            this,
            title = "动态配色对比度 · ${apScope.name(this)}",
            iconRes = R.drawable.ic_eye,
            onFill = { content, dialog ->
                val textPrimary = ThemeColors.textPrimary(this@WidgetDynamicColorActivity)
                val accent = ThemeColors.accent(this@WidgetDynamicColorActivity)
                val selectedBg = CommonDialogHelper.createSelectedBg(accent, cornerRadius)
                val unselectedBg = CommonDialogHelper.createUnselectedBg(this@WidgetDynamicColorActivity, cornerRadius)

                val options = listOf(
                    Triple(0, "柔和", "低对比度，色彩更柔和，适合浅色壁纸"),
                    Triple(1, "标准", "中等对比度，平衡可读性与美观"),
                    Triple(2, "强烈", "高对比度，文字更清晰，色彩更鲜明")
                )
                options.forEach { (level, label, desc) ->
                    val isSelected = level == currentLevel
                    val itemLayout = createOptionItem(label, desc, textPrimary, if (isSelected) selectedBg else unselectedBg, isSelected)
                    itemLayout.setOnClickListener {
                        apSetContrast(level)
                        updateDynamicContrastSubtitle()
                        BaseWifiWidget.renderAllWidgets(this@WidgetDynamicColorActivity, force = true)
                        dialog.dismiss()
                    }
                    content.addView(itemLayout)
                }
            }
        )
    }


    // ==================== 3. 动态配色高级设置 ====================
    private fun initDynamicAdvancedItem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_widget_dynamic_advanced),
            iconRes = R.drawable.ic_settings,
            title = "高级参数调节",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDynamicAdvancedDialog
        )
        updateDynamicAdvancedSubtitle()
    }

    private fun updateDynamicAdvancedSubtitle() {
        val advanced = apAdvanced()
        val label = if (advanced) {
            val lBg = apLightBg()
            val lTx = apLightTxt()
            val dBg = apDarkBg()
            val dTx = apDarkTxt()
            val sat = apSatBoost()
            "浅底$lBg/文$lTx · 深底$dBg/文$dTx · 饱和$sat%"
        } else "关闭"
        try {
            findViewById<View>(R.id.item_widget_dynamic_advanced)
                .findViewById<android.widget.TextView>(R.id.common_item_subtitle)?.text = label
        } catch (e: Exception) { DebugLogger.w("WidgetDynamicColorActivity", "set advanced subtitle failed: ${e.message}") }
    }

    private fun showDynamicAdvancedDialog() {
        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)
        dialog.findViewById<android.widget.TextView>(R.id.common_dialog_title).text = "高级参数调节 · ${apScope.name(this)}"

        dialog.findViewById<android.widget.ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_settings)
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<android.widget.LinearLayout>(R.id.common_dialog_content)
        val scrollContainer = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
        }
        val innerContent = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollContainer.addView(innerContent)
        content.addView(scrollContainer)

        var advancedEnabled = apAdvanced()

        val slidersContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            alpha = if (advancedEnabled) 1f else 0.4f
            isEnabled = advancedEnabled
        }

        var lightBg = apLightBg().toFloat()
        var lightTxt = apLightTxt().toFloat()
        var darkBg = apDarkBg().toFloat()
        var darkTxt = apDarkTxt().toFloat()
        var satBoost = apSatBoost().toFloat()


        fun addSlider(title: String, desc: String, min: Float, max: Float, default: Float, suffix: String = "",
                      tickStep: Float = 0f,
                      onUpdate: (Float) -> Unit): Pair<android.widget.TextView, com.ufi_axis_widget.view.ThemeSlider> {
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp2px(14) }
            }
            val label = android.widget.TextView(this).apply {
                text = "$title: ${default.toInt()}$suffix"
                textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ThemeColors.textPrimary(this@WidgetDynamicColorActivity))
            }
            container.addView(label)
            container.addView(android.widget.TextView(this).apply {
                text = desc; textSize = 11f; alpha = 0.7f
                setTextColor(ThemeColors.textSecondary(this@WidgetDynamicColorActivity))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(2) }
            })
            val slider = com.ufi_axis_widget.view.ThemeSlider(this).apply {
                minValue = min; maxValue = max; stepSize = 1f
                currentValue = default
                isEnabled = advancedEnabled
                if (tickStep > 0f) {
                    tickStepSize = tickStep
                    tickLabelFormatter = { v -> "${v.toInt()}$suffix" }
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44)
                )
                onValueChanging = { v ->
                    label.text = "$title: ${v.toInt()}$suffix"
                    onUpdate(v)
                }
            }
            container.addView(slider)
            slidersContainer.addView(container)
            return Pair(label, slider)
        }

        // 先创建所有滑块（存入列表以便开关回调引用）
        val sliders = listOf(
            addSlider("浅色背景亮度", "值越高背景越亮", 85f, 99f, lightBg, tickStep = 2f) { v -> lightBg = v },
            addSlider("浅色文字亮度", "值越低文字越深，对比度越高", 0f, 40f, lightTxt, tickStep = 5f) { v -> lightTxt = v },
            addSlider("深色背景亮度", "值越低背景越暗", 4f, 20f, darkBg, tickStep = 4f) { v -> darkBg = v },
            addSlider("深色文字亮度", "值越高文字越亮，对比度越高", 75f, 98f, darkTxt, tickStep = 5f) { v -> darkTxt = v },
            addSlider("饱和度增强", "100%为原始，>100%增强色彩鲜艳度", 50f, 150f, satBoost, "%", tickStep = 40f) { v -> satBoost = v }
        )

        val defaultSliderValues = listOf(97f, 12f, 8f, 90f, 100f)

        // 再创建开关（回调中引用 sliders 列表，此时已定义）
        val switchWrapper = layoutInflater.inflate(R.layout.layout_common_switch, innerContent, false)
        switchWrapper.findViewById<android.widget.TextView>(R.id.common_switch_label).text = "启用高级调节"
        val switchTrack = switchWrapper.findViewById<View>(R.id.common_switch_track)
        com.ufi_axis_widget.util.ThemeUtil.setupSwitch(switchWrapper, advancedEnabled) { isChecked ->
            advancedEnabled = isChecked
            slidersContainer.alpha = if (isChecked) 1f else 0.4f
            slidersContainer.isEnabled = isChecked
            // 同步每个滑块自身的 enabled 状态（容器 disabled 不阻断自定义 View 触摸）
            sliders.forEach { (_, slider) -> slider.isEnabled = isChecked }
        }
        innerContent.addView(switchWrapper)

        innerContent.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = dp2px(12); bottomMargin = dp2px(12) }
            setBackgroundColor(ThemeColors.divider(this@WidgetDynamicColorActivity))
            alpha = 0.3f
        })

        innerContent.addView(slidersContainer)

        CommonDialogHelper.applyThemeToViewTree(innerContent, this)

        val btnContainer = dialog.findViewById<android.widget.LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE
        AnimationUtil.applyScaleClickAnimation(
            dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary).apply {
                text = "确定"
            }
        ) {
            apSetAdvanced(advancedEnabled)
            if (advancedEnabled) {
                apSetLightBg(lightBg.toInt())
                apSetLightTxt(lightTxt.toInt())
                apSetDarkBg(darkBg.toInt())
                apSetDarkTxt(darkTxt.toInt())
                apSetSatBoost(satBoost.toInt())
            }

            updateDynamicAdvancedSubtitle()
            BaseWifiWidget.renderAllWidgets(this@WidgetDynamicColorActivity, force = true)
            dialog.dismiss()
        }
        AnimationUtil.applyScaleClickAnimation(
            dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary).apply {
                visibility = View.VISIBLE; text = "恢复默认"
            }
        ) {
            // 重置开关为关闭状态
            if (advancedEnabled) {
                switchTrack.performClick()
            }
            // 重置滑块数值为出厂默认值
            sliders.forEachIndexed { i, (_, slider) ->
                slider.currentValue = defaultSliderValues[i]
            }
            lightBg = 97f; lightTxt = 12f; darkBg = 8f; darkTxt = 90f; satBoost = 100f
            // 不保存 SPUtil、不刷新小组件、不关闭弹窗
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        dialog.show()
    }

    // ==================== 4. 动态配色色源选择 ====================
    private fun initDynamicColorSourceItem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_widget_dynamic_color_source),
            iconRes = R.drawable.ic_palette,
            title = "动态配色色源",
            showSubtitle = true,
            subtitle = "",
            onClick = ::showDynamicColorSourceDialog
        )
        updateDynamicColorSourceSubtitle()
    }

    private fun updateDynamicColorSourceSubtitle() {
        val names = listOf("Primary (主色)", "Secondary (次色)", "Tertiary (第三色)", "Neutral (中性色)", "Neutral Variant (中性变体)")
        val source = apSource()
        val name = names.getOrElse(source) { "Primary (主色)" }
        try {
            findViewById<View>(R.id.item_widget_dynamic_color_source)
                .findViewById<android.widget.TextView>(R.id.common_item_subtitle)?.text = name
        } catch (e: Exception) { DebugLogger.w("WidgetDynamicColorActivity", "set color source subtitle failed: ${e.message}") }
    }

    private fun showDynamicColorSourceDialog() {
        val currentSource = apSource()
        val density = resources.displayMetrics.density
        val cornerRadius = 12f * density

        // 先检查能否从小组件背景图提取颜色（按当前作用域取背景图，否则永远取的是全局那张）
        val appearance = WidgetAppearance.of(this, apScope.kind, apScope.appWidgetId)
        val availableColors = ThemeColors.getAvailableWallpaperColors(this@WidgetDynamicColorActivity, appearance)
        val hasValidColor = availableColors.any { (_, color) -> color != null }
        if (!hasValidColor) {
            DebugLogger.w("WidgetDynamicColorActivity", "无法从小组件背景图提取色源颜色")
            ToastUtil.showDropToast(this@WidgetDynamicColorActivity, ToastStyle.WARNING, "无法提取背景图颜色，请检查小组件背景图片设置")
            return
        }

        CommonDialogHelper.showSelectionDialog(
            this,
            title = "动态配色色源 · ${apScope.name(this)}",
            iconRes = R.drawable.ic_palette,
            onFill = { content, dialog ->
                val textPrimary = ThemeColors.textPrimary(this@WidgetDynamicColorActivity)
                val accent = ThemeColors.accent(this@WidgetDynamicColorActivity)
                val selectedBg = CommonDialogHelper.createSelectedBg(accent, cornerRadius)
                val unselectedBg = CommonDialogHelper.createUnselectedBg(this@WidgetDynamicColorActivity, cornerRadius)

                availableColors.forEachIndexed { index, (name, color) ->
                    val isSelected = index == currentSource
                    val itemLayout = createColorSourceOptionItem(name, color, textPrimary, if (isSelected) selectedBg else unselectedBg, isSelected)
                    itemLayout.setOnClickListener {
                        apSetSource(index)
                        updateDynamicColorSourceSubtitle()
                        BaseWifiWidget.renderAllWidgets(this@WidgetDynamicColorActivity, force = true)
                        dialog.dismiss()
                    }
                    content.addView(itemLayout)
                }
            }
        )
    }


    private fun createColorSourceOptionItem(
        label: String, color: Int?, textPrimary: Int, bg: android.graphics.drawable.GradientDrawable, isSelected: Boolean
    ): android.widget.LinearLayout {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(6) }
            background = bg
            isClickable = true
            isFocusable = true

            addView(android.view.View(this@WidgetDynamicColorActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp2px(14), dp2px(14))
                val dotColor = color ?: 0xFF888888.toInt()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(dotColor)
                    if (isSelected) setStroke(dp2px(1), 0xFFFFFFFF.toInt())
                }
            })

            addView(android.widget.TextView(this@WidgetDynamicColorActivity).apply {
                text = label
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp2px(10) }
            })
        }
    }

    // ==================== 工具方法 ====================

    private fun createOptionItem(
        label: String, desc: String, textPrimary: Int, bg: android.graphics.drawable.GradientDrawable, isSelected: Boolean
    ): android.widget.LinearLayout {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp2px(12), dp2px(14), dp2px(12), dp2px(14))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(8) }
            background = bg; isClickable = true; isFocusable = true
            addView(android.widget.TextView(this@WidgetDynamicColorActivity).apply {
                text = label; textSize = 15f; gravity = android.view.Gravity.CENTER
                setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
            })
            addView(android.widget.TextView(this@WidgetDynamicColorActivity).apply {
                text = desc; textSize = 11f; gravity = android.view.Gravity.CENTER
                alpha = if (isSelected) 0.85f else 0.55f
                setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(4) }
            })
        }
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
