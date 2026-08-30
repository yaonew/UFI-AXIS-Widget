package com.ufi_axis_widget

import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.content.BroadcastReceiver
import com.ufi_axis_widget.util.AnimationUtil
import com.ufi_axis_widget.util.CommonDialogHelper
import com.ufi_axis_widget.util.CommonSettingsItemHelper
import com.ufi_axis_widget.util.DeviceProfiles
import com.ufi_axis_widget.util.PopupViewUtil
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.ThemeChangeNotifier
import com.ufi_axis_widget.util.ThemeColors
import com.ufi_axis_widget.util.ThemeUtil
import com.ufi_axis_widget.util.ThemedSliderUtil
import com.ufi_axis_widget.util.ToastStyle
import com.ufi_axis_widget.util.ToastUtil
import com.ufi_axis_widget.util.WidgetLabelToggle
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry
import com.ufi_axis_widget.util.widget.AppearanceScope
import com.ufi_axis_widget.util.widget.BgCrop
import com.ufi_axis_widget.util.widget.WidgetFieldsDialog

import com.ufi_axis_widget.util.widget.WidgetPrefs

import com.ufi_axis_widget.view.ThemeSlider
import com.ufi_axis_widget.widget.BaseWifiWidget
import com.ufi_axis_widget.widget.WidgetRegistry
import com.ufi_axis_widget.widget.WidgetSpec

