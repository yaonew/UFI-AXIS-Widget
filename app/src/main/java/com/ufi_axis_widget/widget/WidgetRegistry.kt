package com.ufi_axis_widget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.ufi_axis_widget.R
import com.ufi_axis_widget.util.DeviceCapabilities
import com.ufi_axis_widget.util.widget.WidgetPrefs

/**
 * 数据源能力枚举，把 [DeviceCapabilities] 的布尔位变成可以放进声明式表格里的值。
 *
 * 用途有两个：小组件声明自己需要哪些能力（数据源不满足时设置页给出提示），
 * 单个显示项声明自己依赖哪个能力（不满足时该项在渲染与设置页里一起隐藏）。
 */
enum class Capability(val label: String) {
    CPU("CPU 占用"),
    MEMORY("内存占用"),
    TEMPERATURE("设备温度"),
    STORAGE("存储空间"),
    BATTERY_DETAIL("电池电流/电压"),
    AT_NETWORK("信号详情"),
    DAILY_TRAFFIC("当日流量");

    fun isSupported(caps: DeviceCapabilities): Boolean = when (this) {
        CPU -> caps.cpu
        MEMORY -> caps.memory
        TEMPERATURE -> caps.temperature
        STORAGE -> caps.storage
        BATTERY_DETAIL -> caps.batteryDetail
        AT_NETWORK -> caps.atNetwork
        DAILY_TRAFFIC -> caps.dailyTraffic
    }
}

/**
 * 渲染期的配置作用域。
 *
 * 渲染函数不再直接读 SP 裸 key，一律通过这里取值，作用域回退规则集中在 [WidgetPrefs]。
 * [appWidgetId] 为 null 表示按「类型层」渲染一份 RemoteViews 广播给该类型的全部实例；
 * 非 null 表示该实例有独立配置，必须单独构建。
 */
class WidgetScope(
    val context: Context,
    val kind: String,
    val appWidgetId: Int? = null,
) {
    fun show(key: String, default: Boolean = true): Boolean =
        WidgetPrefs.getBool(context, kind, key, default, appWidgetId)

    fun int(key: String, default: Int): Int =
        WidgetPrefs.getInt(context, kind, key, default, appWidgetId)
}

/** 渲染函数签名。数据渲染与主题着色共用，两者都只是往 RemoteViews 上写属性。 */
fun interface WidgetRenderer {
    fun render(context: Context, rv: RemoteViews, scope: WidgetScope)
}

/**
 * 一个可开关的显示项。
 *
 * 设置页据此动态生成开关列表 —— 这是「新增组件不用改设置页」的关键：
 * 组件把自己有哪些可显示内容声明在 [WidgetSpec.fields] 里，UI 只管遍历。
 */
data class FieldSpec(
    val key: String,
    val label: String,
    val subtitle: String? = null,
    val default: Boolean = true,
    /** 该项依赖的数据源能力，null 表示任何数据源都有 */
    val requires: Capability? = null,
)

/**
 * 一种形态在不同数据源下的呈现变体。
 *
 * 同一个桌面组件不该因为换了数据源就要用户删掉重加：能力齐全时用信息量最大的
 * 布局，缺能力时整体换成另一套布局与显示项，而不是留一排空槽位。
 *
 * @param requires 该变体依赖的能力，全部满足才会被选中；[WidgetSpec.variants]
 *                 的最后一项是兜底，即使不满足也会用
 * @param fixedNote 该变体里「固定显示、不给开关」的槽位说明。小尺寸版面没有余量做
 *                  显隐组合（隐掉一个图标也换不来别的信息），与其给一堆改了没意义的
 *                  开关，不如直接固定下来 —— 但必须在弹窗里说明，否则用户会以为开关丢了
 */
data class WidgetVariant(
    val layoutId: Int,
    val fields: List<FieldSpec>,
    val renderer: WidgetRenderer,
    val requires: Set<Capability> = emptySet(),
    val fixedNote: String? = null,
)

