package com.ufi_axis_widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.BroadcastReceiver
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ufi_axis_widget.db.TrafficRecord
import com.ufi_axis_widget.util.*
import com.ufi_axis_widget.util.source.DeviceDataSourceRegistry

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class TrafficHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var tabBarLayout: LinearLayout
    private lateinit var rootLayout: FrameLayout
    private lateinit var contentLayout: View
    private lateinit var paginationBar: LinearLayout
    private var isDailyMode = true
    private var dailyAdapter = DailyTrafficAdapter()
    private var monthlyAdapter = MonthlyTrafficAdapter()
    private var currentPage = 1
    private var totalPages = 1
    private var pageSize = 30
    private var navBarBottom = 0
    private var themeChangeReceiver: BroadcastReceiver? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_traffic_history)
        pageSize = SPUtil.getTrafficPageSize(this)
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)

        rootLayout = findViewById(R.id.root_layout)
        contentLayout = findViewById(R.id.content_layout)
        tabBarLayout = findViewById(R.id.layout_tab_bar)
        // 总开关使用公共组件
        CommonSettingsItemHelper.setupSwitchItem(
            findViewById(R.id.item_traffic_master_switch),
            iconRes = R.drawable.ic_antenna,
            label = "流量记录总开关",
            subtitle = "开启后，流量记录将自动开始",
            initialChecked = SPUtil.getTrafficRecordEnabled(this),
            onToggle = { checked ->
                SPUtil.setTrafficRecordEnabled(this@TrafficHistoryActivity, checked)
                updateContentVisibility()
            }
        )

        // 手动处理系统栏内边距
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val statusBar = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            navBarBottom = navBar.bottom
            // 状态栏高度应用到 header 顶部
            val headerFrame = contentLayout.findViewById<View>(R.id.btn_back)?.parent as? FrameLayout
            headerFrame?.setPadding(
                headerFrame.paddingLeft,
                statusBar.top,
                headerFrame.paddingRight,
                headerFrame.paddingBottom
            )
            // 导航栏高度应用到内容区域底部
            if (navBarBottom > 0) {
                contentLayout.setPadding(
                    contentLayout.paddingLeft,
                    contentLayout.paddingTop,
                    contentLayout.paddingRight,
                    navBarBottom
                )
                if (::paginationBar.isInitialized) {
                    val lp = paginationBar.layoutParams as? FrameLayout.LayoutParams
                    val d = resources.displayMetrics.density
                    val minMargin = (8 * d).toInt()
                    lp?.bottomMargin = if (navBarBottom > 0) navBarBottom else minMargin
                    paginationBar.layoutParams = lp
                }
            }
            insets
        }

        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        recyclerView = findViewById(R.id.recycler_traffic_records)
        emptyView = findViewById(R.id.tv_traffic_empty)
        subtitleView = findViewById(R.id.tv_traffic_subtitle)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 设置按钮 -> 弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_traffic_settings)) { showSettingsDialog() }

        // 标签切换 + 点击动画
        val tabDaily = findViewById<View>(R.id.tab_daily)
        val tabMonthly = findViewById<View>(R.id.tab_monthly)
        AnimationUtil.applyScaleClickAnimation(tabDaily) { switchToDaily() }
        AnimationUtil.applyScaleClickAnimation(tabMonthly) { switchToMonthly() }

        // 主题变更监听
        themeChangeReceiver = ThemeChangeNotifier.register(this) {
            ThemeUtil.applyThemeSync(this@TrafficHistoryActivity, ThemeUtil.PageType.SETTINGS_LIST)
            updateTabStyles()
        }

        // 等主题异步应用完成后刷新标签样式（修复首次打开文字颜色不对的问题）
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                updateTabStyles()
                updateContentVisibility()
            }
        }

        setupPaginationBar()
        recyclerView.adapter = dailyAdapter
        loadRecords()
    }

    private fun updateContentVisibility() {
        val enabled = SPUtil.getTrafficRecordEnabled(this)
        if (enabled) {
            animateVisibility(tabBarLayout, true)
            animateVisibility(recyclerView, true)
            // loadRecords 内部会处理 emptyView 状态，先设为加载提示
            emptyView.text = "加载中..."
            emptyView.alpha = 0.5f
            if (::paginationBar.isInitialized) {
                PaginationBarHelper.fadeVisibility(paginationBar, totalPages > 1)
            }
            loadRecords()
        } else {
            animateVisibility(tabBarLayout, false)
            animateVisibility(recyclerView, false)
            emptyView.visibility = View.VISIBLE
            emptyView.text = "总开关未开启"
            emptyView.alpha = 0.5f
            if (::paginationBar.isInitialized) {
                PaginationBarHelper.fadeVisibility(paginationBar, false)
            }
        }
    }

    private fun animateVisibility(view: View, visible: Boolean) {
        val targetAlpha = if (visible) 1f else 0f
        if (view.alpha == targetAlpha && (visible == (view.visibility == View.VISIBLE))) return
        view.animate().cancel()
        if (visible && view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(targetAlpha)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(if (!visible) object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                }
            } else null)
            .start()
    }

    // ── 弹窗设置 ──

    private fun showSettingsDialog() {
        val hourlyEnabled = SPUtil.getTrafficHourlyRecordEnabled(this)
        val resetDay = SPUtil.getTrafficMonthlyResetDay(this)
        var curPageSize = pageSize
        CommonDialogHelper.showCommonDialog(
            context = this,
            title = "记录设置",
            iconRes = R.drawable.ic_settings,
            onFill = { content ->
                // 每小时记录开关
                val switchRow = CommonSettingsItemHelper.createSwitchRow(
                    context = this,
                    label = "每小时记录",
                    subtitle = "开启后将以小时为单位记录流量数据，关闭则按天记录",
                    initialChecked = hourlyEnabled,
                    onToggle = { checked ->
                        SPUtil.setTrafficHourlyRecordEnabled(this, checked)
                    }
                )
                content.addView(switchRow)

                // 分隔线
                content.addView(CommonSettingsItemHelper.createDivider(this))

                // 快捷入口开关
                val quickEntryRow = CommonSettingsItemHelper.createSwitchRow(
                    context = this,
                    label = "快捷入口",
                    subtitle = "可点击主界面的流量信息控件快速进入",
                    initialChecked = SPUtil.getTrafficQuickEntryEnabled(this),
                    onToggle = { checked ->
                        SPUtil.setTrafficQuickEntryEnabled(this, checked)
                    }
                )
                content.addView(quickEntryRow)

                // 分隔线
                content.addView(CommonSettingsItemHelper.createDivider(this))

                // 每页显示条数
                val tvPageLabel = TextView(this).apply {
                    text = "每页显示条数"
                    setTextAppearance(R.style.AppText_Title)
                    setTextColor(ThemeColors.textPrimary(this@TrafficHistoryActivity))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                content.addView(tvPageLabel)

                val pageOptions = listOf(10, 20, 30, 50, 100)
                var pageUpdate: ((Int) -> Unit)? = null
                val (pageRow, pUpdate) = CommonDialogHelper.createPresetRow(
                    context = this, values = pageOptions,
                    formatLabel = { "$it" }, currentValue = curPageSize,
                    onSelect = { v -> curPageSize = v; pageUpdate?.invoke(v) }
                )
                pageUpdate = pUpdate
                content.addView(pageRow)

                // 分隔线
                content.addView(CommonSettingsItemHelper.createDivider(this))

                // 每月重置日标签
                val tvLabel = TextView(this).apply {
                    text = "每月重置日"
                    setTextAppearance(R.style.AppText_Title)
                    setTextColor(ThemeColors.textPrimary(this@TrafficHistoryActivity))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                content.addView(tvLabel)

                val tvSub = TextView(this).apply {
                    text = "流量统计按此日期重置，仅影响月度视图显示"
                    setTextAppearance(R.style.AppText_Subtitle)
                    setTextColor(ThemeColors.textSecondary(this@TrafficHistoryActivity))
                }
                content.addView(tvSub)

                // 自定义输入框：手动输入重置日
                val inputLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
                }
                val density = resources.displayMetrics.density
                val cardBg = ThemeColors.cardBg(this)
                val textPrimary = ThemeColors.textPrimary(this)
                val etResetDay = EditText(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(cardBg)
                        cornerRadius = 8f * density
                        setStroke(1, if (ThemeColors.isDark(this@TrafficHistoryActivity))
                            0x30FFFFFF.toInt() else 0x20000000)
                    }
                    gravity = android.view.Gravity.CENTER
                    hint = "输入1~28"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    maxLines = 1
                    setTextColor(textPrimary)
                    setHintTextColor(ThemeColors.textSecondary(this@TrafficHistoryActivity))
                    textSize = 13f
                    setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                    setText(resetDay.toString())
                }
                inputLayout.addView(etResetDay)

                val btnConfirm = com.google.android.material.button.MaterialButton(this).apply {
                    text = "确定"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeColors.btnBg(this@TrafficHistoryActivity))
                    setTextColor(0xFFFFFFFF.toInt())
                    setCornerRadius((12f * density).toInt())
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (48 * density).toInt()
                    ).apply { marginStart = (8 * density).toInt() }
                }
                AnimationUtil.applyScaleClickAnimation(btnConfirm) {
                    val text = etResetDay.text.toString()
                    val v = text.toIntOrNull()
                    if (v == null || v < 1 || v > 28) {
                        ToastUtil.showDropToast(
                            this@TrafficHistoryActivity,
                            ToastStyle.WARNING,
                            "请输入1~28之间的日期"
                        )
                    } else {
                        SPUtil.setTrafficMonthlyResetDay(this@TrafficHistoryActivity, v)
                        ToastUtil.showDropToast(
                            this@TrafficHistoryActivity,
                            ToastStyle.SUCCESS,
                            "已设为每月${v}日"
                        )
                    }
                }
                inputLayout.addView(btnConfirm)
                content.addView(inputLayout)

                // ── 套餐额度 ──
                content.addView(CommonSettingsItemHelper.createDivider(this))
                addNumberSetting(
                    content = content,
                    label = "月度套餐额度 (GB)",
                    subtitle = "填 0 表示不设置。已用量按本地每日记录在账期区间内求和 —— " +
                        "设备上报的月累计是自然月口径，账期不是 1 号时会算错，所以不用它",
                    hint = "如 100",
                    initial = quotaGbText(),
                    validate = { it.toDoubleOrNull()?.takeIf { v -> v >= 0 } != null },
                    onConfirm = { text ->
                        val gb = text.toDouble()
                        SPUtil.setTrafficQuotaBytes(this, (gb * 1024 * 1024 * 1024).toLong())
                        ToastUtil.showDropToast(
                            this, ToastStyle.SUCCESS,
                            if (gb <= 0) "已取消额度设置" else "额度已设为 ${text}GB"
                        )
                    },
                    invalidHint = "请输入非负数字"
                )

                // ── 账期起始日 ──
                content.addView(CommonSettingsItemHelper.createDivider(this))
                addNumberSetting(
                    content = content,
                    label = "账期起始日",
                    subtitle = "运营商账期常常不是 1 号。限 1~28：29 及以后的日期在 2 月不存在，" +
                        "允许设置只会制造一堆边界分支",
                    hint = "输入1~28",
                    initial = SPUtil.getTrafficBillingDay(this).toString(),
                    validate = { it.toIntOrNull()?.let { v -> v in 1..28 } == true },
                    onConfirm = { text ->
                        SPUtil.setTrafficBillingDay(this, text.toInt())
                        ToastUtil.showDropToast(this, ToastStyle.SUCCESS, "账期起始日已设为每月 ${text} 日")
                    },
                    invalidHint = "请输入1~28之间的日期"
                )

                // ── 导出 ──
                content.addView(CommonSettingsItemHelper.createDivider(this))
                val btnExport = com.google.android.material.button.MaterialButton(this).apply {
                    text = "导出为 CSV"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        ThemeColors.btnBg(this@TrafficHistoryActivity)
                    )
                    setTextColor(0xFFFFFFFF.toInt())
                    setCornerRadius((12f * density).toInt())
                    insetTop = 0
                    insetBottom = 0
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()
                    ).apply { topMargin = (12 * density).toInt() }
                }
                AnimationUtil.applyScaleClickAnimation(btnExport) { launchExport() }
                content.addView(btnExport)
            },
            primaryBtnText = "保存",
            onPrimaryClick = { d ->
                SPUtil.setTrafficPageSize(this@TrafficHistoryActivity, curPageSize)
                pageSize = curPageSize
                currentPage = 1
                loadRecords()
                d.dismiss()
            },
            secondaryBtnText = "取消",
            onSecondaryClick = { d -> d.dismiss() }
        )
    }

    /** 额度输入框的初值：字节转 GB，去掉无意义的小数尾巴 */
    private fun quotaGbText(): String {
        val bytes = SPUtil.getTrafficQuotaBytes(this)
        if (bytes <= 0) return "0"
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        return if (gb == gb.toLong().toDouble()) gb.toLong().toString()
        else String.format(Locale.US, "%.1f", gb)
    }

    /**
     * 往弹窗里加一组「标题 + 说明 + 数字输入框 + 确定」。
     *
     * 抽出来是因为额度、账期日、原有的重置日结构完全一样，三份复制粘贴改一处就会漏。
     */
    private fun addNumberSetting(
        content: LinearLayout,
        label: String,
        subtitle: String,
        hint: String,
        initial: String,
        validate: (String) -> Boolean,
        onConfirm: (String) -> Unit,
        invalidHint: String,
    ) {
        val density = resources.displayMetrics.density
        content.addView(TextView(this).apply {
            text = label
            setTextAppearance(R.style.AppText_Title)
            setTextColor(ThemeColors.textPrimary(this@TrafficHistoryActivity))
        })
        content.addView(TextView(this).apply {
            text = subtitle
            setTextAppearance(R.style.AppText_Subtitle)
            setTextColor(ThemeColors.textSecondary(this@TrafficHistoryActivity))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        }
        val input = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ThemeColors.cardBg(this@TrafficHistoryActivity))
                cornerRadius = 8f * density
                setStroke(
                    1,
                    if (ThemeColors.isDark(this@TrafficHistoryActivity)) 0x30FFFFFF.toInt() else 0x20000000
                )
            }
            gravity = Gravity.CENTER
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            maxLines = 1
            setTextColor(ThemeColors.textPrimary(this@TrafficHistoryActivity))
            setHintTextColor(ThemeColors.textSecondary(this@TrafficHistoryActivity))
            textSize = 13f
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            setText(initial)
        }
        row.addView(input)

        val btn = com.google.android.material.button.MaterialButton(this).apply {
            text = "确定"
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ThemeColors.btnBg(this@TrafficHistoryActivity)
            )
            setTextColor(0xFFFFFFFF.toInt())
            setCornerRadius((12f * density).toInt())
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (48 * density).toInt()
            ).apply { marginStart = (8 * density).toInt() }
        }
        AnimationUtil.applyScaleClickAnimation(btn) {
            val text = input.text.toString().trim()
            if (!validate(text)) {
                ToastUtil.showDropToast(this, ToastStyle.WARNING, invalidHint)
            } else {
                onConfirm(text)
                loadRecords()
            }
        }
        row.addView(btn)
        content.addView(row)
    }

    // ── CSV 导出 ──

    /**
     * 导出目标由系统文件选择器决定（SAF），因此**不需要任何存储权限**。
     *
     * 不要为此去申请 WRITE_EXTERNAL_STORAGE：清单里那条声明带 maxSdkVersion=28，
     * 是别的历史用途，复用它在新系统上根本拿不到权限。
     */
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { exportTo(it) } }

    private fun launchExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date())
        try {
            exportLauncher.launch("traffic-$stamp.csv")
        } catch (e: Exception) {
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "无法打开文件选择器", e.message ?: "")
        }
    }

    private fun exportTo(uri: android.net.Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var rows = 0
                    contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
                        requireNotNull(writer) { "无法写入所选位置" }
                        writer.write("dateKey,recordType,source,dailyBytes,monthlyBytes,timestamp\n")
                        // 分页读 + 逐行写：记录可能上千条，先在内存里拼一个大 String
                        // 既费内存又没有任何好处
                        val batch = 200
                        var offset = 0
                        while (true) {
                            val page = TrafficRecordManager.getRecentPaged(
                                this@TrafficHistoryActivity, batch, offset
                            )
                            if (page.isEmpty()) break
                            page.forEach { r ->
                                writer.write(
                                    "${r.dateKey},${r.recordType},\"${r.source}\"," +
                                        "${r.dailyRawBytes},${r.monthlyRawBytes},${r.timestamp}\n"
                                )
                            }
                            rows += page.size
                            if (page.size < batch) break
                            offset += batch
                        }
                        writer.flush()
                    }
                    rows
                }
            }
            result.onSuccess { rows ->
                ToastUtil.showDropToast(
                    this@TrafficHistoryActivity, ToastStyle.SUCCESS, "已导出 $rows 条记录"
                )
            }.onFailure { e ->
                ToastUtil.showDropToast(
                    this@TrafficHistoryActivity, ToastStyle.WARNING, "导出失败", e.message ?: ""
                )
            }
        }
    }

    // ── 模式切换 ──

    private fun switchToDaily() {
        if (isDailyMode) return
        isDailyMode = true
        currentPage = 1
        recyclerView.adapter = dailyAdapter
        updateTabStyles()
        loadRecords()  // loadRecords 内部会设置正确的 subtitle
    }

    private fun switchToMonthly() {
        if (!isDailyMode) return
        isDailyMode = false
        currentPage = 1
        recyclerView.adapter = monthlyAdapter
        updateTabStyles()
        subtitleView.text = "每月流量使用统计"
        loadRecords()
    }

    private fun updateTabStyles() {
        val tabDaily = findViewById<TextView>(R.id.tab_daily)
        val tabMonthly = findViewById<TextView>(R.id.tab_monthly)
        val accent = ThemeColors.accent(this)
        val textPrimary = ThemeColors.textPrimary(this)
        val textSecondary = ThemeColors.textSecondary(this)
        val density = resources.displayMetrics.density

        listOf(tabDaily to isDailyMode, tabMonthly to !isDailyMode).forEach { (tab, active) ->
            if (active) {
                tab.setTextColor(if (ThemeColors.isDark(this@TrafficHistoryActivity))
                    0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                tab.background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = 8f * density
                }
            } else {
                tab.setTextColor(textSecondary)
                tab.background = null
            }
        }
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        super.onDestroy()
    }

    // ── 数据加载 ──

    private fun loadRecords() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                if (isDailyMode) {
                    val hourlyEnabled = SPUtil.getTrafficHourlyRecordEnabled(this@TrafficHistoryActivity)

                    if (hourlyEnabled) {
                        // 每小时模式：加载所有小时记录，计算差值后分页
                        val allHourly = mutableListOf<TrafficRecord>()
                        var off = 0
                        val batch = 100
                        while (true) {
                            val page = TrafficRecordManager.getHourlyPaged(this@TrafficHistoryActivity, batch, off)
                            if (page.isEmpty()) break
                            allHourly.addAll(page)
                            if (page.size < batch) break
                            off += batch
                        }
                        val deltaRecords = computeHourlyDeltas(allHourly)
                        totalPages = ((deltaRecords.size + pageSize - 1) / pageSize).coerceAtLeast(1)
                        currentPage = currentPage.coerceAtMost(totalPages)
                        val start = (currentPage - 1) * pageSize
                        val end = minOf(start + pageSize, deltaRecords.size)
                        val records = if (start < deltaRecords.size) deltaRecords.subList(start, end) else emptyList()

                        if (records.isEmpty()) {
                            recyclerView.visibility = View.GONE
                            emptyView.visibility = View.VISIBLE
                            emptyView.text = "暂无每小时流量记录"
                            emptyView.alpha = 1f
                        } else {
                            recyclerView.visibility = View.VISIBLE
                            emptyView.visibility = View.GONE
                            dailyAdapter.submitList(records)
                        }
                        subtitleView.text = "每小时流量消耗记录" + derivedDailyHint() + quotaLine()

                    } else {
                        // 每日模式：正常分页查询
                        val totalCount = TrafficRecordManager.getCount(this@TrafficHistoryActivity)
                        totalPages = ((totalCount + pageSize - 1) / pageSize).coerceAtLeast(1)
                        currentPage = currentPage.coerceAtMost(totalPages)
                        val offset = (currentPage - 1) * pageSize
                        val records = TrafficRecordManager.getRecentPaged(this@TrafficHistoryActivity, pageSize, offset)
                        if (records.isEmpty()) {
                            recyclerView.visibility = View.GONE
                            emptyView.visibility = View.VISIBLE
                            emptyView.text = "暂无流量记录"
                            emptyView.alpha = 1f
                        } else {
                            recyclerView.visibility = View.VISIBLE
                            emptyView.visibility = View.GONE
                            dailyAdapter.submitList(records)
                        }
                        subtitleView.text = "每日流量使用历史" + derivedDailyHint() + quotaLine()

                    }
                } else {
                    val totalCount = TrafficRecordManager.getMonthlyCount(this@TrafficHistoryActivity)
                    totalPages = ((totalCount + pageSize - 1) / pageSize).coerceAtLeast(1)
                    currentPage = currentPage.coerceAtMost(totalPages)
                    val offset = (currentPage - 1) * pageSize
                    val records = TrafficRecordManager.getMonthlyPaged(this@TrafficHistoryActivity, pageSize, offset)
                    if (records.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        emptyView.visibility = View.VISIBLE
                        emptyView.text = "暂无月度统计"
                        emptyView.alpha = 1f
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        emptyView.visibility = View.GONE
                        monthlyAdapter.submitList(records)
                    }
                }
                PaginationBarHelper.update(paginationBar, currentPage, totalPages)
                PaginationBarHelper.fadeVisibility(paginationBar, totalPages > 1)
            } catch (e: Exception) {
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.text = "加载失败: ${e.message}"
                emptyView.alpha = 1f
            }
        }
    }

    // ── 翻页栏 ──

    /**
     * 当前数据源没有日流量字段时的推算说明。
     *
     * 这类数据源（如 goform 只有 `monthly_rx/tx_bytes`）的日用量是
     * [com.ufi_axis_widget.util.TrafficRecordManager] 用月累计跨天做差推出来的，
     * 精度不如设备直接上报，得让用户知道。支持日流量的数据源返回空串。
     */
    private fun derivedDailyHint(): String =
        if (DeviceDataSourceRegistry.currentCapabilities(this).dailyTraffic) ""
        else "（当前数据源仅有月累计，日用量按跨天差值推算）"

    /**
     * 套餐额度与用完预测的一行摘要。未设额度时返回空串（不占位）。
     *
     * 已用量取自本地每日记录按账期区间求和，而不是设备的 `monthly_*_bytes` ——
     * 后者是自然月统计，账期不是 1 号时和额度根本不对齐。代价是本地没记录的日子
     * 不计入，所以文案里要点明「按本地记录统计」，别让用户以为这是运营商口径。
     */
    private suspend fun quotaLine(): String {
        val fc = try {
            TrafficForecast.compute(this)
        } catch (e: Exception) {
            DebugLogger.w("TrafficHistoryActivity", "额度推算失败: ${e.message}")
            return ""
        }
        if (!fc.hasQuota) return ""
        return buildString {
            append("\n本账期（")
            append(fc.cycleStartKey)
            append(" 起）已用 ")
            append(formatFlow(fc.usedBytes))
            append(" / ")
            append(formatFlow(fc.quotaBytes))
            if (fc.exhaustDateText.isNotEmpty()) {
                append(" · ")
                append(fc.exhaustDateText)
            }
            append("（按本地记录统计）")
        }
    }

    /**
     * 从累计值计算每小时消耗差值。
     * 记录按 dateKey 降序排列（最新在前），每条与前一条（更早的小时）比较。
     * 同一天内的差值 = 当前累计 - 前一小时累计；跨天时使用当前累计值。
     */
    private fun computeHourlyDeltas(records: List<TrafficRecord>): List<TrafficRecord> {
        if (records.size <= 1) return records
        val result = mutableListOf<TrafficRecord>()
        for (i in records.indices) {
            val cur = records[i]
            if (i == records.size - 1) {
                // 最早的记录，无前序可比较，直接使用累计值
                result.add(cur)
            } else {
                val prev = records[i + 1]  // 降序排列，i+1 是更早的记录
                val curDay = cur.dateKey.substring(0, minOf(10, cur.dateKey.length))
                val prevDay = prev.dateKey.substring(0, minOf(10, prev.dateKey.length))
                val deltaDaily = if (curDay == prevDay) {
                    maxOf(0L, cur.dailyRawBytes - prev.dailyRawBytes)
                } else {
                    cur.dailyRawBytes  // 跨天首小时，使用累计值
                }
                val deltaMonthly = maxOf(0L, cur.monthlyRawBytes - prev.monthlyRawBytes)
                result.add(cur.copy(dailyRawBytes = deltaDaily, monthlyRawBytes = deltaMonthly))
            }
        }
        return result
    }

    private fun setupPaginationBar() {
        paginationBar = PaginationBarHelper.create(this) { action ->
            when (action) {
                PaginationBarHelper.Action.FIRST -> { currentPage = 1; loadRecords() }
                PaginationBarHelper.Action.PREV -> { currentPage = (currentPage - 1).coerceAtLeast(1); loadRecords() }
                PaginationBarHelper.Action.NEXT -> { currentPage = (currentPage + 1).coerceAtMost(totalPages); loadRecords() }
                PaginationBarHelper.Action.LAST -> { currentPage = totalPages; loadRecords() }
                is PaginationBarHelper.Action.Jump -> { currentPage = action.page.coerceIn(1, totalPages); loadRecords() }
            }
        }
        val d = resources.displayMetrics.density
        rootLayout.addView(paginationBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val minMargin = (8 * d).toInt()
            bottomMargin = if (navBarBottom > 0) navBarBottom else minMargin
            leftMargin = (16 * d).toInt()
            rightMargin = (16 * d).toInt()
        })
        paginationBar.visibility = View.GONE
        paginationBar.alpha = 0f
    }
}

