package com.ufi_axis_widget

import android.app.Dialog
import android.app.UiModeManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ufi_axis_widget.util.AnimationUtil
import com.ufi_axis_widget.util.BackgroundUtil
import com.ufi_axis_widget.util.CommonDialogHelper
import com.ufi_axis_widget.util.CommonSettingsItemHelper
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.ThemeChangeNotifier
import com.ufi_axis_widget.util.ThemeColors
import com.ufi_axis_widget.util.ThemeUtil
import com.ufi_axis_widget.util.ThemedSliderUtil
import com.ufi_axis_widget.util.ToastUtil
import com.ufi_axis_widget.util.ToastStyle
import com.ufi_axis_widget.util.WifiGuard
import com.ufi_axis_widget.view.ThemeSlider
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.widget.BaseWifiWidget
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AppSettingsActivity : AppCompatActivity() {

    private var mainIntervalSeconds: Int = 5

    /** 图片选择器（从媒体库选一张图片作为背景） */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handlePickedImage(uri)
        }
    }

    /** 图片裁切启动器 */
    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val filePath = result.data?.getStringExtra("cropped_file_path")
            if (!filePath.isNullOrBlank()) {
                val uri = Uri.fromFile(java.io.File(filePath))
                applyBgImage(uri)
            }
        }
    }

    /**
     * 定位权限请求器。Android 10+ 读取当前 Wi-Fi 的 SSID 必须有 ACCESS_FINE_LOCATION，
     * 否则系统一律返回 `<unknown ssid>`，无法把当前 Wi-Fi 加进名单。
     *
     * 被拒绝时必须把开关回滚：否则功能开着却永远拿不到 SSID，名单也永远填不进去，
     * 用户会得到一个「开了但什么都没变、也没法配置」的死状态。
     */
    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showWifiLockDialog()
        } else {
            SPUtil.setWifiLockEnabled(this, false)
            updateWifiLockSubtitle()
            BaseWifiWidget.renderAllWidgets(this, force = true)
            ToastUtil.showDropToast(this, ToastStyle.WARNING,
                "未授予定位权限，读不到 Wi-Fi 名称，已自动关闭该功能")
        }
    }

    private fun handlePickedImage(uri: Uri) {
        // 获取持久化 URI 权限
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {}

        // 检测尺寸与设备匹配度
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val screenRatio = screenH.toFloat() / screenW.toFloat()

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                val imgW = options.outWidth
                val imgH = options.outHeight
                val imgRatio = imgH.toFloat() / imgW.toFloat()

                // 如果比例差异超过 2% 或者图片太小，建议裁切
                if (Math.abs(imgRatio - screenRatio) > 0.02f || imgW < screenW || imgH < screenH) {
                    val intent = Intent(this, ImageCropActivity::class.java).apply {
                        data = uri
                        putExtra("targetW", screenW)
                        putExtra("targetH", screenH)
                        putExtra("saveSubDir", "app_bg")
                        putExtra("saveFileName", "custom_bg.jpg")
                    }
                    cropLauncher.launch(intent)
                } else {
                    applyBgImage(uri)
                }
            }
        } catch (e: Exception) {
            DebugLogger.w("AppSettingsActivity", "启动裁剪失败，直接应用原图: ${e.message}")
            applyBgImage(uri) // 失败则直接应用
        }

    }

    private fun applyBgImage(uri: Uri) {
        BackgroundUtil.clearCache() // 强制清理，确保加载新图
        SPUtil.setBgImageUri(this, uri.toString())
        updateBgImageSubtitle()
        BackgroundUtil.applyWindowBackgroundAsync(this)
        ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "背景图片已更新")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. 设置内容
        setContentView(R.layout.activity_app_settings)

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        initDisplayModeItem()
        initThemeColorItem()
        initRefreshIntervalItem()
        initWifiLockItem()
        initPowerGuardItem()
        // initBgImageItem() // 暂时隐藏入口

        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
    }

    override fun onResume() {
        super.onResume()
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.APP_SETTINGS)
        updateDisplayModeSubtitle()
        updateThemeColorSubtitle()
        updateRefreshIntervalSubtitle()
        updateWifiLockSubtitle()
        // updateBgImageSubtitle() // 暂时隐藏入口
    }

    override fun onDestroy() {
        try { activeThemeDialog?.dismiss() } catch (_: Exception) {}
        try { activeColorDialog?.dismiss() } catch (_: Exception) {}
        try { activeIntervalDialog?.dismiss() } catch (_: Exception) {}
        try { activeWifiLockDialog?.dismiss() } catch (_: Exception) {}
        activeThemeDialog = null
        activeColorDialog = null
        activeIntervalDialog = null
        activeWifiLockDialog = null
        super.onDestroy()
    }

    // ==================== 显示模式（弹窗选择） ====================
    private var currentAppTheme: String = "system"
    private var activeThemeDialog: Dialog? = null

    private fun initDisplayModeItem() {
        currentAppTheme = SPUtil.getAppTheme(this)

        // 设置通用项的内容
        try {
            findInItem<ImageView>(R.id.item_display_mode, R.id.common_item_icon)?.setImageResource(getDisplayModeIcon())
            findInItem<TextView>(R.id.item_display_mode, R.id.common_item_title)?.text = "显示模式"
        } catch (e: Exception) { DebugLogger.w("AppSettingsActivity", "setting display mode item icon/title failed: ${e.message}") }
        updateDisplayModeSubtitle()

        findViewById<View>(R.id.item_display_mode).setOnClickListener {
            showDisplayModeDialog()
        }
    }

    /** 根据当前主题模式返回对应图标 */
    private fun getDisplayModeIcon(): Int = when (currentAppTheme) {
        "light" -> R.drawable.ic_sun
        "dark" -> R.drawable.ic_moon
        else -> R.drawable.ic_sun_moon
    }

    /** 更新设置项副标题为当前模式名称，并同步更新图标 */
    private fun updateDisplayModeSubtitle() {
        val modeName = when (currentAppTheme) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "跟随系统"
        }
        try {
            findInItem<TextView>(R.id.item_display_mode, R.id.common_item_subtitle)?.text = modeName
            findInItem<ImageView>(R.id.item_display_mode, R.id.common_item_icon)?.setImageResource(getDisplayModeIcon())
        } catch (e: Exception) { DebugLogger.w("AppSettingsActivity", "updating display mode subtitle/icon failed: ${e.message}") }
    }

    /** 显示模式选择弹窗 */
    private fun showDisplayModeDialog() {
        activeThemeDialog?.takeIf { it.isShowing }?.dismiss()
        activeThemeDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this) {
            activeThemeDialog = null
        }
        dialog.setContentView(R.layout.layout_common_dialog)
        // ...

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cornerRadius = 12f * resources.displayMetrics.density

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "显示模式"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_sun_moon)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val options = listOf(
            "system" to "跟随系统",
            "light" to "浅色",
            "dark" to "深色"
        )
        options.forEach { (key, label) ->
            content.addView(CommonDialogHelper.buildOptionView(
                context = this,
                label = label,
                selected = key == currentAppTheme,
                textPrimary = textPrimary,
                accent = accent,
                cornerRadius = cornerRadius
            ) {
                dialog.dismiss()
                applyThemeModeChange(key)
            })
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeThemeDialog = dialog
        dialog.show()
    }

    /** 应用显示模式切换：复用主题配色的原位圆形揭露动画 */
    private fun applyThemeModeChange(theme: String) {
        val oldStored = SPUtil.getAppTheme(this)
        if (oldStored == theme) return

        // 用 UiModeManager 读取真实系统深色模式（不受 AppCompat 覆盖影响）
        val uiModeMgr = getSystemService(UiModeManager::class.java)!!
        val isSystemDark = uiModeMgr.nightMode == UiModeManager.MODE_NIGHT_YES

        // 将 SP 存储值解析为当前有效暗色模式：
        //   "light" → 强制浅色  /  "dark" → 强制深色  /  其他(跟随系统) → 自动读取系统状态
        fun resolveIsDark(stored: String) = when (stored) {
            "light" -> false
            "dark" -> true
            else -> isSystemDark
        }

        val wasDark = resolveIsDark(oldStored)
        val willBeDark = resolveIsDark(theme)

        if (wasDark == willBeDark) {
            // 有效视觉模式不变（切换前后均为浅色或均为深色）→ 静默应用，跳过动画
            SPUtil.setAppTheme(this, theme)
            currentAppTheme = theme
            updateDisplayModeSubtitle()
            ThemeChangeNotifier.notifyThemeChanged(this)
            return
        }

        AnimationUtil.applyCircleRevealPulse(this) {
            SPUtil.setAppTheme(this, theme)
            currentAppTheme = theme

            BackgroundUtil.initActivity(this)
            ThemeUtil.applyToAppSettingsActivity(this)
            updateDisplayModeSubtitle()
            updateThemeColorSubtitle()
            updateRefreshIntervalSubtitle()
            updateBgImageSubtitle()
            // 异步渲染小组件，避免阻塞动画回调
            lifecycleScope.launch { withContext(Dispatchers.IO) { BaseWifiWidget.renderAllWidgets(this@AppSettingsActivity, force = true) } }
            ThemeChangeNotifier.notifyThemeChanged(this)
        }
    }

    /** 从 include 项中查找子 View（避免同 ID 冲突） */
    private fun <T : View> findInItem(itemId: Int, childId: Int): T? {
        return findViewById<View>(itemId)?.findViewById(childId)
    }

    // ==================== 主题配色（弹窗选择） ====================
    private var currentThemeIndex: Int = 0
    private var activeColorDialog: Dialog? = null

    private fun initThemeColorItem() {
        currentThemeIndex = SPUtil.getColorThemeIndex(this)
        try {
            findInItem<ImageView>(R.id.item_theme_color, R.id.common_item_icon)?.setImageResource(R.drawable.ic_palette)
            findInItem<TextView>(R.id.item_theme_color, R.id.common_item_title)?.text = "主题配色"
        } catch (e: Exception) { DebugLogger.w("AppSettingsActivity", "setting theme color item icon/title failed: ${e.message}") }
        updateThemeColorSubtitle()

        findViewById<View>(R.id.item_theme_color).setOnClickListener {
            showThemeColorDialog()
        }
    }

    private fun updateThemeColorSubtitle() {
        val palette = ThemeColors.getById(this, currentThemeIndex)
        try {
            findInItem<TextView>(R.id.item_theme_color, R.id.common_item_subtitle)?.text = palette.name
        } catch (e: Exception) { DebugLogger.w("AppSettingsActivity", "updating theme color subtitle failed: ${e.message}") }
    }

    private fun showThemeColorDialog() {
        activeColorDialog?.takeIf { it.isShowing }?.dismiss()
        activeColorDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this) {
            activeColorDialog = null
        }
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cardBg = ThemeColors.cardBg(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "主题配色"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_palette)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val chipRadius = 12f * resources.displayMetrics.density

        val selectedBg = CommonDialogHelper.createSelectedBg(accent, chipRadius)
        val unselectedBg = CommonDialogHelper.createUnselectedBg(this, chipRadius)

        // 核心优化：使用 GridLayout 实现双栏显示
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)

        // 5 个预设主题
        ThemeColors.ALL.forEach { palette ->
            grid.addView(buildColorOption(palette.id, palette.name, palette.accentLight,
                textPrimary, accent, cardBg, selectedBg, unselectedBg, content, dialog, isGrid = true))
        }

        // 自定义选项
        val customAccent = SPUtil.getCustomAccentLight(this)
        grid.addView(buildColorOption(-1, "自定义", customAccent,
            textPrimary, accent, cardBg, selectedBg, unselectedBg, content, dialog, isGrid = true))

        // 自定义颜色编辑面板（放在网格下方）
        val customPanel = createCustomColorPanel(dialog, content, textPrimary, accent, cardBg)
        content.addView(customPanel)

        // 如果当前选中自定义，显示编辑面板
        if (currentThemeIndex == -1) customPanel.visibility = View.VISIBLE

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeColorDialog = dialog
        dialog.show()
    }

    /** 构建单个颜色选项行（增加 isGrid 模式适配） */
    private fun buildColorOption(
        index: Int, name: String, dotColor: Int,
        textPrimary: Int, accent: Int, cardBg: Int,
        selectedBg: GradientDrawable, unselectedBg: GradientDrawable,
        content: LinearLayout, dialog: Dialog,
        isGrid: Boolean = false
    ): View {
        val isSelected = index == currentThemeIndex
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))

            if (isGrid) {
                // 网格模式：平分宽度
                val params = android.widget.GridLayout.LayoutParams()
                params.width = 0
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                params.setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
                layoutParams = params
            } else {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp2px(8)
                }
            }

            background = if (isSelected) selectedBg else unselectedBg
            isClickable = true
            isFocusable = true
            foreground = android.util.TypedValue().let { tv ->
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                resources.getDrawable(typedValue.resourceId, theme)
            }
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
                val panel = content.findViewWithTag<View>("custom_color_panel")
                panel?.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (panel.visibility == View.VISIBLE) {
                    currentThemeIndex = -1
                    refreshColorDialogOptions(content, dialog, textPrimary, accent, cardBg)
                }
            } else {
                dialog.dismiss()
                selectColorTheme(index, dialog)
            }
        }
        return row
    }

    /** 刷新弹窗内容（保持双栏结构） */
    private fun refreshColorDialogOptions(content: LinearLayout, dialog: Dialog, textPrimary: Int, accent: Int, cardBg: Int) {
        val chipRadius = 12f * resources.displayMetrics.density
        val selectedBg = CommonDialogHelper.createSelectedBg(accent, chipRadius)
        val unselectedBg = CommonDialogHelper.createUnselectedBg(this, chipRadius)

        content.removeAllViews()

        val grid = android.widget.GridLayout(this).apply {
            columnCount = 2
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(grid)

        ThemeColors.ALL.forEach { palette ->
            grid.addView(buildColorOption(palette.id, palette.name, palette.accentLight,
                textPrimary, accent, cardBg, selectedBg, unselectedBg, content, dialog, isGrid = true))
        }
        val customAccent = SPUtil.getCustomAccentLight(this)
        grid.addView(buildColorOption(-1, "自定义", customAccent,
            textPrimary, accent, cardBg, selectedBg, unselectedBg, content, dialog, isGrid = true))

        val customPanel = createCustomColorPanel(dialog, content, textPrimary, accent, cardBg)
        content.addView(customPanel)
        if (currentThemeIndex == -1) customPanel.visibility = View.VISIBLE
    }

    /** 选择并应用颜色主题 */
    private fun selectColorTheme(index: Int, dialog: Dialog) {
        if (currentThemeIndex == index && index != -1) return
        currentThemeIndex = index
        updateThemeColorSubtitle()

        // 仅在非自定义确认时直接触发 Pulse 动画
        if (index != -1) {
            applyColorThemeChange(index)
        }
    }

    private fun applyColorThemeChange(index: Int) {
        // 使用专门的圆形揭露动画处理原位颜色切换
        // 注意：SP 写入必须在 onMutation 内部，否则 ThemeColors.pageBg() 会在动画截图前读到新值
        AnimationUtil.applyCircleRevealPulse(this) {
            SPUtil.setColorThemeIndex(this, index)

            // 1. 刷新 Activity 自身的颜色
            BackgroundUtil.initActivity(this)
            ThemeUtil.applyToAppSettingsActivity(this)

            // 2. 手动刷新当前显示的配色弹窗 UI
            activeColorDialog?.let { dialog ->
                if (dialog.isShowing) {
                    CommonDialogHelper.applyThemeToDialogRoot(this, dialog)
                    val textPrimary = ThemeColors.textPrimary(this)
                    val accent = ThemeColors.accent(this)
                    val cardBg = ThemeColors.cardBg(this)
                    val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
                    refreshColorDialogOptions(content, dialog, textPrimary, accent, cardBg)
                }
            }

            updateDisplayModeSubtitle()
            updateRefreshIntervalSubtitle()
            // 异步渲染小组件，避免阻塞动画回调
            lifecycleScope.launch { withContext(Dispatchers.IO) { BaseWifiWidget.renderAllWidgets(this@AppSettingsActivity, force = true) } }
            ThemeChangeNotifier.notifyThemeChanged(this)
        }
    }

    /** 创建自定义颜色编辑面板 */
    private fun createCustomColorPanel(dialog: Dialog, content: LinearLayout, textPrimary: Int, accent: Int, cardBg: Int): View {
        val panel = LinearLayout(this).apply {
            tag = "custom_color_panel"
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(12)
            }
        }

        // 分割线
        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp2px(12) }
            setBackgroundColor(textPrimary)
            alpha = 0.12f
        })

        // 标题
        panel.addView(TextView(this).apply {
            text = "自定义强调色"
            setTextColor(textPrimary)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        // 色块 + 输入框 + 应用按钮行
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp2px(10)
            }
        }

        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp2px(40), dp2px(40))
            background = makeDot(SPUtil.getCustomAccentLight(this@AppSettingsActivity))
        }
        inputRow.addView(swatch)

        val tvStatusTip = TextView(this).apply {
            text = "支持十六进制格式 (如 #7B61FF)"
            setTextColor(ThemeColors.textSecondary(this@AppSettingsActivity))
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
                setStroke((1.5f * resources.displayMetrics.density).toInt(), if (ThemeColors.isDark(this@AppSettingsActivity)) 0x30FFFFFF.toInt() else 0x20000000)
            }
            gravity = android.view.Gravity.CENTER
            hint = "#7B61FF"
            inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            setTextColor(textPrimary)
            setHintTextColor(ThemeColors.textSecondary(this@AppSettingsActivity))
            textSize = 13f
            setPadding(dp2px(12), 0, dp2px(12), 0)
            setText(String.format(Locale.US, "#%06X", 0xFFFFFF and SPUtil.getCustomAccentLight(this@AppSettingsActivity)))

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val input = s?.toString()?.trim() ?: ""
                    if (input.isEmpty()) {
                        tvStatusTip.text = "请输入颜色代码"
                        tvStatusTip.setTextColor(ThemeColors.textSecondary(this@AppSettingsActivity))
                        return
                    }
                    val formatted = if (input.startsWith("#")) input else "#$input"
                    try {
                        val color = android.graphics.Color.parseColor(formatted)
                        swatch.background = makeDot(color)
                        tvStatusTip.text = "支持十六进制格式 (如 #7B61FF)"
                        tvStatusTip.setTextColor(ThemeColors.textSecondary(this@AppSettingsActivity))
                    } catch (_: Exception) {
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
                val color = try { android.graphics.Color.parseColor(formatted) } catch (_: Exception) { null }
                if (color != null) {
                    val darkColor = adjustBrightness(color, 0.85f)
                    SPUtil.setCustomAccentLight(this@AppSettingsActivity, color)
                    SPUtil.setCustomAccentDark(this@AppSettingsActivity, darkColor)

                    // 标记当前选中自定义并刷新副标题
                    currentThemeIndex = -1
                    SPUtil.setColorThemeIndex(this@AppSettingsActivity, -1)
                    updateThemeColorSubtitle()

                    // 先关闭弹窗，再应用颜色
                    dialog.dismiss()
                    applyColorThemeChange(-1)

                    ToastUtil.showDropToast(this@AppSettingsActivity, ToastStyle.SUCCESS, "自定义颜色已应用")
                } else {
                    ToastUtil.showDropToast(this@AppSettingsActivity, ToastStyle.WARNING, "颜色格式无效")
                }
            }
        }
        inputRow.addView(btnApply)
        panel.addView(inputRow)
        panel.addView(tvStatusTip)

        return panel
    }

    // ==================== 主界面刷新频率（滑块选择） ====================
    private var activeIntervalDialog: Dialog? = null

    private fun initRefreshIntervalItem() {
        mainIntervalSeconds = SPUtil.getMainRefreshSeconds(this)
        try {
            findInItem<ImageView>(R.id.item_refresh_interval, R.id.common_item_icon)?.setImageResource(R.drawable.ic_clock_bolt)
            findInItem<TextView>(R.id.item_refresh_interval, R.id.common_item_title)?.text = "主界面刷新频率"
        } catch (e: Exception) { DebugLogger.w("AppSettingsActivity", "setting refresh interval item icon/title failed: ${e.message}") }
        updateRefreshIntervalSubtitle()

        findViewById<View>(R.id.item_refresh_interval).setOnClickListener {
            showRefreshIntervalDialog()
        }
    }

    private fun updateRefreshIntervalSubtitle() {
        val label = if (mainIntervalSeconds == 0) "关闭" else "${mainIntervalSeconds} 秒"
        try {
            findInItem<TextView>(R.id.item_refresh_interval, R.id.common_item_subtitle)?.text = label
        } catch (e: Exception) { DebugLogger.w("AppSettingsActivity", "updating refresh interval subtitle failed: ${e.message}") }
    }

    private fun showRefreshIntervalDialog() {
        activeIntervalDialog?.takeIf { it.isShowing }?.dismiss()
        activeIntervalDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this) {
            activeIntervalDialog = null
        }
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "主界面刷新频率"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_clock_bolt)

        // 先应用主题着色，再配置按钮
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        // --- 滑块与标签 ---
        val valueLabel = TextView(this).apply {
            text = "${mainIntervalSeconds} 秒"
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeColors.textPrimary(this@AppSettingsActivity))
            gravity = android.view.Gravity.CENTER
        }

        val slider = ThemeSlider(this).apply {
            minValue = 5f
            maxValue = 120f
            stepSize = 1f
            currentValue = if (mainIntervalSeconds > 0) mainIntervalSeconds.toFloat().coerceIn(5f, 120f) else 5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(44)
            ).apply { topMargin = dp2px(8) }
        }
        // 注意：onValueChange 在下方 createPresetRow 之后统一设置，此处不重复赋值
        ThemedSliderUtil.setupSliderTickMarks(slider, 30f) { "${it}秒" }

        // 实时更新数值（拖动时不受抑制）
        slider.onValueChanging = { value ->
            valueLabel.text = "${value.toInt()} 秒"
        }

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.addView(valueLabel)
        content.addView(slider)

        // 常用值预设（自动跟随滑块高亮）
        val (presetRow, updatePresets) = CommonDialogHelper.createPresetRow(
            context = this,
            values = listOf(5, 10, 15, 30, 60),
            formatLabel = { "${it}秒" },
            currentValue = mainIntervalSeconds,
            onSelect = { slider.currentValue = it.toFloat() }
        )
        content.addView(presetRow)
        // 统一设置 onValueChange（包含 updatePresets），避免覆盖
        slider.onValueChange = { value ->
            mainIntervalSeconds = value.toInt()
            valueLabel.text = "${value.toInt()} 秒"
            updatePresets(value.toInt())
            SPUtil.setMainRefreshSeconds(this@AppSettingsActivity, value.toInt())
            updateRefreshIntervalSubtitle()
        }

        // --- 自定义输入面板（默认隐藏） ---
        val customPanel = createCustomPanel(dialog, textPrimary)
        customPanel.layoutParams = (customPanel.layoutParams as ViewGroup.MarginLayoutParams).also {
            it.topMargin = dp2px(12)
        }
        content.addView(customPanel)

        // --- 按钮区域：使用公共弹窗按钮，由 applyThemeToDialogRoot 自动着色 ---
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
                    et?.let {
                        if (mainIntervalSeconds > 0 && mainIntervalSeconds !in 5..120) {
                            it.setText(mainIntervalSeconds.toString())
                        }
                        it.requestFocus()
                    }
                }
            }
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)
        activeIntervalDialog = dialog
        dialog.show()
    }

    private fun createCustomPanel(dialog: Dialog, textPrimary: Int): View {
        return CommonDialogHelper.createInputPanel(
            context = this,
            hint = "输入 5-3600 秒",
            validate = { text ->
                val secs = text.toIntOrNull()
                when {
                    secs == null -> "请输入有效数字"
                    secs !in 5..3600 -> "请输入 5-3600 之间的秒数"
                    else -> null
                }
            },
            onConfirm = { text ->
                val secs = text.toInt()
                mainIntervalSeconds = secs
                SPUtil.setMainRefreshSeconds(this@AppSettingsActivity, secs)
                updateRefreshIntervalSubtitle()
                updateWifiLockSubtitle()
                dialog.dismiss()
            }
        )
    }

    // ==================== 自定义背景图片 ====================
    private var activeBgDialog: Dialog? = null

    private fun initBgImageItem() {
        // 暂时隐藏入口
    }

    private fun updateBgImageSubtitle() {
        // 暂时隐藏入口
    }

    private fun showBgImageDialog() {
        activeBgDialog?.takeIf { it.isShowing }?.dismiss()
        activeBgDialog = null

        val dialog = CommonDialogHelper.createAnimatedDialog(this) {
            activeBgDialog = null
        }
        dialog.setContentView(R.layout.layout_common_dialog)

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)

        dialog.findViewById<TextView>(R.id.common_dialog_title).text = "自定义背景"
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(R.drawable.ic_photo)
        dialog.findViewById<View>(R.id.common_dialog_button_container).visibility = View.GONE

        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        val cornerRadius = 12f * resources.displayMetrics.density

        // 选项1：选择图片
        content.addView(CommonDialogHelper.buildOptionView(
            context = this,
            label = "从相册选择图片",
            textPrimary = textPrimary,
            accent = accent,
            cornerRadius = cornerRadius
        ) {
            pickImageLauncher.launch("image/*")
            dialog.dismiss()
        })

        // 选项2：清除背景（仅在有自定义背景时显示）
        val hasBg = SPUtil.getBgImageUri(this).isNotBlank()
        if (hasBg) {
            content.addView(CommonDialogHelper.buildOptionView(
                context = this,
                label = "清除背景",
                textPrimary = textPrimary,
                accent = accent,
                cornerRadius = cornerRadius
            ) {
                SPUtil.clearBgImageUri(this)
                BackgroundUtil.clearCache()
                updateBgImageSubtitle()
                BackgroundUtil.applyWindowBackground(this)
                ToastUtil.showDropToast(this, ToastStyle.INFO, "背景已清除")
                dialog.dismiss()
            })
        }

        CommonDialogHelper.setupDialogWindow(this, dialog)

        activeBgDialog = dialog
        dialog.show()
    }

    // ==================== 指定 Wi-Fi 下刷新（弹窗设置） ====================
    private var activeWifiLockDialog: Dialog? = null

    private fun initWifiLockItem() {
        try {
            findInItem<ImageView>(R.id.item_wifi_lock, R.id.common_item_icon)
                ?.setImageResource(R.drawable.ic_filter)
            findInItem<TextView>(R.id.item_wifi_lock, R.id.common_item_title)
                ?.text = "指定 Wi-Fi 下刷新"
        } catch (e: Exception) {
            DebugLogger.w("AppSettingsActivity", "setting wifi lock item icon/title failed: ${e.message}")
        }
        updateWifiLockSubtitle()

        findViewById<View>(R.id.item_wifi_lock).setOnClickListener {
            showWifiLockDialog()
        }
    }

    private fun updateWifiLockSubtitle() {
        val text = if (!SPUtil.getWifiLockEnabled(this)) {
            "已关闭，任何网络下都刷新"
        } else {
            val ssids = SPUtil.getWifiLockSsids(this)
            if (ssids.isEmpty()) "已开启，但尚未指定 Wi-Fi" else "已开启 · ${ssids.size} 个 Wi-Fi"
        }
        try {
            findInItem<TextView>(R.id.item_wifi_lock, R.id.common_item_subtitle)?.text = text
        } catch (e: Exception) {
            DebugLogger.w("AppSettingsActivity", "updating wifi lock subtitle failed: ${e.message}")
        }
    }

    private fun showWifiLockDialog() {
        activeWifiLockDialog?.takeIf { it.isShowing }?.dismiss()
        activeWifiLockDialog = null

        val textPrimary = ThemeColors.textPrimary(this)
        val accent = ThemeColors.accent(this)
        val cornerRadius = 12f * resources.displayMetrics.density

        val enabled = SPUtil.getWifiLockEnabled(this)
        val ssids = SPUtil.getWifiLockSsids(this).sorted()
        val currentSsid = WifiGuard.currentSsid(this)

        activeWifiLockDialog = CommonDialogHelper.showSelectionDialog(
            context = this,
            title = "指定 Wi-Fi 下刷新",
            iconRes = R.drawable.ic_filter,
            onFill = { content, dialog ->
                // 说明区：当前状态 + 当前连接的 Wi-Fi
                content.addView(TextView(this).apply {
                    text = buildString {
                        append(if (enabled) "已开启：只有连在名单内的 Wi-Fi 上才采集。蜂窝、断网、连了别的 Wi-Fi、读不到 Wi-Fi 名称，一律暂停刷新并保持缓存显示。"
                               else "关闭状态下，任何网络都会尝试采集，用蜂窝数据时小组件容易长期显示加载中。")
                        append("\n当前 Wi-Fi：")
                        append(when {
                            !WifiGuard.isOnWifi(this@AppSettingsActivity) -> "未连接 Wi-Fi"
                            currentSsid != null -> currentSsid
                            !WifiGuard.hasLocationPermission(this@AppSettingsActivity) -> "需要定位权限才能读取名称"
                            else -> "读取失败（请确认系统定位已打开）"
                        })
                    }
                    textSize = 12f
                    alpha = 0.6f
                    setTextColor(textPrimary)
                    setPadding(dp2px(4), 0, dp2px(4), dp2px(12))
                })

                // 开关
                content.addView(CommonDialogHelper.buildOptionView(
                    context = this,
                    label = if (enabled) "关闭此功能" else "开启此功能",
                    textPrimary = textPrimary,
                    accent = accent,
                    cornerRadius = cornerRadius
                ) {
                    val turningOn = !enabled
                    SPUtil.setWifiLockEnabled(this, turningOn)
                    updateWifiLockSubtitle()
                    BaseWifiWidget.renderAllWidgets(this, force = true)
                    dialog.dismiss()
                    if (turningOn && !WifiGuard.hasLocationPermission(this)) {
                        locationPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    } else {
                        showWifiLockDialog()
                    }
                })

                // 添加当前 Wi-Fi
                if (currentSsid != null && !ssids.contains(currentSsid)) {
                    content.addView(CommonDialogHelper.buildOptionView(
                        context = this,
                        label = "添加当前 Wi-Fi：$currentSsid",
                        textPrimary = textPrimary,
                        accent = accent,
                        cornerRadius = cornerRadius
                    ) {
                        SPUtil.addWifiLockSsid(this, currentSsid)
                        updateWifiLockSubtitle()
                        BaseWifiWidget.renderAllWidgets(this, force = true)
                        ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "已添加 $currentSsid")
                        dialog.dismiss()
                        showWifiLockDialog()
                    })
                } else if (currentSsid == null && !WifiGuard.hasLocationPermission(this)) {
                    content.addView(CommonDialogHelper.buildOptionView(
                        context = this,
                        label = "授予定位权限以读取 Wi-Fi 名称",
                        textPrimary = textPrimary,
                        accent = accent,
                        cornerRadius = cornerRadius
                    ) {
                        dialog.dismiss()
                        locationPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    })
                }

                // 已添加的名单，点击移除
                ssids.forEach { ssid ->
                    content.addView(CommonDialogHelper.buildOptionView(
                        context = this,
                        label = "移除：$ssid",
                        textPrimary = textPrimary,
                        accent = accent,
                        cornerRadius = cornerRadius
                    ) {
                        SPUtil.removeWifiLockSsid(this, ssid)
                        updateWifiLockSubtitle()
                        BaseWifiWidget.renderAllWidgets(this, force = true)
                        ToastUtil.showDropToast(this, ToastStyle.INFO, "已移除 $ssid")
                        dialog.dismiss()
                        showWifiLockDialog()
                    })
                }
            }
        )
    }

    // ==================== 省电暂停（息屏 / 系统省电模式） ====================
    /**
     * 两个开关都是纯布尔语义，没有别的参数要填，所以直接把开关放在入口行右侧，
     * 不再进弹窗——少一次点击，状态也一眼可见。
     */
    private fun initPowerGuardItem() {
        CommonSettingsItemHelper.setupSwitchItem(
            itemView = findViewById(R.id.item_pause_screen_off),
            iconRes = R.drawable.ic_moon,
            label = "息屏时暂停刷新",
            subtitle = "亮屏瞬间自动补刷一次",
            initialChecked = SPUtil.getPauseOnScreenOff(this)
        ) { checked ->
            SPUtil.setPauseOnScreenOff(this, checked)
            BaseWifiWidget.renderAllWidgets(this, force = true)
        }

        CommonSettingsItemHelper.setupSwitchItem(
            itemView = findViewById(R.id.item_pause_power_save),
            iconRes = R.drawable.ic_battery_4,
            label = "省电模式时暂停刷新",
            subtitle = "阈值告警不受影响",
            initialChecked = SPUtil.getPauseOnPowerSave(this)
        ) { checked ->
            SPUtil.setPauseOnPowerSave(this, checked)
            BaseWifiWidget.renderAllWidgets(this, force = true)
        }
    }

    // ==================== 工具方法 ====================
    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun parseColor(str: String): Int? {
        return try { Color.parseColor(str) } catch (e: Exception) { null }
    }

    private fun adjustBrightness(color: Int, factor: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = ((color shr 16) and 0xFF)
        val g = ((color shr 8) and 0xFF)
        val b = (color and 0xFF)
        val nr = (r * factor).toInt().coerceIn(0, 255)
        val ng = (g * factor).toInt().coerceIn(0, 255)
        val nb = (b * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    /** 创建纯色小圆点 */
    private fun makeDot(color: Int, displayColor: Int = color): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(displayColor)
        }
    }

}