/**
 * 一种小组件形态的完整声明。
 *
 * 新增一个组件的全部成本：写一个 layout、写一个 renderer、在 [WidgetRegistry.all] 加一行、
 * Manifest 加一个 receiver、res/xml 加一个 provider 元数据。渲染分发、配置读写、
 * 设置页 UI 都会自动适配，不需要再改任何 if/else。
 *
 * @param kind           作用域标识，同时是配置 key 的前缀，一旦发布不可更名（会丢配置）
 * @param shadowProvider 隐藏桌面标签用的影子 receiver。[com.ufi_axis_widget.util.WidgetLabelToggle]
 *                       依赖「主 + 影子」两个 receiver 的 enabled 互斥切换，所以这层配对
 *                       关系必须建模出来；为 null 表示该形态不支持隐藏标签
 * @param enabled        与 Manifest 里 receiver 的 `android:enabled` 保持一致。
 *                       设为 false 的形态不参与渲染分发，也不出现在设置页
 * @param variants       呈现变体，按顺序取第一个能力满足的，最后一项兜底
 * @param cellW          桌面占用的横向格数，与 res/xml 里的 `targetCellWidth` 保持一致
 * @param cellH          桌面占用的纵向格数，与 res/xml 里的 `targetCellHeight` 保持一致
 */
data class WidgetSpec(
    val kind: String,
    val provider: Class<out AppWidgetProvider>,
    val shadowProvider: Class<out AppWidgetProvider>? = null,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
    val cellW: Int,
    val cellH: Int,
    val variants: List<WidgetVariant>,
    val hasFontSize: Boolean = false,
    val themer: WidgetRenderer,
) {
    /** 主 + 影子，用于遍历「该形态下所有可能已放置实例」的 provider */
    val providers: List<Class<out AppWidgetProvider>>
        get() = listOfNotNull(provider, shadowProvider)

    /**
     * 标称宽高比，仅在拿不到实测尺寸时兜底（全局作用域、options 还没下发）。
     *
     * 由 `格数×70−30` 推出，与 `res/xml` 的 minWidth/minHeight 同一套公式；
     * 它和桌面真实比例有偏差，所以能拿到 [WidgetRegistry.measuredAspect] 时优先用后者。
     */
    val nominalAspect: Float
        get() = (cellW * 70 - 30).toFloat() / (cellH * 70 - 30).coerceAtLeast(1)

    fun variantFor(caps: DeviceCapabilities): WidgetVariant =
        variants.firstOrNull { v -> v.requires.all { it.isSupported(caps) } } ?: variants.last()
}

/**
 * 小组件形态注册表。
 *
 * 改造前「有哪些小组件」散落在 `renderAllSizes` 的硬编码列表、设置页的硬编码开关、
 * 以及三处重复的显示项代码里，加一个形态要改六个地方。现在统一收敛到这张表。
 */
object WidgetRegistry {

    const val KIND_4X2 = "4x2"
    const val KIND_2X1 = "2x1"
    const val KIND_4X1 = "4x1"
    const val KIND_2X2 = "2x2"

    /**
     * 「中间大字」候选指标，**列表顺序即双击轮播顺序**。
     *
     * 渲染层（`BaseWifiWidget.centerMetrics`）与设置页共用这一份声明：顺序、文案、
     * 能力依赖都只写一处。两边各写一份的直接后果是「设置里第二项」和「双击后的第二项」
     * 对不上，用户只会觉得双击是随机跳。
     */
    val CENTER_METRIC_FIELDS: List<FieldSpec> = listOf(
        FieldSpec(WidgetPrefs.CENTER_FLOW, "大字 · 本月流量", "选中多项后双击组件轮播切换"),
        FieldSpec(
            WidgetPrefs.CENTER_DAILY, "大字 · 今日流量",
            default = false, requires = Capability.DAILY_TRAFFIC
        ),
        FieldSpec(WidgetPrefs.CENTER_SIGNAL, "大字 · 信号强度", default = false),
        FieldSpec(WidgetPrefs.CENTER_BATTERY, "大字 · 电池电量", default = false),
        FieldSpec(
            WidgetPrefs.CENTER_TEMP, "大字 · 设备温度",
            default = false, requires = Capability.TEMPERATURE
        ),
        FieldSpec(
            WidgetPrefs.CENTER_CPU, "大字 · CPU 占用",
            default = false, requires = Capability.CPU
        ),
        FieldSpec(
            WidgetPrefs.CENTER_MEM, "大字 · 内存占用",
            default = false, requires = Capability.MEMORY
        ),
    )