import com.ufi_axis_widget.worker.WifiWorker
import com.ufi_axis_widget.util.DebugLogger
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class WidgetSettingsActivity : AppCompatActivity() {

    private var widgetIntervalMinutes: Int = 15

    // ── renderAllWidgets 防抖机制：停止操作 300ms 后才执行渲染，避免滑块拖动时每帧触发 ──
    private var renderDebounceJob: Job? = null

    /** 防抖渲染小组件：取消之前的定时器，300ms 后在 IO 线程执行渲染 */
    private fun debouncedRenderWidgets() {
        renderDebounceJob?.cancel()
        renderDebounceJob = lifecycleScope.launch {
            delay(300)
            withContext(Dispatchers.IO) {
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
            }
        }
    }

    /** 立即渲染（用于确认按钮等需要即时生效的场景），仍在 IO 线程避免阻塞主线程 */
    private fun renderWidgetsNow() {
        renderDebounceJob?.cancel()
        renderDebounceJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                BaseWifiWidget.renderAllWidgets(this@WidgetSettingsActivity, force = true)
            }
        }
    }

    // 小组件主题
    private var widgetTheme: String = "follow_app"
    private var widgetColorThemeIndex: Int = 0

    // 活跃弹窗引用

    private var activeWidgetThemeDialog: Dialog? = null
    private var activeDisplayInfoDialog: Dialog? = null
    private var activeWidgetIntervalDialog: Dialog? = null
    private var activeBgImageDialog: Dialog? = null
    private var bgDialogContent: LinearLayout? = null
    private var activeBgOpacityDialog: Dialog? = null
    private var activeWidgetColorDialog: Dialog? = null

    // 主题变更接收器
    private var themeChangeReceiver: BroadcastReceiver? = null

    // 小组件背景
    private var widgetBgImageUri: String = ""
    private var widgetBgImageEnabled: Boolean = false
    private var widgetBgOpacity: Int = 100
    // 弹窗内的待选状态（确认后才提交到 SP）
    private var pendingBgUri: String = ""
    private var pendingBgEnabled: Boolean = false
    /** 图片选择器（为小组件背景选图） */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // 弹窗刷新在 handlePickedWidgetBgImage 内部拷完文件后做：
            // 拷贝是异步的，在这里刷新会拿到还没赋值的 pendingBgUri
            handlePickedWidgetBgImage(uri)
        }
    }

    /**
     * 未提交的取景，key 见 [cropKey]。
     *
     * 和背景图一样走「待选 → 确认才落 SP」：取景在弹窗里点了取消就该一起丢掉，
     * 立即写盘会留下一堆指向没被采用的图的孤儿键。
     */
    private val pendingCrops = mutableMapOf<String, FloatArray>()

    /** 正在取景的目标，用于把返回的矩形认领到对应形态上 */
    private var pendingCropTarget: String? = null

    /** 取景启动器：只回传归一化矩形，不产生文件 */
    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingCropTarget
        pendingCropTarget = null
        if (result.resultCode == RESULT_OK && target != null) {
            result.data?.getFloatArrayExtra("crop_rect")?.let { rect ->
                if (rect.size == 4) pendingCrops[target] = rect
            }
        }
        // 取景完成后刷新弹窗预览
        showWidgetBgImageDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.WIDGET_SETTINGS)
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            AnimationUtil.applyCircleRevealPulse(this@WidgetSettingsActivity) {
                ThemeUtil.applyThemeSync(this@WidgetSettingsActivity, ThemeUtil.PageType.WIDGET_SETTINGS)
            }
            updateWidgetThemeSubtitle()
            updateDisplayInfoSubtitle()
            updateWidgetIntervalSubtitle()

            updateWidgetBgImageSubtitle()
            updateWidgetBgOpacitySubtitle()
        }
        setContentView(R.layout.activity_widget_settings)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        initAppearanceScopeItem()
        initFollowAppThemeItem()
        initWidgetThemeItem()
        initWidgetColorThemeItem()
        initDisplayInfoItem()
        initWidgetIntervalItem()
        initWidgetTripleTapItem()

        initWidgetBgImageItem()
        initWidgetBgOpacityItem()
        initWidgetCompatibilityItem()
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.WIDGET_SETTINGS)

        updateFollowAppThemeSubtitle()

        // ── 动态配色锁定：开启动态配色后禁用主题相关设置 ──
        applyDynamicColorLockState()

        updateWidgetThemeSubtitle()
        updateWidgetColorThemeSubtitle()
        updateDisplayInfoSubtitle()
        updateWidgetIntervalSubtitle()
        updateWidgetBgImageSubtitle()

        updateWidgetBgOpacitySubtitle()
        updateCompatibilitySubtitle()
        // 桌面上的实例可能在离开期间被删掉，作用域文案要重算
        updateAppearanceScopeUi()
    }

    override fun onDestroy() {
        renderDebounceJob?.cancel()
        // 防止 Activity 销毁时弹窗未关闭导致 WindowLeaked 异常
        try { activeWidgetThemeDialog?.dismiss() } catch (_: Exception) {}
        try { activeDisplayInfoDialog?.dismiss() } catch (_: Exception) {}
        try { activeWidgetIntervalDialog?.dismiss() } catch (_: Exception) {}
        try { activeBgImageDialog?.dismiss() } catch (_: Exception) {}
        try { activeBgOpacityDialog?.dismiss() } catch (_: Exception) {}
        try { activeWidgetColorDialog?.dismiss() } catch (_: Exception) {}
        try { activeAppearanceScopeDialog?.dismiss() } catch (_: Exception) {}
        activeWidgetThemeDialog = null
        activeDisplayInfoDialog = null
        activeWidgetIntervalDialog = null
        activeBgImageDialog = null
        activeBgOpacityDialog = null
        activeWidgetColorDialog = null
        activeAppearanceScopeDialog = null
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    // ==================== 外观作用域：当前正在设置哪个组件 ====================
    //
    // 只有两种可选作用域：「全局默认」和「桌面上某个具体组件」。
    // 刻意不给「某形态的全部组件」这一层选项 —— 数据层支持（WidgetAppearance 的
    // 实例 → 形态 → 全局三层），但四个形态都列出来会让这个弹窗有十来项，
    // 而真正的需求是「桌面上这个组件长得跟别的不一样」。形态层仍作为回退层存在。

    /** 作用域状态与读写入口，与「实验功能 → 动态取色」页共用同一套实现 */
    private val apScope = AppearanceScope()

    private var activeAppearanceScopeDialog: Dialog? = null

    private fun placedScopes(): List<Triple<String, Int, String>> = apScope.placed(this)

    /** 作用域的显示名，直接用于「正在设置」副标题与弹窗标题 */
    private fun scopeName(): String = apScope.name(this)

    /** 当前作用域是否读写独立外观键。全局作用域恒为 false，即永远走 SPUtil */
    private fun scopeOverridden(): Boolean = apScope.isOverridden(this)

    // ── 8 组外观读写。默认值刻意取全局当前值：作用域键缺失时表现为「继承全局」──

    private fun apTheme(): String = apScope.str(this, WidgetPrefs.THEME_MODE) { SPUtil.getWidgetTheme(it) }

    private fun apSetTheme(v: String) =
        apScope.setStr(this, WidgetPrefs.THEME_MODE, v) { c, x -> SPUtil.setWidgetTheme(c, x) }

    private fun apFollowApp(): Boolean =
        apScope.bool(this, WidgetPrefs.FOLLOW_APP_THEME) { SPUtil.getWidgetFollowAppTheme(it) }

    private fun apSetFollowApp(v: Boolean) =
        apScope.setBool(this, WidgetPrefs.FOLLOW_APP_THEME, v) { c, x -> SPUtil.setWidgetFollowAppTheme(c, x) }

    private fun apColorTheme(): Int =
        apScope.int(this, WidgetPrefs.COLOR_THEME) { SPUtil.getWidgetColorThemeIndex(it) }

    private fun apSetColorTheme(v: Int) =
        apScope.setInt(this, WidgetPrefs.COLOR_THEME, v) { c, x -> SPUtil.setWidgetColorThemeIndex(c, x) }

    private fun apDynamic(): Boolean =
        apScope.bool(this, WidgetPrefs.DYNAMIC_COLOR) { SPUtil.getWidgetDynamicColor(it) }

    private fun apSetDynamic(v: Boolean) =
        apScope.setBool(this, WidgetPrefs.DYNAMIC_COLOR, v) { c, x -> SPUtil.setWidgetDynamicColor(c, x) }

    private fun apBgUri(): String =
        apScope.str(this, WidgetPrefs.BG_IMAGE_URI) { SPUtil.getWidgetBgImageUri(it) }

    private fun apSetBgUri(v: String) =
        apScope.setStr(this, WidgetPrefs.BG_IMAGE_URI, v) { c, x -> SPUtil.setWidgetBgImageUri(c, x) }

    private fun apBgEnabled(): Boolean =
        apScope.bool(this, WidgetPrefs.BG_IMAGE_ENABLED) { SPUtil.getWidgetBgImageEnabled(it) }

    private fun apSetBgEnabled(v: Boolean) =
        apScope.setBool(this, WidgetPrefs.BG_IMAGE_ENABLED, v) { c, x -> SPUtil.setWidgetBgImageEnabled(c, x) }

    private fun apOpacity(): Int = apScope.int(this, WidgetPrefs.BG_OPACITY) { SPUtil.getWidgetBgOpacity(it) }

    private fun apSetOpacity(v: Int) =
        apScope.setInt(this, WidgetPrefs.BG_OPACITY, v) { c, x -> SPUtil.setWidgetBgOpacity(c, x) }

    private fun apClip(): Boolean =
        apScope.bool(this, WidgetPrefs.CLIP_TO_OUTLINE) { SPUtil.getWidgetClipToOutline(it) }

    private fun apSetClip(v: Boolean) =
        apScope.setBool(this, WidgetPrefs.CLIP_TO_OUTLINE, v) { c, x -> SPUtil.setWidgetClipToOutline(c, x) }


    private fun initAppearanceScopeItem() {
        // 在实例作用域下首次改动会自动开启独立外观，这里同步开关和提示，
        // 否则用户看到「改动只影响了这一个」却不知道开关已经被打开
        apScope.onAutoOverride = {
            updateAppearanceScopeUi()
            ToastUtil.showDropToast(this, ToastStyle.INFO, "已为${scopeName()}开启独立外观")
        }
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_appearance_scope),
            iconRes = R.drawable.ic_widget_large,
            title = "正在设置",
            showSubtitle = true,
            subtitle = scopeName(),
            onClick = ::showAppearanceScopeDialog
        )

        CommonSettingsItemHelper.setupSwitchItem(
            itemView = findViewById(R.id.item_appearance_override),
            iconRes = R.drawable.ic_widget_small,
            label = "单独设置外观",
            subtitle = "关闭时跟随全局默认",
            initialChecked = scopeOverridden()
        ) { checked ->
            if (apScope.kind == null) return@setupSwitchItem
            // 打开时把当前全局值快照进作用域：不快照的话外观会瞬间跳到一堆默认值上
            apScope.setOverride(this, checked)
            reloadAppearanceValues()
            updateAppearanceScopeUi()
            renderWidgetsNow()
        }


        updateAppearanceScopeUi()
    }

    /**
     * 切换作用域或开关独立外观后，把界面上缓存的外观值重新读一遍。
     *
     * [widgetTheme] / [widgetColorThemeIndex] / [widgetBgImageUri] 等字段是各项的显示缓存，
     * 不重读会出现「切到别的组件，界面还显示上一个组件的配色」。
     */
    private fun reloadAppearanceValues() {
        widgetTheme = apTheme()
        widgetColorThemeIndex = apColorTheme()
        widgetBgImageUri = apBgUri()
        widgetBgImageEnabled = apBgEnabled()
        widgetBgOpacity = apOpacity()

        applyDynamicColorLockState()
        updateWidgetThemeSubtitle()
        // 主题项的图标（日/月/日月）跟着模式变，切作用域时不重设会留着上一个作用域的图标
        try {
            findInItem<ImageView>(R.id.item_widget_theme, R.id.common_item_icon)
                ?.setImageResource(getWidgetThemeIcon())
        } catch (e: Exception) {
            DebugLogger.w("WidgetSettingsActivity", "reloadAppearanceValues: theme icon failed: ${e.message}")
        }
        updateWidgetColorThemeSubtitle()
        updateWidgetBgImageSubtitle()
        updateWidgetBgOpacitySubtitle()
        updateCompatibilitySubtitle()
        // 显示信息也跟着作用域走，副标题要一起换，否则还挂着上一个作用域的名字
        updateDisplayInfoSubtitle()
        ThemeUtil.setSwitchVisualSilently(findViewById(R.id.item_widget_follow_theme), apFollowApp())
    }

    /** 刷新作用域相关的所有文案：入口副标题、两个分区标题、独立开关显隐 */
    private fun updateAppearanceScopeUi() {
        val placed = placedScopes()

        // 正在设置的那个组件可能已经从桌面上被删掉（离开设置页期间），回落到全局
        if (apScope.resetIfGone(this)) reloadAppearanceValues()

        val name = scopeName()
        try {
            findInItem<TextView>(R.id.item_appearance_scope, R.id.common_item_subtitle)?.text = name
            // 分区标题也带上作用域：背景设置离「正在设置」那一行有一段距离，
            // 滚下去之后只看标题会不知道改的是谁
            findViewById<TextView>(R.id.label_appearance_section)?.text = "外观设置 · $name"
            findViewById<TextView>(R.id.label_background_section)?.text = "背景设置 · $name"
        } catch (e: Exception) {
            DebugLogger.w("WidgetSettingsActivity", "updateAppearanceScopeUi failed: ${e.message}")
        }

        // 桌面上一个组件都没有时，「给哪个组件单独设置」无从谈起，两行都隐藏，
        // 外观区就退回改造前的样子
        findViewById<View>(R.id.item_appearance_scope).visibility =
            if (placed.isEmpty()) View.GONE else View.VISIBLE

        val overrideItem = findViewById<View>(R.id.item_appearance_override)
        if (apScope.kind == null || placed.isEmpty()) {
            // 全局作用域没有「单独设置」可言，整行隐藏而不是置灰：置灰会被当成暂时不可用
            overrideItem.visibility = View.GONE
        } else {
            overrideItem.visibility = View.VISIBLE
            ThemeUtil.setSwitchVisualSilently(overrideItem, scopeOverridden())
            findInItem<TextView>(R.id.item_appearance_override, R.id.common_switch_subtitle)?.apply {
                text = if (scopeOverridden()) "这个组件使用自己的外观" else "关闭时跟随全局默认"
                visibility = View.VISIBLE
            }
        }
    }

    /**
     * 选择作用域：全局默认 + 桌面上已添加的每个组件。
     *
     * 弹窗本体在 [AppearanceScope.showPicker]，与「实验功能 → 动态取色」页共用。
     */
    private fun showAppearanceScopeDialog() {
        activeAppearanceScopeDialog?.takeIf { it.isShowing }?.dismiss()
        activeAppearanceScopeDialog = apScope.showPicker(this) {
            reloadAppearanceValues()
            updateAppearanceScopeUi()
            ToastUtil.showDropToast(this, ToastStyle.INFO, "正在设置：${scopeName()}")
        }
    }


    // ==================== 0. 跟随应用主题（开关） ====================
    private fun initFollowAppThemeItem() {
        val isFollow = apFollowApp()
        val followItem = findViewById<View>(R.id.item_widget_follow_theme)
        CommonSettingsItemHelper.setupSwitchItem(
            itemView = followItem,
            iconRes = R.drawable.ic_sun_moon,
            label = "跟随应用主题",
            initialChecked = isFollow
        ) { isChecked ->
            if (isChecked && isWidgetDynamicActive()) {
                CommonDialogHelper.showWarningConfirmDialog(
                    context = this,
                    title = "互斥提醒",
                    message = "开启「跟随应用主题」将自动关闭「动态配色」，小组件配色将恢复为应用主题控制。",
                    confirmText = "继续开启",
                    cancelText = "取消",
                    onConfirm = {
                        apSetDynamic(false)
                        apSetFollowApp(true)
                        updateFollowAppThemeSubtitle()
                        renderWidgetsNow()
                        updateWidgetThemeItemState(true, animate = true)
                        applyDynamicColorLockState()
                        // 静默恢复开关视觉为 ON（不触发回调，避免重复弹窗）
                        ThemeUtil.setSwitchVisualSilently(followItem, true)
                    }
                )
                // 静默回退开关视觉（不触发回调），等待用户确认
                ThemeUtil.setSwitchVisualSilently(followItem, false)
                return@setupSwitchItem
            }
            apSetFollowApp(isChecked)
            updateFollowAppThemeSubtitle()
            renderWidgetsNow()
            updateWidgetThemeItemState(isChecked, animate = true)
            applyDynamicColorLockState()
        }
        updateFollowAppThemeSubtitle()
        applyDynamicColorLockState()
    }

    private fun updateFollowAppThemeSubtitle() {
        // Switch layout doesn't usually have a subtitle in the common_switch layout,
        // but we can add one if we want or just keep it simple.
    }

    /** 当前作用域的动态配色是否激活（API 31+ 且该作用域的开关开启） */
    private fun isWidgetDynamicActive(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && apDynamic()
    }

    /**
     * 当动态配色激活时：禁用"跟随应用主题"开关 + 隐藏"小组件主题"/"小组件配色"，并同步 SP 状态；
     * 当动态配色关闭时：恢复开关交互，根据跟随主题状态正常显示/隐藏子项。
     */
    private fun applyDynamicColorLockState() {
        val isDynamicActive = isWidgetDynamicActive()
        val followItem = findViewById<View>(R.id.item_widget_follow_theme)
        val track = followItem.findViewById<View>(R.id.common_switch_track)
        val subtitle = followItem.findViewById<android.widget.TextView>(R.id.common_switch_subtitle)

        val themeItem = findViewById<View>(R.id.item_widget_theme)
        val colorThemeItem = findViewById<View>(R.id.item_widget_color_theme)
        val themeContentContainer = findViewById<View>(R.id.layout_widget_theme_content)

        if (isDynamicActive) {
            // SP 层面也确保互斥：动态配色开启时跟随主题必须关闭
            if (apFollowApp()) {
                apSetFollowApp(false)
            }
            // ── 跟随应用主题：禁用并显示提示 ──
            subtitle?.apply {
                text = "动态配色已开启，跟随主题由系统壁纸自动控制"
                visibility = View.VISIBLE
            }
            track?.isEnabled = false
            track?.alpha = 0.4f

            // ── 动态配色激活时，完全隐藏主题设置区域（它们对用户无意义） ──
            themeContentContainer.visibility = View.GONE
        } else {
            // ── 跟随应用主题：恢复 ──
            subtitle?.visibility = View.GONE
            track?.isEnabled = true
            track?.alpha = 1f
            val isFollow = apFollowApp()
            updateWidgetThemeItemState(isFollow)

            // ── 小组件主题 / 小组件配色：恢复 ──
            themeItem.alpha = 1f
            themeItem.isClickable = true
            themeItem.findViewById<TextView>(R.id.common_item_subtitle)?.apply {
                text = when (widgetTheme) {
                    "light" -> "浅色"; "dark" -> "深色"; else -> "浅色"
                }
                visibility = View.VISIBLE
                alpha = 1f
            }

            colorThemeItem.alpha = 1f
            colorThemeItem.isClickable = true
            colorThemeItem.findViewById<TextView>(R.id.common_item_subtitle)?.apply {
                val palette = ThemeColors.getById(this@WidgetSettingsActivity, widgetColorThemeIndex, isWidget = true)
                text = palette.name
                visibility = View.VISIBLE
                alpha = 1f
            }
        }
    }

    private fun updateWidgetThemeItemState(isFollow: Boolean, animate: Boolean = false) {
        val container = findViewById<View>(R.id.layout_widget_theme_content)
        val targetVisibility = if (isFollow) View.GONE else View.VISIBLE
        applyVisibility(container, targetVisibility, animate)
    }

    /**
     * 设置 View 可见性，带统一的淡入/淡出动画。
     *
     * 淡入：300ms DecelerateInterpolator，纯 alpha 过渡
     * 淡出：250ms AccelerateInterpolator，结束后 GONE 并复位 alpha
     */
    private fun applyVisibility(view: View, targetVisibility: Int, animate: Boolean) {
        if (view.visibility == targetVisibility) return
        view.animate().cancel()
        if (animate) {
            if (targetVisibility == View.VISIBLE) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setListener(null)
            } else {
                view.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        view.visibility = View.GONE
                        view.alpha = 1f
                    }
            }
        } else {
            view.visibility = targetVisibility
            view.alpha = if (targetVisibility == View.VISIBLE) 1f else 0f
        }
    }

    // ==================== 1. 小组件主题（弹窗选择） ====================
    private fun initWidgetThemeItem() {
        widgetTheme = apTheme()

        try {
            findInItem<ImageView>(R.id.item_widget_theme, R.id.common_item_icon)?.setImageResource(getWidgetThemeIcon())
            findInItem<TextView>(R.id.item_widget_theme, R.id.common_item_title)?.text = "小组件主题"
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "initWidgetThemeItem: setting icon/title failed: ${e.message}") }
        updateWidgetThemeSubtitle()

        findViewById<View>(R.id.item_widget_theme).setOnClickListener {
            showWidgetThemeDialog()
        }
    }

    private fun getWidgetThemeIcon(): Int = when (widgetTheme) {
        "light" -> R.drawable.ic_sun
        "dark" -> R.drawable.ic_moon
        else -> R.drawable.ic_sun_moon
    }

    private fun updateWidgetThemeSubtitle() {
        val modeName = when (widgetTheme) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "浅色" // 默认为浅色，如果主开关关闭
        }
        try {
            findInItem<TextView>(R.id.item_widget_theme, R.id.common_item_subtitle)?.text = modeName
            findInItem<ImageView>(R.id.item_widget_theme, R.id.common_item_icon)?.setImageResource(getWidgetThemeIcon())
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "updateWidgetThemeSubtitle: setting subtitle/icon failed: ${e.message}") }
    }

    private fun showWidgetThemeDialog() {
        activeWidgetThemeDialog?.takeIf { it.isShowing }?.dismiss()
        activeWidgetThemeDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "小组件主题 · ${scopeName()}"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_sun_moon)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val cornerRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, cornerRadius)
        val unselectedBg = makeUnselectedBg(cornerRadius)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val options = listOf(
            "light" to "浅色",
            "dark" to "深色"
        )
        options.forEach { (key, label) ->
            val isSelected = key == widgetTheme
            content.addView(buildDialogOptionView(label, textPrimary,
                selectedBg, unselectedBg) {
                if (isWidgetDynamicActive()) {
                    dialog.dismiss()
                    CommonDialogHelper.showWarningConfirmDialog(
                        context = this,
                        title = "互斥提醒",
                        message = "手动修改小组件主题将自动关闭「动态配色」，配色将恢复为手动设置。",
                        confirmText = "继续修改",
                        cancelText = "取消",
                        onConfirm = {
                            widgetTheme = key
                            apSetTheme(key)
                            apSetDynamic(false)
                            updateWidgetThemeSubtitle()
                            renderWidgetsNow()
                            applyDynamicColorLockState()
                        }
                    )
                } else {
                    widgetTheme = key
                    apSetTheme(key)
                    updateWidgetThemeSubtitle()
                    renderWidgetsNow()
                    dialog.dismiss()
                }
            }.apply {
                if (isSelected) {
                    background = selectedBg
                    setTextColor(0xFFFFFFFF.toInt())
                }
            })
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeWidgetThemeDialog = dialog
        dialog.show()
    }

    // ==================== 1.1 小组件颜色主题（弹窗选择） ====================
    private fun initWidgetColorThemeItem() {
        widgetColorThemeIndex = apColorTheme()
        try {
            findInItem<ImageView>(R.id.item_widget_color_theme, R.id.common_item_icon)?.setImageResource(R.drawable.ic_palette)
            findInItem<TextView>(R.id.item_widget_color_theme, R.id.common_item_title)?.text = "小组件配色"
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "initWidgetColorThemeItem: setting icon/title failed: ${e.message}") }
        updateWidgetColorThemeSubtitle()

        findViewById<View>(R.id.item_widget_color_theme).setOnClickListener {
            showWidgetColorThemeDialog()
        }
    }

    private fun updateWidgetColorThemeSubtitle() {
        val palette = ThemeColors.getById(this, widgetColorThemeIndex, isWidget = true)
        try {
            findInItem<TextView>(R.id.item_widget_color_theme, R.id.common_item_subtitle)?.text = palette.name
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "updateWidgetColorThemeSubtitle: setting subtitle failed: ${e.message}") }
    }

    private fun showWidgetColorThemeDialog() {
        activeWidgetColorDialog?.takeIf { it.isShowing }?.dismiss()
        activeWidgetColorDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "小组件配色 · ${scopeName()}"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_palette)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val chipRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, chipRadius)
        val unselectedBg = makeUnselectedBg(chipRadius)

        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)

        ThemeColors.ALL.forEach { palette ->
            grid.addView(buildWidgetColorOption(palette.id, palette.name, palette.accentLight,
                textPrimary, cardBg, selectedBg, unselectedBg, content, dialog))
        }

        // 自定义选项
        val customAccent = SPUtil.getWidgetCustomAccentLight(this)
        grid.addView(buildWidgetColorOption(-1, "自定义", customAccent,
            textPrimary, cardBg, selectedBg, unselectedBg, content, dialog))

        // 自定义面板
        val customPanel = createCustomWidgetColorPanel(dialog, textPrimary, accent, cardBg)
        content.addView(customPanel)
        if (widgetColorThemeIndex == -1) customPanel.visibility = View.VISIBLE

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeWidgetColorDialog = dialog
        dialog.show()
    }

    private fun buildWidgetColorOption(
        index: Int, name: String, dotColor: Int,
        textPrimary: Int, cardBg: Int,
        selectedBg: GradientDrawable, unselectedBg: GradientDrawable,
        content: LinearLayout, dialog: Dialog
    ): View {
        val isSelected = index == widgetColorThemeIndex
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))

            val params = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
            }
            layoutParams = params

            background = if (isSelected) selectedBg else unselectedBg
            isClickable = true
            isFocusable = true
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(10), dp2px(10))
            background = makeDot(dotColor, if (isSelected) 0xFFFFFFFF.toInt() else dotColor)
        }
        row.addView(dot)

        val label = TextView(this).apply {
            text = name
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp2px(10)
            }
        }
        row.addView(label)

        row.setOnClickListener {
            if (index == -1) {
                val panel = content.findViewWithTag<View>("custom_widget_color_panel")
                panel?.visibility = if (panel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            } else if (isWidgetDynamicActive()) {
                dialog.dismiss()
                CommonDialogHelper.showWarningConfirmDialog(
                    context = this,
                    title = "互斥提醒",
                    message = "手动修改小组件配色将自动关闭「动态配色」，配色将恢复为手动设置。",
                    confirmText = "继续修改",
                    cancelText = "取消",
                    onConfirm = {
                        widgetColorThemeIndex = index
                        apSetColorTheme(index)
                        apSetDynamic(false)
                        updateWidgetColorThemeSubtitle()
                        renderWidgetsNow()
                        applyDynamicColorLockState()
                    }
                )
            } else {
                widgetColorThemeIndex = index
                apSetColorTheme(index)
                updateWidgetColorThemeSubtitle()
                renderWidgetsNow()
                dialog.dismiss()
            }
        }
        return row
    }

    private fun createCustomWidgetColorPanel(dialog: Dialog, textPrimary: Int, accent: Int, cardBg: Int): View {
        val panel = LinearLayout(this).apply {
            tag = "custom_widget_color_panel"
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(12)
            }
        }

        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp2px(12) }
            setBackgroundColor(textPrimary)
            alpha = 0.12f
        })

        panel.addView(TextView(this).apply {
            text = "自定义小组件强调色"
            setTextColor(textPrimary)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(10)
            }
        }

        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(40), dp2px(40))
            background = makeDot(SPUtil.getWidgetCustomAccentLight(this@WidgetSettingsActivity), 0)
        }
        inputRow.addView(swatch)

        val tvStatusTip = TextView(this).apply {
            text = "支持十六进制格式 (如 #7B61FF)"
            setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
            textSize = 11f
            alpha = 0.8f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(8)
            }
        }

        val etColor = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp2px(40), 1f).apply { marginStart = dp2px(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; setColor(cardBg); cornerRadius = 8f * resources.displayMetrics.density
                setStroke(1, if (ThemeColors.isDark(this@WidgetSettingsActivity)) 0x30FFFFFF.toInt() else 0x20000000)
            }
            gravity = android.view.Gravity.CENTER
            hint = "#7B61FF"
            inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            setTextColor(textPrimary)
            setHintTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
            textSize = 13f
            setPadding(dp2px(12), 0, dp2px(12), 0)
            val currentCustomColor = SPUtil.getWidgetCustomAccentLight(this@WidgetSettingsActivity)
            setText(String.format(Locale.US, "#%06X", 0xFFFFFF and currentCustomColor))

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val input = s?.toString()?.trim() ?: ""
                    if (input.isEmpty()) {
                        tvStatusTip.text = "支持十六进制格式 (如 #7B61FF)"
                        tvStatusTip.setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
                        return
                    }
                    val formatted = if (input.startsWith("#")) input else "#$input"
                    try {
                        val color = android.graphics.Color.parseColor(formatted)
                        swatch.background = makeDot(color, 0)
                        tvStatusTip.text = "支持十六进制格式 (如 #7B61FF)"
                        tvStatusTip.setTextColor(ThemeColors.textSecondary(this@WidgetSettingsActivity))
                    } catch (e: Exception) {
                        DebugLogger.w("WidgetSettingsActivity", "afterTextChanged: parsing color failed: ${e.message}")
                        tvStatusTip.text = "无效的颜色代码"
                        tvStatusTip.setTextColor(0xFFE53935.toInt())
                    }
                }
            })
        }
        inputRow.addView(etColor)

        val btnApply = TextView(this).apply {
            text = "确定"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp2px(16), 0, dp2px(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp2px(40)).apply { marginStart = dp2px(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; setColor(accent); cornerRadius = 20f * resources.displayMetrics.density
            }
            setOnClickListener {
                val input = etColor.text.toString().trim()
                val formatted = if (input.startsWith("#")) input else "#$input"
                val color = try {
                    android.graphics.Color.parseColor(formatted)
                } catch (e: Exception) {
                    // 用户手输的色值，非法很正常，只记日志不打扰
                    DebugLogger.w(
                        "WidgetSettingsActivity",
                        "showWidgetColorThemeDialog: parsing color failed: ${e.message}"
                    )
                    null
                }
                if (color != null) {
                    val applyCustomColor = {
                        val darkColor = adjustBrightness(color, 0.85f)
                        // 自定义强调色本身仍是全局的：配色索引 -1 只是「指向自定义色」这个引用，
                        // 多个作用域同时选 -1 就是共用同一份自定义色，这是预期行为
                        SPUtil.setWidgetCustomAccentLight(this@WidgetSettingsActivity, color)
                        SPUtil.setWidgetCustomAccentDark(this@WidgetSettingsActivity, darkColor)
                        widgetColorThemeIndex = -1
                        apSetColorTheme(-1)
                        apSetDynamic(false)
                        updateWidgetColorThemeSubtitle()
                        renderWidgetsNow()
                        applyDynamicColorLockState()
                        ToastUtil.showDropToast(this@WidgetSettingsActivity, ToastStyle.SUCCESS, "自定义配色已应用")
                    }
                    if (isWidgetDynamicActive()) {
                        dialog.dismiss()
                        CommonDialogHelper.showWarningConfirmDialog(
                            context = this@WidgetSettingsActivity,
                            title = "互斥提醒",
                            message = "手动设置自定义配色将自动关闭「动态配色」，配色将恢复为手动设置。",
                            confirmText = "继续修改",
                            cancelText = "取消",
                            onConfirm = {
                                applyCustomColor()
                            }
                        )
                    } else {
                        val darkColor = adjustBrightness(color, 0.85f)
                        SPUtil.setWidgetCustomAccentLight(this@WidgetSettingsActivity, color)
                        SPUtil.setWidgetCustomAccentDark(this@WidgetSettingsActivity, darkColor)
                        widgetColorThemeIndex = -1
                        apSetColorTheme(-1)
                        updateWidgetColorThemeSubtitle()
                        renderWidgetsNow()
                        dialog.dismiss()
                        ToastUtil.showDropToast(this@WidgetSettingsActivity, ToastStyle.SUCCESS, "自定义配色已应用")
                    }
                } else {
                    ToastUtil.showDropToast(this@WidgetSettingsActivity, ToastStyle.WARNING, "颜色格式无效")
                }
            }
        }
        inputRow.addView(btnApply)
        panel.addView(inputRow)
        panel.addView(tvStatusTip)

        return panel
    }

    private fun adjustBrightness(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] *= factor
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun makeDot(color: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp2px(1), stroke)
        }
    }

    // ==================== 2. 显示信息（按组件形态动态生成） ====================

    /**
     * 「显示信息」入口。
     *
     * 改造前这里是三份几乎一样的代码（4×2 / 2×1 / 4×1 各一套硬编码开关），
     * 现在开关列表由 [WidgetSpec.fields] 生成，新增一种小组件时这个文件不需要任何改动。
     *
     * 只有一种形态启用时直接打开它的显示项 —— 让用户在只有一个选项的列表里
     * 先点一下再进去，纯属白挨一次点击。
     */
    private fun initDisplayInfoItem() {
        CommonSettingsItemHelper.setupSettingItem(
            findViewById(R.id.item_display_info),
            iconRes = R.drawable.ic_eye,
            title = "显示信息",
            showSubtitle = true,
            subtitle = "",
            onClick = ::openDisplayInfo
        )
        updateDisplayInfoSubtitle()
    }

    /** 「正在设置」选中的形态；全局作用域或该形态已停用时返回 null */
    private fun scopedSpec(): WidgetSpec? =
        apScope.kind?.let { k -> WidgetRegistry.enabled.firstOrNull { it.kind == k } }

    private fun updateDisplayInfoSubtitle() {
        val specs = WidgetRegistry.enabled
        val scoped = scopedSpec()
        val label = when {
            specs.isEmpty() -> "暂无可配置的组件"
            // 作用域指名了组件时显示项就跟着它走，副标题必须说明改的是谁，
            // 否则用户会以为这一项仍然是全局的
            scoped != null -> scopeName()
            // 单形态时副标题写它自己的说明，比「1 种组件可配置」有信息量
            specs.size == 1 -> specs[0].description
            else -> {
                val placed = specs.count { WidgetRegistry.isPlaced(this, it) }
                if (placed == 0) "${specs.size} 种组件可配置"
                else "已放置 $placed 种 · 共 ${specs.size} 种可配置"
            }
        }
        try {
            findInItem<TextView>(R.id.item_display_info, R.id.common_item_subtitle)?.text = label
        } catch (e: Exception) {
            DebugLogger.w("WidgetSettingsActivity", "updateDisplayInfoSubtitle: setting subtitle failed: ${e.message}")
        }
    }

    /** 作用域已指名组件时直达它的显示项；全局作用域下单形态直达、多形态才先选形态 */
    private fun openDisplayInfo() {
        // 「正在设置」里已经挑过一次组件了，这里再挑一次形态是重复劳动，
        // 而且两处各挑一次必然出现「挑的和改的不是同一个」
        scopedSpec()?.let { return showFieldsDialog(it, apScope.appWidgetId) }
        val specs = WidgetRegistry.enabled
        when (specs.size) {
            0 -> ToastUtil.showDropToast(this, ToastStyle.INFO, "当前没有启用的小组件形态")
            1 -> showFieldsDialog(specs[0])
            else -> showWidgetKindDialog()
        }
    }

    /**
     * 多形态时的第一步：选组件形态。双栏网格，与作用域选择弹窗同一套观感。
     *
     * 单元格里只放「已放置 / 未放置」和缺数据项数：形态的功能说明（[WidgetSpec.description]）
     * 和「哪些槽位没数据」原本逐项写在副标题里，双栏宽度下会折成四五行，
     * 四个形态叠起来整屏都是字。真正影响选择的只有「这个我放没放」，
     * 数据源相关的解释对所有形态都一样，提到弹窗顶部说一次即可。
     */
    private fun showWidgetKindDialog() {
        activeDisplayInfoDialog?.takeIf { it.isShowing }?.dismiss()
        activeDisplayInfoDialog = null

        val caps = DeviceDataSourceRegistry.currentCapabilities(this)
        // 文案里必须点出当前数据源名字：只写「当前数据源不提供 XXX」挂在组件条目下面，
        // 会被读成「这个组件 / 这个组件对应的数据源不提供 XXX」，属性归错了对象。
        val sourceName = DeviceDataSourceRegistry.current(this).type.displayName
        val specs = WidgetRegistry.enabled
        val anyMissing = specs.any { WidgetRegistry.missingCapabilities(it, caps).isNotEmpty() }

        activeDisplayInfoDialog = CommonDialogHelper.showSelectionDialog(
            context = this,
            title = "选择组件",
            iconRes = R.drawable.ic_eye,
            onFill = { content, dialog ->
                content.addView(TextView(this).apply {
                    text = buildString {
                        append("当前数据源「")
                        append(sourceName)
                        append("」")
                        if (anyMissing) append("。标注「N 项无数据」的组件，对应槽位会自动隐藏。")
                    }
                    textSize = 12f
                    alpha = 0.6f
                    setTextColor(ThemeColors.textPrimary(this@WidgetSettingsActivity))
                    setPadding(dp2px(4), 0, dp2px(4), dp2px(10))
                })

                val grid = CommonDialogHelper.addTwoColumnGrid(this, content)
                for (spec in specs) {
                    val placed = WidgetRegistry.isPlaced(this, spec)
                    val missing = WidgetRegistry.missingCapabilities(spec, caps)
                    val subtitle = buildString {
                        append(if (placed) "已放置" else "未放置")
                        if (missing.isNotEmpty()) append(" · ${missing.size} 项无数据")
                    }
                    grid.addView(
                        CommonDialogHelper.asGridCell(
                            this,
                            CommonDialogHelper.buildOptionView(
                                context = this,
                                label = spec.displayName,
                                subtitle = subtitle,
                                // 这里不能用 selected 表示「已放置」：buildOptionView 把 selected 当成
                                // 「当前项即结果」，会连带把它设为不可点击（clickable = onClick != null && !selected），
                                // 结果是已放置的形态点不动，只有未放置的能点开 —— 表现为「永远打开另一个形态的设置」
                                selected = false,
                                onClick = {
                                    dialog.dismiss()
                                    showFieldsDialog(spec)
                                }
                            )
                        )
                    )
                }
            }
        )
    }


    /**
     * 第二步：编辑某个形态的显示项。
     *
     * 弹窗实现在 [WidgetFieldsDialog] 里，与桌面「重新配置」入口共用同一份代码 ——
     * 两个入口各写一份是最容易长期跑偏的地方。
     *
     * @param appWidgetId 非 null 时写实例层（作用域选中了具体组件），null 写类型层、
     *                    对该形态所有实例生效
     */
    private fun showFieldsDialog(spec: WidgetSpec, appWidgetId: Int? = null) {
        WidgetFieldsDialog.show(
            activity = this,
            spec = spec,
            appWidgetId = appWidgetId,
            onSaved = {
                updateDisplayInfoSubtitle()
                renderWidgetsNow()
            }
        )
    }

    // ==================== 3. 后台刷新频率（弹窗选择） ====================
    private fun initWidgetIntervalItem() {
        widgetIntervalMinutes = SPUtil.getRefreshInterval(this)

        try {
            findInItem<ImageView>(R.id.item_widget_interval, R.id.common_item_icon)?.setImageResource(R.drawable.ic_clock_bolt)
            findInItem<TextView>(R.id.item_widget_interval, R.id.common_item_title)?.text = "后台刷新频率"
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "initWidgetIntervalItem: setting icon/title failed: ${e.message}") }
        updateWidgetIntervalSubtitle()

        findViewById<View>(R.id.item_widget_interval).setOnClickListener {
            showWidgetIntervalDialog()
        }
    }

    private fun updateWidgetIntervalSubtitle() {
        val label = if (widgetIntervalMinutes <= 0) "关闭" else "${widgetIntervalMinutes} 分钟"
        try {
            findInItem<TextView>(R.id.item_widget_interval, R.id.common_item_subtitle)?.text = label
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "updateWidgetIntervalSubtitle: setting subtitle failed: ${e.message}") }
    }

    private fun showWidgetIntervalDialog() {
        activeWidgetIntervalDialog?.takeIf { it.isShowing }?.dismiss()
        activeWidgetIntervalDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "后台刷新频率"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_clock_bolt)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val valueLabel = TextView(this).apply {
            text = "${widgetIntervalMinutes} 分钟"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.textPrimary(this@WidgetSettingsActivity))
            gravity = android.view.Gravity.CENTER
        }

        val slider = ThemeSlider(this).apply {
            minValue = 1f
            maxValue = 120f
            stepSize = 1f
            currentValue = if (widgetIntervalMinutes > 0) widgetIntervalMinutes.toFloat().coerceIn(1f, 120f) else 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44))
        }
        ThemedSliderUtil.setupSliderTickMarks(slider, 30f) { "${it}分" }

        // 实时更新数值（拖动时不受抑制）
        slider.onValueChanging = { value ->
            valueLabel.text = "${value.toInt()} 分钟"
        }

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.addView(valueLabel)
        content.addView(slider)

        // 常用值预设（自动跟随滑块高亮）
        val (presetRow, updatePresets) = CommonDialogHelper.createPresetRow(
            context = this,
            values = listOf(15, 30, 60, 120),
            formatLabel = { "${it}分" },
            currentValue = widgetIntervalMinutes,
            onSelect = { slider.currentValue = it.toFloat() }
        )
        content.addView(presetRow)
        slider.onValueChange = { value ->
            widgetIntervalMinutes = value.toInt()
            valueLabel.text = "${value.toInt()} 分钟"
            updatePresets(value.toInt())
            SPUtil.setRefreshInterval(this@WidgetSettingsActivity, value.toInt())
            updateWidgetIntervalSubtitle()
        }

        // 自定义输入面板
        val customPanel = CommonDialogHelper.createInputPanel(
            context = this,
            hint = "输入 1-1440 分钟",
            validate = { text ->
                val mins = text.toIntOrNull()
                when {
                    mins == null -> "请输入有效数字"
                    mins !in 1..1440 -> "请输入 1-1440 之间的分钟数"
                    else -> null
                }
            },
            onConfirm = { text ->
                widgetIntervalMinutes = text.toInt()
                SPUtil.setRefreshInterval(this@WidgetSettingsActivity, text.toInt())
                updateWidgetIntervalSubtitle()
                updateWidgetWorker()
                dialog.dismiss()
                ToastUtil.showDropToast(this@WidgetSettingsActivity, ToastStyle.SUCCESS, "自定义间隔已设为 ${text}分钟")
            }
        )
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // 公共弹窗按钮
        val btnPrimary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确定"
        btnPrimary.setOnClickListener {
            updateWidgetWorker()
            dialog.dismiss()
        }

        val btnSecondary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = android.view.View.VISIBLE
        btnSecondary.text = "自定义"
        btnSecondary.setOnClickListener {
            val showing = customPanel.visibility == android.view.View.VISIBLE
            CommonDialogHelper.animatePanelVisibility(customPanel, !showing) {
                if (!showing) {
                    val et = customPanel.findViewWithTag<android.widget.EditText>("custom_input_field")
                    et?.requestFocus()
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeWidgetIntervalDialog = dialog
        dialog.show()
    }

    // ==================== 3.5 三击切换设备配置档 ====================
    //
    // RemoteViews 没有连击手势，实现方式是在 BaseWifiWidget.onReceive 里累计相邻
    // 点击广播的连击数（见 SPUtil.bumpWidgetTapCount）。所以这里只管配置。
    // 用三击而不是双击：双击已经让给「轮播大字」，三击也更难误触到切设备这种重动作。

    private fun initWidgetTripleTapItem() {
        val itemView = findViewById<View>(R.id.item_widget_triple_tap)
        // 只有一个配置档时「循环切换」无从循环，直接锁住开关：
        // 允许打开只会得到一个永远不生效的开关，反而看起来像坏了
        val canCycle = DeviceProfiles.ids(this).size >= 2
        if (!canCycle && SPUtil.getWidgetTripleTapSwitch(this)) {
            SPUtil.setWidgetTripleTapSwitch(this, false)
        }

        CommonSettingsItemHelper.setupSwitchItem(
            itemView = itemView,
            iconRes = R.drawable.ic_sync,
            label = "三击切换设备",
            subtitle = widgetCycleSubtitle(),
            initialChecked = canCycle && SPUtil.getWidgetTripleTapSwitch(this)
        ) { checked ->
            SPUtil.setWidgetTripleTapSwitch(this, checked)
            updateWidgetTripleTapSubtitle()
        }

        if (canCycle) {
            itemView.alpha = 1f
            // 开关只占右侧滑块，整行仍可点开「参与循环的配置档」多选
            itemView.setOnClickListener { showWidgetTripleTapDialog() }
        } else {
            itemView.alpha = 0.45f
            val hint = View.OnClickListener {
                ToastUtil.showDropToast(this, ToastStyle.WARNING, "至少需要 2 个设备配置档")
            }
            itemView.setOnClickListener(hint)
            // 覆盖 setupSwitch 装上的监听，否则滑块还能拨动
            itemView.findViewById<View>(R.id.common_switch_track)?.setOnClickListener(hint)
        }
    }

    private fun widgetCycleSubtitle(): String {
        val all = DeviceProfiles.ids(this)
        if (all.size < 2) return "仅有 1 个配置档，暂不可用"
        val picked = SPUtil.getWidgetCycleProfiles(this).filter { it in all }
        return if (picked.size >= 2) "循环指定的 ${picked.size} 个配置档"
            else "循环全部 ${all.size} 个配置档"
    }

    private fun updateWidgetTripleTapSubtitle() {
        try {
            findInItem<TextView>(R.id.item_widget_triple_tap, R.id.common_switch_subtitle)
                ?.text = widgetCycleSubtitle()
        } catch (e: Exception) {
            DebugLogger.w("WidgetSettingsActivity", "updateWidgetTripleTapSubtitle failed: ${e.message}")
        }
    }

    private fun showWidgetTripleTapDialog() {
        val ids = DeviceProfiles.ids(this)
        val stored = SPUtil.getWidgetCycleProfiles(this).filter { it in ids }
        CommonDialogHelper.showSwitchGridDialog(
            context = this,
            title = "参与循环的配置档",
            iconRes = R.drawable.ic_sync,
            items = ids.map { it to DeviceProfiles.displayName(this, it) },
            // 存空集合的语义是「全部档」，这时开关必须全亮 —— 否则界面显示全灭、
            // 行为却是全部参与，看着就是开关状态错乱
            checkedOf = { id -> stored.size < 2 || id in stored },
            onConfirm = { picked ->
                // 勾不足 2 个（含勾满全部）都回落成空集合＝全部档，
                // 新建的档不用回来改设置就能自动参与
                val next = if (picked.size >= 2 && picked.size < ids.size) picked else emptySet()
                SPUtil.setWidgetCycleProfiles(this, next)
                updateWidgetTripleTapSubtitle()
            }
        )
    }

    // ==================== 4. 自定义背景图（弹窗选择） ====================

    private fun initWidgetBgImageItem() {
        widgetBgImageUri = apBgUri()
        widgetBgImageEnabled = apBgEnabled()

        try {
            findInItem<ImageView>(R.id.item_widget_bg_image, R.id.common_item_icon)?.setImageResource(R.drawable.ic_photo)
            findInItem<TextView>(R.id.item_widget_bg_image, R.id.common_item_title)?.text = "小组件背景"
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "initWidgetBgImageItem: setting icon/title failed: ${e.message}") }
        updateWidgetBgImageSubtitle()

        findViewById<View>(R.id.item_widget_bg_image).setOnClickListener {
            showWidgetBgImageDialog()
        }
    }

    private fun updateWidgetBgImageSubtitle() {
        val label = if (widgetBgImageUri.isNotBlank()) {
            if (widgetBgImageEnabled) "已开启" else "已关闭"
        } else {
            "未设置"
        }
        try {
            findInItem<TextView>(R.id.item_widget_bg_image, R.id.common_item_subtitle)?.text = label
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "updateWidgetBgImageSubtitle: setting subtitle failed: ${e.message}") }
    }

    /**
     * 取景框的目标尺寸（px），只用来定宽高比。
     *
     * 优先用系统给的实测尺寸：桌面一格不是 70dp 方格，按标称公式算 2×1 得 2.75:1，
     * 真实值接近 1.8:1，框出来的图贴上去会被 fitXY 拉变形。
     * 全局作用域（没有具体实例）只能退回标称比例。
     */
    private fun cropTargetPx(kind: String, id: Int?): Pair<Int, Int> {
        val aspect = WidgetRegistry.measuredAspect(this, id)
            ?: WidgetRegistry.byKind(kind)?.nominalAspect
            ?: 2f
        // 放大到长边 1000 再取整，避免小数字下的整除误差影响比例
        return if (aspect >= 1f) {
            1000 to (1000 / aspect).toInt().coerceAtLeast(1)
        } else {
            (1000 * aspect).toInt().coerceAtLeast(1) to 1000
        }
    }

    /**
     * 需要单独取景的形态列表：(kind, appWidgetId, 显示名)。
     *
     * 作用域是某个实例时只有它自己；是全局默认时列出桌面上已放置的每个形态 ——
     * 一张图要同时贴 4×2 和 2×2，比例天差地别，只能各框一次。
     * 没放到桌面上的形态不列：改了也看不见。
     */
    private fun cropTargets(): List<Triple<String, Int?, String>> {
        val kind = apScope.kind
        if (kind != null) {
            return listOf(Triple(kind, apScope.appWidgetId, scopeName()))
        }
        return WidgetRegistry.enabled
            .filter { WidgetRegistry.isPlaced(this, it) }
            .map { Triple(it.kind, null, it.displayName) }
    }

    /** 该形态当前生效的取景（优先未提交的待选值），null = 自动居中 */
    private fun cropRectOf(kind: String, id: Int?): FloatArray? {
        pendingCrops[cropKey(kind, id)]?.let { return it }
        val stored = WidgetPrefs.getString(this, kind, WidgetPrefs.BG_CROP, "", id)
        val rect = BgCrop.decode(stored, pendingBgUri) ?: return null
        return floatArrayOf(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun cropKey(kind: String, id: Int?): String = "$kind|${id ?: -1}"

    /**
     * 处理用户选择的图片：拷贝到内部存储（解决 Widget 跨进程 content:// 权限问题）。
     *
     * 不再在这里强制裁切 —— 取景是按形态各存一个矩形的，选图阶段还不知道用户
     * 想给哪个形态怎么框；没框的形态由渲染层按比例居中裁兜底，不会变形。
     */
    private fun handlePickedWidgetBgImage(uri: Uri) {
        // 获取持久化 URI 权限（兜底，确保拷贝时能读取流）
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}

        lifecycleScope.launch {
            val internalPath = withContext(Dispatchers.IO) {
                SPUtil.saveWidgetBgImageToInternal(this@WidgetSettingsActivity, uri)
            }
            if (internalPath == null) {
                ToastUtil.showDropToast(this@WidgetSettingsActivity, ToastStyle.WARNING, "图片拷贝失败，请重试")
                return@launch
            }
            pendingBgUri = internalPath
            pendingBgEnabled = true
            // 换了图，之前那些针对旧图的待选取景全部作废
            pendingCrops.clear()
            // 拷贝完成才刷新：预览和「各形态取景」都依赖 pendingBgUri
            if (activeBgImageDialog?.isShowing == true) {
                rebuildBgDialogContent()
            } else {
                showWidgetBgImageDialog()
            }

            // 只有一个形态要框时直接进取景页（作用域是某个实例的情形），
            // 省掉一次点击、也保留改造前「选完图就进裁切」的手感；
            // 多形态时不替用户决定先框哪个，未框的按居中裁兜底
            val targets = cropTargets()
            if (targets.size == 1) {
                val (kind, id, _) = targets[0]
                openCropForTarget(kind, id)
            }
        }
    }

    /**
     * 点选「最近使用」里的历史背景。
     *
     * 不需要重裁：渲染时按形态取景，历史图是给哪个形态裁过的已经不影响结果。
     */
    private fun onHistoryBgPicked(histUri: String) {
        pendingBgUri = histUri
        pendingBgEnabled = true
        pendingCrops.clear()
        rebuildBgDialogContent()
    }

    /** 打开取景页。矩形只回传不落文件，原图保持不变，所以可以反复重框 */
    private fun openCropForTarget(kind: String, id: Int?) {
        val source = pendingBgUri
        if (source.isBlank()) return
        val (w, h) = cropTargetPx(kind, id)
        pendingCropTarget = cropKey(kind, id)
        val intent = Intent(this, ImageCropActivity::class.java).apply {
            data = if (source.startsWith("/")) Uri.fromFile(java.io.File(source)) else Uri.parse(source)
            putExtra("targetW", w)
            putExtra("targetH", h)
            putExtra("returnRect", true)
            cropRectOf(kind, id)?.let { putExtra("initRect", it) }
        }
        cropLauncher.launch(intent)
    }

    /**
     * 把待选取景写进对应形态/实例的 SP。
     *
     * 必须在图确定之后写：矩形是和源图路径一起存的，先写会把矩形绑到一个
     * 可能被取消掉的路径上。图被清空时写空串，让旧矩形不再生效。
     */
    private fun commitPendingCrops() {
        if (pendingCrops.isEmpty()) return
        val uri = pendingBgUri
        for ((key, r) in pendingCrops) {
            val kind = key.substringBefore('|')
            val id = key.substringAfter('|').toIntOrNull()?.takeIf { it >= 0 }
            val value = if (uri.isBlank()) {
                ""
            } else {
                BgCrop.encode(uri, BgCrop.Rect(r[0], r[1], r[2], r[3]))
            }
            WidgetPrefs.setString(this, kind, WidgetPrefs.BG_CROP, value, id)
        }
        pendingCrops.clear()
    }

    /** 按归一化矩形裁预览图，让弹窗里看到的就是桌面上会显示的那一块 */
    private fun cropPreviewBitmap(src: android.graphics.Bitmap, r: FloatArray): android.graphics.Bitmap {
        if (src.width <= 0 || src.height <= 0) return src
        val l = (r[0] * src.width).toInt().coerceIn(0, src.width - 1)
        val t = (r[1] * src.height).toInt().coerceIn(0, src.height - 1)
        val w = ((r[2] - r[0]) * src.width).toInt().coerceIn(1, src.width - l)
        val h = ((r[3] - r[1]) * src.height).toInt().coerceIn(1, src.height - t)
        return try {
            android.graphics.Bitmap.createBitmap(src, l, t, w, h)
        } catch (e: Exception) {
            DebugLogger.w("WidgetSettingsActivity", "预览裁切失败，回落整图: ${e.message}")
            src
        }
    }



    private fun showWidgetBgImageDialog() {
        activeBgImageDialog?.takeIf { it.isShowing }?.dismiss()
        activeBgImageDialog = null

        // 初始化待选状态（仅在无待选图片时使用已提交的值）
        if (pendingBgUri.isBlank() && widgetBgImageUri.isNotBlank()) {
            pendingBgUri = widgetBgImageUri
        }
        if (pendingBgUri == widgetBgImageUri) {
            pendingBgEnabled = widgetBgImageEnabled
        }

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "小组件背景 · ${scopeName()}"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_photo)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        bgDialogContent = content

        // 构建初始内容
        rebuildBgDialogContent()

        // ── 确认按钮：提交待选状态到 SP 并生效 ──
        val btnPrimary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确认"
        btnPrimary.setOnClickListener {
            val uriChanged = pendingBgUri != widgetBgImageUri
            val enabledChanged = pendingBgEnabled != widgetBgImageEnabled
            val cropsChanged = pendingCrops.isNotEmpty()
            if (uriChanged || enabledChanged || cropsChanged) {
                if (pendingBgUri.isNotBlank()) {
                    apSetBgUri(pendingBgUri)
                    // 历史按作用域独立：混在一条列表里，点进去大半是给别的比例裁过的图。
                    // 每次选图都拷成带时间戳的新文件，所以不同作用域天然指向不同文件
                    SPUtil.addWidgetBgHistory(this, pendingBgUri, apScope.kind, apScope.appWidgetId)
                }
                if (pendingBgUri.isBlank()) {
                    if (apScope.kind != null) {
                        // 实例作用域下只清自己的键、不删文件：那张图可能还被全局或别的形态引用，
                        // 删掉会让别的组件背景突然变空。
                        // 这里判断的是「有没有选中具体组件」而不是「独立开关开没开」——
                        // 用开关判断会在未开启时把全局背景清掉，等于清错了对象
                        apSetBgUri("")
                    } else {
                        SPUtil.clearWidgetBgImage(this)
                    }
                }
                commitPendingCrops()
                apSetBgEnabled(pendingBgEnabled && pendingBgUri.isNotBlank())
                widgetBgImageUri = pendingBgUri
                widgetBgImageEnabled = pendingBgEnabled && pendingBgUri.isNotBlank()

                // ── 背景 URI 变化时清除动态取色缓存，确保下次渲染重新提取颜色 ──
                if (uriChanged) {
                    ThemeColors.invalidateWallpaperColorCache()
                }

                // ── 动态配色依赖背景存在，背景被清除/关闭时自动关闭动态配色 ──
                val bgEffectivelyAvailable = pendingBgUri.isNotBlank() && pendingBgEnabled
                if (!bgEffectivelyAvailable && isWidgetDynamicActive()) {
                    apSetDynamic(false)
                }
                // ── 刷新动态配色锁定状态 ──
                applyDynamicColorLockState()

                updateWidgetBgImageSubtitle()
                renderWidgetsNow()
                if (pendingBgUri.isBlank()) {
                    ToastUtil.showDropToast(this, ToastStyle.INFO, "小组件背景已清除")
                } else if (!uriChanged && enabledChanged) {
                    // 仅开关变化
                    val msg = if (widgetBgImageEnabled) "自定义背景已开启" else "自定义背景已关闭"
                    ToastUtil.showDropToast(this, ToastStyle.SUCCESS, msg)
                } else {
                    ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "小组件背景已更新")
                }
            }
            dialog.dismiss()
        }

        // ── 取消按钮：放弃变更 ──
        val btnSecondary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = android.view.View.VISIBLE
        btnSecondary.text = "取消"
        btnSecondary.setOnClickListener {
            // 清理取消产生的临时文件（不删除已提交或在历史中的文件）
            val cancelledUri = pendingBgUri
            if (cancelledUri.isNotBlank() && cancelledUri != widgetBgImageUri
                && !cancelledUri.startsWith("content://")
                && cancelledUri !in SPUtil.getWidgetBgHistory(this, apScope.kind, apScope.appWidgetId)) {
                try { java.io.File(cancelledUri).delete() } catch (_: Exception) {}
            }
            // 恢复为已提交状态
            pendingBgUri = widgetBgImageUri
            pendingBgEnabled = widgetBgImageEnabled
            pendingCrops.clear()
            dialog.dismiss()
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeBgImageDialog = dialog
        dialog.show()
    }

    /** 原地重建弹窗内容区域（预览、历史、选项），不销毁弹窗窗口 */
    private fun rebuildBgDialogContent() {
        val content = bgDialogContent ?: return
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val textSecondary = ThemeColors.textSecondary(this)
        val chipRadius = 12f * resources.displayMetrics.density
        val selectedBg = makeSelectedBg(accent, chipRadius)
        val unselectedBg = makeUnselectedBg(chipRadius)

        content.removeAllViews()

        // ── 预览区域（有待选图片时显示）──
        if (pendingBgUri.isNotBlank()) {
            val previewContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp2px(12) }
            }

            // 预览按「第一个取景目标」的比例显示：作用域是实例时就是它自己，全局默认时
            // 取已放置形态里的第一个。固定 2:1 会让方块形态看着被压扁，跟桌面对不上
            val previewTarget = cropTargets().firstOrNull()
            val previewKind = previewTarget?.first ?: WidgetRegistry.KIND_4X2
            val previewRect = previewTarget?.let { cropRectOf(it.first, it.second) }

            val preview = ImageView(this).apply {
                val (targetW, targetH) = cropTargetPx(previewKind, previewTarget?.second)
                val previewW = dp2px(200)
                layoutParams = LinearLayout.LayoutParams(
                    previewW,
                    (previewW * targetH.toFloat() / targetW.toFloat()).toInt()
                ).apply {
                    bottomMargin = dp2px(8)
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(ThemeColors.cardBg(this@WidgetSettingsActivity))
                    setCornerRadius(8f * resources.displayMetrics.density)
                    setStroke(
                        (1f * resources.displayMetrics.density).toInt(),
                        ThemeColors.textSecondary(this@WidgetSettingsActivity)
                    )
                }
                try {
                    // 异步加载预览图，避免阻塞主线程
                    val uriStr = pendingBgUri
                    val previewView = this@apply
                    this@WidgetSettingsActivity.lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            if (uriStr.startsWith("/") && java.io.File(uriStr).exists()) {
                                java.io.FileInputStream(java.io.File(uriStr)).use { stream ->
                                    BitmapFactory.decodeStream(stream, null, opts)
                                }
                            } else {
                                val uri = Uri.parse(uriStr)
                                this@WidgetSettingsActivity.contentResolver.openInputStream(uri)?.use { stream ->
                                    BitmapFactory.decodeStream(stream, null, opts)
                                }
                            }
                        }
                        if (bmp != null) {
                            // 预览要显示「桌面上真正会看到的那一块」，否则用户框完看不出变化
                            previewView.setImageBitmap(
                                previewRect?.let { cropPreviewBitmap(bmp, it) } ?: bmp
                            )
                        }
                    }
                } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "rebuildBgDialogContent: loading preview failed: ${e.message}") }
                clipToOutline = true
            }
            previewContainer.addView(preview)

            // ── 启用开关（仅修改待选状态，不提交 SP）──
            val switchRow = layoutInflater.inflate(R.layout.layout_common_switch, previewContainer, false)
            switchRow.findViewById<TextView>(R.id.common_switch_label).apply {
                text = "启用自定义背景"
                textSize = 14f
                setTextColor(ThemeColors.textPrimary(this@WidgetSettingsActivity))
                alpha = 1f
            }
            com.ufi_axis_widget.util.ThemeUtil.setupSwitch(switchRow, pendingBgEnabled) { isChecked ->
                pendingBgEnabled = isChecked
                preview.alpha = if (isChecked) 1f else 0.35f
            }
            preview.alpha = if (pendingBgEnabled) 1f else 0.35f
            previewContainer.addView(switchRow)
            content.addView(previewContainer)

            // ── 各形态取景：同一张图在 4×2 与 2×2 上的可用区域完全不同，只能各框一次 ──
            for ((kind, id, label) in cropTargets()) {
                val framed = cropRectOf(kind, id) != null
                content.addView(
                    buildDialogOptionView(
                        "取景 · $label · ${if (framed) "已框选" else "自动居中"}",
                        textPrimary, selectedBg, unselectedBg
                    ) { openCropForTarget(kind, id) }
                )
            }
        }

        // ── 选项：从相册选择图片 ──
        content.addView(buildDialogOptionView("从相册选择图片", textPrimary,
            selectedBg, unselectedBg) {
            pickImageLauncher.launch("image/*")
        })

        // ── 历史背景（最多3条缩略图）──
        val history = SPUtil.getWidgetBgHistory(this, apScope.kind, apScope.appWidgetId)
        if (history.isNotEmpty()) {
            val historySection = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp2px(4); bottomMargin = dp2px(4) }
            }

            val historyLabel = TextView(this).apply {
                text = "最近使用"
                textSize = 12f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp2px(8) }
            }
            historySection.addView(historyLabel)

            val thumbRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }

            history.forEachIndexed { _, histUri ->
                if (histUri.isBlank()) return@forEachIndexed
                if (histUri.startsWith("/") && !java.io.File(histUri).exists()) return@forEachIndexed

                val isSelected = histUri == pendingBgUri
                val thumbContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp2px(3), 0, dp2px(3), 0)
                    }
                    isClickable = true
                    isFocusable = true
                    foreground = android.util.TypedValue().let { tv ->
                        val typedValue = android.util.TypedValue()
                        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                        resources.getDrawable(typedValue.resourceId, theme)
                    }
                    if (isSelected) {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setStroke((2f * resources.displayMetrics.density).toInt(), accent)
                            setCornerRadius(8f * resources.displayMetrics.density)
                        }
                        setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
                    }
                    // 原地更新：只重建内容区域，不重建弹窗
                    setOnClickListener { onHistoryBgPicked(histUri) }
                }

                val thumb = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp2px(56), dp2px(36))
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(ThemeColors.cardBg(this@WidgetSettingsActivity))
                        setCornerRadius(6f * resources.displayMetrics.density)
                    }
                    try {
                        // 异步加载历史缩略图，避免多张图串行解码阻塞主线程
                        val thumbUri = histUri
                        val thumbView = this@apply
                        this@WidgetSettingsActivity.lifecycleScope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 6 }
                                if (thumbUri.startsWith("/") && java.io.File(thumbUri).exists()) {
                                    java.io.FileInputStream(java.io.File(thumbUri)).use { stream ->
                                        BitmapFactory.decodeStream(stream, null, opts)
                                    }
                                } else {
                                    val uri = Uri.parse(thumbUri)
                                    this@WidgetSettingsActivity.contentResolver.openInputStream(uri)?.use { stream ->
                                        BitmapFactory.decodeStream(stream, null, opts)
                                    }
                                }
                            }
                            if (bmp != null) thumbView.setImageBitmap(bmp)
                        }
                    } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "rebuildBgDialogContent: loading history thumb failed: ${e.message}") }
                    clipToOutline = true
                }
                thumbContainer.addView(thumb)

                if (isSelected) {
                    val activeDot = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp2px(6), dp2px(6)).apply {
                            topMargin = dp2px(4)
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(accent)
                        }
                    }
                    thumbContainer.addView(activeDot)
                }

                thumbRow.addView(thumbContainer)
            }

            historySection.addView(thumbRow)
            content.addView(historySection)
        }

        // ── 清除背景（仅当有待选背景时显示）──
        if (pendingBgUri.isNotBlank()) {
            content.addView(buildDialogOptionView("清除背景", textPrimary,
                selectedBg, unselectedBg) {
                pendingBgUri = ""
                pendingBgEnabled = false
                rebuildBgDialogContent()
            })
        }

        // 内容高度变了要重新自适应：窗口高度是 show() 前一次性测出来的，内容超过上限时
        // 会被钉成固定高度 + ScrollView 撑满。清除背景后预览、取景行、历史一起消失，
        // 窗口还停在原高度，表现就是下方一大片空白
        activeBgImageDialog?.let { dlg ->
            content.post {
                if (dlg.isShowing) PopupViewUtil.autoAdjustDialogHeight(this, dlg)
            }
        }
    }

    // ==================== 5. 背景透明度（弹窗选择） ====================
    private fun initWidgetBgOpacityItem() {
        widgetBgOpacity = apOpacity()

        try {
            findInItem<ImageView>(R.id.item_widget_bg_opacity, R.id.common_item_icon)?.setImageResource(R.drawable.ic_opacity)
            findInItem<TextView>(R.id.item_widget_bg_opacity, R.id.common_item_title)?.text = "背景透明度"
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "initWidgetBgOpacityItem: setting icon/title failed: ${e.message}") }
        updateWidgetBgOpacitySubtitle()

        findViewById<View>(R.id.item_widget_bg_opacity).setOnClickListener {
            showWidgetBgOpacityDialog()
        }
    }

    private fun updateWidgetBgOpacitySubtitle() {
        val label = "${widgetBgOpacity}%"
        try {
            findInItem<TextView>(R.id.item_widget_bg_opacity, R.id.common_item_subtitle)?.text = label
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "updateWidgetBgOpacitySubtitle: setting subtitle failed: ${e.message}") }
    }

    private fun showWidgetBgOpacityDialog() {
        activeBgOpacityDialog?.takeIf { it.isShowing }?.dismiss()
        activeBgOpacityDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "背景透明度 · ${scopeName()}"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_opacity)

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val valueLabel = TextView(this).apply {
            text = "$widgetBgOpacity%"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.textPrimary(this@WidgetSettingsActivity))
            gravity = android.view.Gravity.CENTER
        }

        val defVal = if (widgetBgOpacity in 0..100) widgetBgOpacity.toFloat() else 100f
        val slider = ThemeSlider(this).apply {
            minValue = 0f
            maxValue = 100f
            stepSize = 1f
            currentValue = defVal
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44))
            onValueChange = { value ->
                widgetBgOpacity = value.toInt()
                valueLabel.text = "${value.toInt()}%"
                apSetOpacity(value.toInt())
                updateWidgetBgOpacitySubtitle()
                debouncedRenderWidgets()
            }
        }
        ThemedSliderUtil.setupSliderTickMarks(slider, 20f) { "${it}%" }

        // 实时更新数值（拖动时不受抑制）
        slider.onValueChanging = { value ->
            valueLabel.text = "${value.toInt()}%"
        }

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.addView(valueLabel)
        content.addView(slider)

        // 常用值预设（自动跟随滑块高亮）
        val (presetRow, updatePresets) = CommonDialogHelper.createPresetRow(
            context = this,
            values = listOf(100, 80, 60, 40, 20),
            formatLabel = { "${it}%" },
            currentValue = widgetBgOpacity,
            onSelect = { slider.currentValue = it.toFloat() }
        )
        content.addView(presetRow)
        slider.onValueChange = { value ->
            widgetBgOpacity = value.toInt()
            valueLabel.text = "${value.toInt()}%"
            updatePresets(value.toInt())
            apSetOpacity(value.toInt())
            updateWidgetBgOpacitySubtitle()
            debouncedRenderWidgets()
        }

        // 自定义输入面板
        val customPanel = CommonDialogHelper.createInputPanel(
            context = this,
            hint = "输入 0-100",
            validate = { text ->
                val v = text.toIntOrNull()
                when {
                    v == null -> "请输入有效数字"
                    v !in 0..100 -> "请输入 0-100 之间的数字"
                    else -> null
                }
            },
            onConfirm = { text ->
                widgetBgOpacity = text.toInt()
                apSetOpacity(text.toInt())
                updateWidgetBgOpacitySubtitle()
                renderWidgetsNow()
                dialog.dismiss()
            }
        )
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // 公共弹窗按钮
        val btnPrimary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = "确定"
        btnPrimary.setOnClickListener { dialog.dismiss() }

        val btnSecondary = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary)
        btnSecondary.visibility = android.view.View.VISIBLE
        btnSecondary.text = "自定义"
        btnSecondary.setOnClickListener {
            val showing = customPanel.visibility == android.view.View.VISIBLE
            CommonDialogHelper.animatePanelVisibility(customPanel, !showing) {
                if (!showing) {
                    val et = customPanel.findViewWithTag<android.widget.EditText>("custom_input_field")
                    et?.requestFocus()
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeBgOpacityDialog = dialog
        dialog.show()
    }

    // ==================== 6. 兼容性设置（点击弹窗） ====================
    private fun initWidgetCompatibilityItem() {
        CommonSettingsItemHelper.setupSettingItem(
            itemView = findViewById(R.id.item_widget_compatibility),
            iconRes = R.drawable.ic_rounded_corners,
            title = "兼容性设置",
            subtitle = "圆角裁剪、隐藏名称等",
            onClick = ::showCompatibilityDialog
        )
    }

    /**
     * 当前作用域下「隐藏名称」是否生效。
     *
     * 以 PackageManager 的组件状态为准而不是另存一个 SP 标志：组件 enabled 才是这个功能
     * 真正的开关，用户从系统里手动改过、或某个形态切换失败时，只有读组件状态才不会骗人。
     */
    private fun hideLabelActive(): Boolean = scopedSpec()
        ?.let { WidgetLabelToggle.isShadowActive(this, it) }
        ?: WidgetLabelToggle.isShadowActiveForAll(this)

    private fun updateCompatibilitySubtitle() {
        val parts = mutableListOf<String>()
        if (apClip()) parts.add("圆角")
        if (hideLabelActive()) parts.add("隐藏名称")
        val subtitle = if (parts.isEmpty()) "圆角裁剪、隐藏名称等" else parts.joinToString("、")
        try {
            findViewById<TextView>(R.id.item_widget_compatibility)?.findViewById<TextView>(R.id.common_item_subtitle)?.text = subtitle
        } catch (e: Exception) { DebugLogger.w("WidgetSettingsActivity", "updateCompatibilitySubtitle: setting subtitle failed: ${e.message}") }
    }

    private fun showCompatibilityDialog() {
        val dialog = CommonDialogHelper.createAnimatedDialog(this)
        dialog.setContentView(R.layout.layout_common_dialog)

        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_rounded_corners)
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "兼容性设置 · ${scopeName()}"

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)

        // 临时状态。两项都按作用域走：隐藏名称靠停用 provider receiver 实现，
        // 而停用会让桌面移除该 provider 名下的实例，所以绝不能顺手把别的尺寸一起切
        var tempClipToOutline = apClip()
        var tempHideLabel = hideLabelActive()

        // ── 圆角裁剪兜底 ──
        // 用 createSwitchRow 而不是 inflate layout_common_switch：后者带
        // android:textSize="13sp" 覆盖，和下面「隐藏小组件名称」的 15sp 标题不一致
        val clipRow = CommonSettingsItemHelper.createSwitchRow(
            context = this,
            label = "兼容性小组件圆角",
            initialChecked = tempClipToOutline,
            onToggle = { tempClipToOutline = it }
        )
        content.addView(clipRow)

        // ── 隐藏小组件名称（影子组件切换） ──
        // 只对声明了影子 receiver 的形态开放；作用域指到某个形态时只切它一个，
        // 副标题必须点明「会被移除」——这不是重新添加就能避免的副作用，而是方案的代价
        val labelSpec = scopedSpec()
        val labelTogglable = labelSpec?.shadowProvider != null ||
            (labelSpec == null && WidgetLabelToggle.togglableSpecs.isNotEmpty())
        if (labelTogglable) {
            val hideLabelRow = CommonSettingsItemHelper.createSwitchRow(
                context = this,
                label = "隐藏小组件名称",
                subtitle = if (labelSpec != null) {
                    "仅作用于${labelSpec.displayName}；桌面上已放置的该尺寸组件会被移除，需重新添加"
                } else {
                    "作用于全部尺寸；桌面上已放置的组件会被移除，需重新添加"
                },
                initialChecked = tempHideLabel,
                onToggle = { tempHideLabel = it }
            )
            content.addView(hideLabelRow)
        }

        // 对 content 内动态添加的视图递归着色
        CommonDialogHelper.applyThemeToViewTree(content, this)

        // 按钮区域
        val btnContainer = dialog.findViewById<LinearLayout>(R.id.common_dialog_button_container)
        btnContainer.visibility = View.VISIBLE

        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_primary).apply {
            text = "确定"
            setOnClickListener {
                val oldHideLabel = hideLabelActive()
                apSetClip(tempClipToOutline)

                // 标签状态变化时切换组件（影子组件方案）。
                // 作用域指到某个形态就只切它，全局作用域才整组切 —— 早期实现无论如何都
                // 遍历全部形态，于是「2×2 作用域下开隐藏名称」把 1×1/4×1/4×2 的实例一起清掉了
                if (labelTogglable && tempHideLabel != oldHideLabel) {
                    if (labelSpec != null) {
                        WidgetLabelToggle.apply(this@WidgetSettingsActivity, labelSpec, tempHideLabel)
                    } else {
                        WidgetLabelToggle.applyAll(this@WidgetSettingsActivity, tempHideLabel)
                    }
                }

                updateCompatibilitySubtitle()
                renderWidgetsNow()
                dialog.dismiss()
            }
        }

        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.common_dialog_btn_secondary).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        dialog.show()
    }

    // ==================== Worker 更新 ====================
    private fun updateWidgetWorker() {
        // 请求构造统一在 WifiWorker.buildPeriodicRequest（含指数退避）
        WifiWorker.schedulePeriodic(this, widgetIntervalMinutes, keepExisting = false)
    }

    // ==================== 工具方法 ====================

    /** 从 include 项中查找子 View */
    private fun <T : View> findInItem(itemId: Int, childId: Int): T? {
        return findViewById<View>(itemId)?.findViewById(childId)
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun makeSelectedBg(accent: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accent)
            this.cornerRadius = cornerRadius
        }
    }

    private fun makeUnselectedBg(cornerRadius: Float): GradientDrawable {
        val cardBg = ThemeColors.cardBg(this)
        val borderColor = if (ThemeColors.isDark(this))
            0x30FFFFFF.toInt() else 0x20000000
        val borderWidth = (1.5f * resources.displayMetrics.density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(cardBg)
            this.cornerRadius = cornerRadius
            setStroke(borderWidth, borderColor)
        }
    }

    /**
     * 创建统一风格的弹窗选项视图
     */
    private fun buildDialogOptionView(
        label: String,
        textPrimary: Int,
        selectedBg: GradientDrawable,
        unselectedBg: GradientDrawable,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp2px(14), 0, dp2px(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp2px(8) }
            background = unselectedBg
            setTextColor(textPrimary)
            isClickable = true
            isFocusable = true
            foreground = android.util.TypedValue().let { tv ->
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                resources.getDrawable(typedValue.resourceId, theme)
            }
            setOnClickListener {
                background = selectedBg
                setTextColor(0xFFFFFFFF.toInt())
                postDelayed({ onClick() }, 120)
            }
        }
    }
}