// ═══════════════════════════════════════════
// 每日记录适配器（DiffUtil 增量更新）
// ═══════════════════════════════════════════

class DailyTrafficAdapter : ListAdapter<TrafficRecord, DailyTrafficAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TrafficRecord>() {
            override fun areItemsTheSame(old: TrafficRecord, new: TrafficRecord) = old.id == new.id
            override fun areContentsTheSame(old: TrafficRecord, new: TrafficRecord) = old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_traffic_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDate: TextView = itemView.findViewById(R.id.tv_record_date)
        private val tvDaily: TextView = itemView.findViewById(R.id.tv_record_daily)
        private val tvMonthly: TextView = itemView.findViewById(R.id.tv_record_monthly)

        // 缓存 SimpleDateFormat，避免每次 bind 重建
        private val parseHourly = SimpleDateFormat("yyyy-MM-dd-HH", Locale.getDefault())
        private val parseDaily = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val fmtHourly = SimpleDateFormat("MM-dd HH:00", Locale.getDefault())
        private val fmtDaily = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

        fun bind(record: TrafficRecord) {
            tvDate.text = formatDateKey(record.dateKey)
            if (record.recordType == "hourly") {
                tvDaily.text = "每小时流量: ${formatBytes(record.dailyRawBytes)}"
                tvMonthly.text = "每小时月增量: ${formatBytes(record.monthlyRawBytes)}"
            } else {
                tvDaily.text = "日流量: ${formatBytes(record.dailyRawBytes)}"
                tvMonthly.text = "月累计: ${formatBytes(record.monthlyRawBytes)}"
            }
        }