    /**
     * 无能力依赖的大字候选（本月流量 / 信号 / 电量），任何数据源都给得出。
     *
     * goform 这类只有基础字段的数据源用它当候选集：温度 / CPU / 内存 / 今日流量
     * 在设置页里直接消失，而不是列出来置灰 —— 小尺寸弹窗里四个灰开关比不显示更容易被当成 bug。
     */
    private val CENTER_METRIC_FIELDS_BASIC: List<FieldSpec> =
        CENTER_METRIC_FIELDS.filter { it.requires == null }

    /** 1×1 右上角固定显示的说明，两套变体共用 */
    private const val NOTE_1X1 =
        "1×1 版面只开放中间大字；右上角的信号、网络制式、电量（含充电 ⚡）固定显示。"

    /** 4×2 的完整变体：数据源能给温度/CPU/内存/当日流量时用它 */
    private val VARIANT_4X2_FULL = WidgetVariant(
        layoutId = R.layout.widget_4x2,
        requires = setOf(
            Capability.TEMPERATURE, Capability.CPU, Capability.MEMORY, Capability.DAILY_TRAFFIC
        ),
        fields = listOf(
            FieldSpec(WidgetPrefs.SHOW_MODEL, "设备型号"),
            FieldSpec(WidgetPrefs.SHOW_VERSION, "固件版本", "型号右侧的版本号，无数据时自动隐藏"),
            FieldSpec(WidgetPrefs.SHOW_FLOW, "流量数据", "今日 / 本月流量大数字"),
            FieldSpec(WidgetPrefs.SHOW_SIGNAL, "信号强度", "信号格 + 制式图标 + dBm"),
            FieldSpec(WidgetPrefs.SHOW_BATTERY, "电池电量"),
            FieldSpec(WidgetPrefs.SHOW_CHARGING, "充电状态", "充电时电池图标内显示 ⚡"),
            FieldSpec(WidgetPrefs.SHOW_TEMP, "设备温度", requires = Capability.TEMPERATURE),
            FieldSpec(WidgetPrefs.SHOW_CPU, "CPU 占用", requires = Capability.CPU),
            FieldSpec(WidgetPrefs.SHOW_MEM, "内存占用", requires = Capability.MEMORY),
            FieldSpec(WidgetPrefs.SHOW_TIME, "更新时间"),
            FieldSpec(WidgetPrefs.SHOW_DIVIDER, "流量分隔线"),
        ),
        renderer = WidgetRenderer { c, rv, s -> BaseWifiWidget.performRender(c, rv, s) },
    )

    /**
     * 4×2 的直连变体：拿不到温度/CPU/内存时（goform）改用这套布局。
     *
     * 与其在完整布局里留三个空槽位，不如把版面让给这个数据源真正有的东西：
     * 运营商、制式、RSRP/SINR/频段/PCI。
     */
    private val VARIANT_4X2_COMPACT = WidgetVariant(
        layoutId = R.layout.widget_goform_4x2,
        requires = setOf(Capability.AT_NETWORK),
        fields = listOf(
            FieldSpec(WidgetPrefs.SHOW_MODEL, "设备型号"),
            FieldSpec(WidgetPrefs.SHOW_VERSION, "固件版本", "型号右侧的版本号，无数据时自动隐藏"),
            FieldSpec(WidgetPrefs.SHOW_FLOW, "本月流量"),
            FieldSpec(WidgetPrefs.SHOW_CARRIER, "运营商与制式"),
            FieldSpec(WidgetPrefs.SHOW_SIGNAL, "信号强度", "信号格 + 制式图标"),
            FieldSpec(WidgetPrefs.SHOW_BAND, "信号详情", "RSRP / SINR / 频段 / PCI", requires = Capability.AT_NETWORK),
            // 电量不做能力声明：goform 协议里有 battery_* 系列字段，但具体机型的固件
            // 可能整组裁掉（F50 实测不返回）。这是「固件裁字段」而不是「协议没有」，
            // 所以按值缺失隐藏槽位，开关保留并在副标题说明，别写成一刀切的不支持。
            FieldSpec(WidgetPrefs.SHOW_BATTERY, "电池电量", "部分机型固件不返回，无数据时自动隐藏"),
            FieldSpec(WidgetPrefs.SHOW_CHARGING, "充电状态", "充电时电池图标内显示 ⚡"),
            FieldSpec(WidgetPrefs.SHOW_TIME, "更新时间"),
            FieldSpec(WidgetPrefs.SHOW_DIVIDER, "流量分隔线"),
        ),
        renderer = WidgetRenderer { c, rv, s -> BaseWifiWidget.performRenderGoform(c, rv, s) },
    )

    val all: List<WidgetSpec> = listOf(
        WidgetSpec(
            kind = KIND_4X2,
            provider = WifiWidget4x2::class.java,
            shadowProvider = WifiWidget4x2NoLabel::class.java,
            displayName = "UFI 状态 (4×2)",
            description = "流量 + 信号 + 电量，版面随数据源自动切换",
            enabled = true,
            cellW = 4,
            cellH = 2,
            // 完整版优先，能力不够时自动落到直连版 —— 换数据源不需要用户删组件重加
            variants = listOf(VARIANT_4X2_FULL, VARIANT_4X2_COMPACT),
            themer = WidgetRenderer { c, rv, s -> BaseWifiWidget.applyWidgetTheme(c, rv, s) },
        ),
        WidgetSpec(
            // kind 仍是 "2x1"：SP 里的键都按它存，改名等于丢掉老用户的全部设置。
            // 桌面上声明的是 1×1，展示名与 cellW/cellH 按 1×1 走
            kind = KIND_2X1,
            provider = WifiWidget2x1::class.java,
            shadowProvider = WifiWidget2x1NoLabel::class.java,
            displayName = "UFI 迷你 (1×1)",
            description = "中间大字可选指标，右上角信号/制式/电量",
            enabled = true,
            cellW = 1,
            cellH = 1,
            hasFontSize = true,
            variants = listOf(
                // 完整版：能力齐全的数据源才列出全部 7 个大字候选
                WidgetVariant(
                    layoutId = R.layout.widget_1x1,
                    requires = setOf(
                        Capability.TEMPERATURE, Capability.CPU,
                        Capability.MEMORY, Capability.DAILY_TRAFFIC
                    ),
                    // 1×1 只开放中间大字：右上角三个图标一行排满，关掉任意一个都只是留白，
                    // 换不来别的信息，开关存在的唯一效果是让人以为自己配错了
                    fields = CENTER_METRIC_FIELDS,
                    fixedNote = NOTE_1X1,
                    renderer = WidgetRenderer { c, rv, s -> BaseWifiWidget.performRender2x1(c, rv, s) },
                ),
                // 基础版（goform 等）：布局不变，只把当前数据源给不出的大字候选从设置页里摘掉
                WidgetVariant(
                    layoutId = R.layout.widget_1x1,
                    fields = CENTER_METRIC_FIELDS_BASIC,
                    fixedNote = NOTE_1X1 +
                        "当前数据源只提供流量 / 信号 / 电量，温度、CPU、内存、今日流量不在候选里。",
                    renderer = WidgetRenderer { c, rv, s -> BaseWifiWidget.performRender2x1(c, rv, s) },
                ),
            ),
            themer = WidgetRenderer { c, rv, s -> BaseWifiWidget.applyWidgetTheme2x1(c, rv, s) },
        ),
        WidgetSpec(
            kind = KIND_4X1,
            provider = WifiWidget4x1::class.java,
            shadowProvider = WifiWidget4x1NoLabel::class.java,
            displayName = "UFI 条形 (4×1)",
            description = "型号 + 本月流量 + 信号 + 电量/温度，四栏铺满",
            enabled = true,
            cellW = 4,
            cellH = 1,
            hasFontSize = true,
            variants = listOf(
                WidgetVariant(
                    layoutId = R.layout.widget_4x1,
                    requires = setOf(Capability.TEMPERATURE),
                    fields = listOf(
                        FieldSpec(WidgetPrefs.SHOW_MODEL, "设备型号"),
                        FieldSpec(WidgetPrefs.SHOW_FLOW, "本月流量"),
                        FieldSpec(WidgetPrefs.SHOW_SIGNAL, "信号强度", "信号格 + 制式图标 + dBm"),
                        FieldSpec(WidgetPrefs.SHOW_BATTERY, "电池电量"),
                        FieldSpec(WidgetPrefs.SHOW_CHARGING, "充电状态", "充电时电池图标内显示 ⚡"),
                        FieldSpec(WidgetPrefs.SHOW_TEMP, "设备温度", requires = Capability.TEMPERATURE),
                        FieldSpec(WidgetPrefs.SHOW_TIME, "更新时间", "型号下方的时间戳"),
                    ),
                    renderer = WidgetRenderer { c, rv, s -> BaseWifiWidget.performRender4x1(c, rv, s) },
                ),
                // goform 直连变体：拿不到温度时换布局，把那一格让给频段，
                // 并在流量标签旁补上运营商 —— 这两项 goform 都有
                WidgetVariant(
                    layoutId = R.layout.widget_goform_4x1,
                    requires = setOf(Capability.AT_NETWORK),
                    fields = listOf(
                        FieldSpec(WidgetPrefs.SHOW_MODEL, "设备型号"),
                        FieldSpec(WidgetPrefs.SHOW_FLOW, "本月流量"),
                        FieldSpec(WidgetPrefs.SHOW_SIGNAL, "信号强度", "信号格 + 制式图标 + dBm"),
                        FieldSpec(WidgetPrefs.SHOW_CARRIER, "运营商", "流量标签右侧，无数据时自动隐藏"),
                        FieldSpec(WidgetPrefs.SHOW_BAND, "频段", requires = Capability.AT_NETWORK),
                        FieldSpec(WidgetPrefs.SHOW_BATTERY, "电池电量", "部分机型固件不返回，无数据时自动隐藏"),
                        FieldSpec(WidgetPrefs.SHOW_CHARGING, "充电状态", "充电时电池图标内显示 ⚡"),
                        FieldSpec(WidgetPrefs.SHOW_TIME, "更新时间"),
                    ),
                    fixedNote = "当前数据源没有温度数据，这个形态用频段与运营商替代了温度那一格。",
                    renderer = WidgetRenderer { c, rv, s -> BaseWifiWidget.performRenderGoform4x1(c, rv, s) },
                ),
            ),
            themer = WidgetRenderer { c, rv, s -> BaseWifiWidget.applyWidgetTheme4x1(c, rv, s) },
        ),
        WidgetSpec(
            kind = KIND_2X2,
            provider = WifiWidget2x2::class.java,
            shadowProvider = WifiWidget2x2NoLabel::class.java,
            displayName = "UFI 方块 (2×2)",
            description = "本月流量大字 + 今日流量 + 信号 + 电量/温度",
            enabled = true,
            cellW = 2,
            cellH = 2,
            hasFontSize = true,
            variants = listOf(
                WidgetVariant(
                    layoutId = R.layout.widget_2x2,
                    // 声明依赖这两项：不满足时落到下面的 goform 变体，
                    // 而不是留着「今日流量」和「温度」两个永远空着的槽位
                    requires = setOf(Capability.DAILY_TRAFFIC, Capability.TEMPERATURE),
                    fields = listOf(
                        FieldSpec(WidgetPrefs.SHOW_MODEL, "设备型号"),
                        FieldSpec(WidgetPrefs.SHOW_SIGNAL, "信号强度", "信号格 + 制式图标 + dBm"),
                        FieldSpec(WidgetPrefs.SHOW_FLOW, "本月流量", "组件中间的大数字"),
                        FieldSpec(
                            WidgetPrefs.SHOW_DAILY, "今日流量",
                            requires = Capability.DAILY_TRAFFIC
                        ),
                        FieldSpec(WidgetPrefs.SHOW_BATTERY, "电池电量"),
                        FieldSpec(WidgetPrefs.SHOW_CHARGING, "充电状态", "充电时电池图标内显示 ⚡"),
                        FieldSpec(WidgetPrefs.SHOW_TEMP, "设备温度", requires = Capability.TEMPERATURE),
                        FieldSpec(WidgetPrefs.SHOW_TIME, "更新时间"),
                        FieldSpec(WidgetPrefs.SHOW_DIVIDER, "流量分隔线"),
                    ),
                    renderer = WidgetRenderer { c, rv, s -> BaseWifiWidget.performRender2x2(c, rv, s) },
                ),
                // goform 直连变体：底部两行从「今日流量」「温度」换成运营商/制式/SINR 与频段/RSRP
                WidgetVariant(
                    layoutId = R.layout.widget_goform_2x2,
                    requires = setOf(Capability.AT_NETWORK),
                    fields = listOf(
                        FieldSpec(WidgetPrefs.SHOW_MODEL, "设备型号"),
                        FieldSpec(WidgetPrefs.SHOW_SIGNAL, "信号强度", "信号格 + 制式图标 + RSRP"),
                        FieldSpec(WidgetPrefs.SHOW_FLOW, "本月流量", "组件中间的大数字"),
                        FieldSpec(WidgetPrefs.SHOW_CARRIER, "运营商与制式"),
                        FieldSpec(
                            WidgetPrefs.SHOW_BAND, "信号详情", "频段 + SINR",
                            requires = Capability.AT_NETWORK
                        ),
                        FieldSpec(WidgetPrefs.SHOW_BATTERY, "电池电量", "部分机型固件不返回，无数据时自动隐藏"),
                        FieldSpec(WidgetPrefs.SHOW_CHARGING, "充电状态", "充电时电池图标内显示 ⚡"),
                        FieldSpec(WidgetPrefs.SHOW_TIME, "更新时间"),
                        FieldSpec(WidgetPrefs.SHOW_DIVIDER, "流量分隔线"),
                    ),
                    fixedNote = "当前数据源没有今日流量与温度，这个形态把底部两行换成了运营商与信号详情。",
                    renderer = WidgetRenderer { c, rv, s -> BaseWifiWidget.performRenderGoform2x2(c, rv, s) },
                ),
            ),
            themer = WidgetRenderer { c, rv, s -> BaseWifiWidget.applyWidgetTheme2x2(c, rv, s) },
        ),
    )