        private fun formatDateKey(dateKey: String): String {
            return try {
                if (dateKey.length > 10) {
                    parseHourly.parse(dateKey)?.let { fmtHourly.format(it) } ?: dateKey
                } else {
                    parseDaily.parse(dateKey)?.let { fmtDaily.format(it) } ?: dateKey
                }
            } catch (_: Exception) { dateKey }
        }

        private fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1_073_741_824.0)
                bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
                bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}

// ═══════════════════════════════════════════
// 月度记录适配器（DiffUtil 增量更新）
// ═══════════════════════════════════════════

class MonthlyTrafficAdapter : ListAdapter<TrafficRecord, MonthlyTrafficAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TrafficRecord>() {
            override fun areItemsTheSame(old: TrafficRecord, new: TrafficRecord) = old.id == new.id
            override fun areContentsTheSame(old: TrafficRecord, new: TrafficRecord) = old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_traffic_record_monthly, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMonth: TextView = itemView.findViewById(R.id.tv_month_label)
        private val tvTraffic: TextView = itemView.findViewById(R.id.tv_month_traffic)

        private val parseMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        private val fmtMonth = SimpleDateFormat("yyyy年MM月", Locale.getDefault())

        fun bind(record: TrafficRecord) {
            tvMonth.text = formatMonthKey(record.dateKey)
            tvTraffic.text = "月流量: ${formatBytes(record.monthlyRawBytes)}"
        }

        private fun formatMonthKey(dateKey: String): String {
            return try {
                val monthPart = dateKey.substring(0, 7.coerceAtMost(dateKey.length))
                val date = parseMonth.parse(monthPart)
                if (date != null) fmtMonth.format(date) else dateKey
            } catch (_: Exception) { dateKey }
        }

        private fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1_073_741_824.0)
                bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
                bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}