    val enabled: List<WidgetSpec> get() = all.filter { it.enabled }

    fun byKind(kind: String): WidgetSpec? = all.firstOrNull { it.kind == kind }

    /** 影子 provider 也要能反查到本体，否则隐藏标签后实例找不到自己的配置 */
    fun byProvider(clazz: Class<*>): WidgetSpec? =
        all.firstOrNull { it.provider == clazz || it.shadowProvider == clazz }

    /** 该形态当前已放置的实例 id（合并主与影子 provider） */
    fun placedIds(context: Context, spec: WidgetSpec): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return spec.providers
            .flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }
            .toIntArray()
    }

    fun isPlaced(context: Context, spec: WidgetSpec): Boolean = placedIds(context, spec).isNotEmpty()

    /** 当前数据源不满足的能力，空集表示完全匹配 */
    fun missingCapabilities(spec: WidgetSpec, caps: DeviceCapabilities): Set<Capability> =
        spec.variantFor(caps).requires.filterNot { it.isSupported(caps) }.toSet()

    /**
     * 实例在桌面上的**实测**尺寸（dp），拿不到时返回 null。
     *
     * 不能用 `res/xml` 里的 minWidth/minHeight 反推：那是尺寸**下限**，而桌面一格
     * 也不是 70dp 方格（常见约 90dp 宽 × 100dp 高），按标称公式算 2×1 得 2.75:1，
     * 真实值接近 1.8:1 —— 取景框和渲染位图都会按错的比例来。
     *
     * 系统给的 options 是每个实例各自的，用户手动缩放过组件也能跟上。
     */
    fun measuredSizeDp(context: Context, appWidgetId: Int?): Pair<Int, Int>? {
        if (appWidgetId == null || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        return try {
            val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
                ?: return null
            // 竖屏下：宽取 MIN_WIDTH、高取 MAX_HEIGHT（横屏是反过来的另一对）
            val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            if (w > 0 && h > 0) w to h else null
        } catch (_: Exception) {
            null
        }
    }

    /** 实例实测宽高比，拿不到实测尺寸时返回 null（调用方回落 [WidgetSpec.nominalAspect]） */
    fun measuredAspect(context: Context, appWidgetId: Int?): Float? {
        val (w, h) = measuredSizeDp(context, appWidgetId) ?: return null
        return w.toFloat() / h
    }

    /**
     * 按宽高比算渲染位图尺寸：长边 ≤640、短边 ≤320。
     *
     * 上限对齐 `WidgetBitmapCache` 的字节预算 —— RemoteViews 单张位图超 1MB 会抛。
     */
    fun bitmapSizeFor(aspect: Float): Pair<Int, Int> {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 2f
        return if (a >= 2f) {
            640 to (640 / a).toInt().coerceAtLeast(1)
        } else {
            (320 * a).toInt().coerceAtLeast(1) to 320
        }
    }
}
