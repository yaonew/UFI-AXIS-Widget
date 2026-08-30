package com.ufi_axis_widget.util.widget

import android.content.Context

/**
 * 小组件配置的三层作用域解析器。
 *
 * 历史上所有小组件配置都是全局单例（裸 key 存在 `wifi_data` 里），一旦出现多种形态的
 * 小组件（4x2 通用版 / goform 专用版 / 2x1 / 4x1）就无法表达「不同组件显示不同内容」。
 * 这里引入作用域前缀，读取时逐级回退：
 *
 * ```
 * 实例层  widget.<kind>.<appWidgetId>.<key>
 * 类型层  widget.<kind>.<key>
 * 旧裸 key（迁移兼容，见 [LEGACY_KEYS]）
 * spec 默认值
 * ```
 *
 * `appWidgetId` 参数从第一天就存在，当前渲染链路统一传 `null`（只用到类型层）；
 * 等实例级配置落地时只需开始传真实 id，不需要再改数据结构。
 *
 * 注意这里直接用 `wifi_data` 这个 SP 文件，与 `SPUtil` 同一份存储 —— 迁移需要读旧裸 key，
 * 分文件会让迁移和回退都变复杂。
 */
object WidgetPrefs {

    private const val SP_NAME = "wifi_data"
    private const val MIGRATION_FLAG = "widget_prefs_migrated_v1"

    /** 显示项 / 字号的规范 key，各形态共用同一套命名，差异由作用域前缀承担 */
    const val SHOW_MODEL = "show_model"
    const val SHOW_VERSION = "show_version"
    const val SHOW_FLOW = "show_flow"
    const val SHOW_DAILY = "show_daily"
    const val SHOW_TEMP = "show_temp"
    const val SHOW_CPU = "show_cpu"
    const val SHOW_MEM = "show_mem"
    const val SHOW_SIGNAL = "show_signal"
    const val SHOW_BATTERY = "show_battery"
    const val SHOW_CHARGING = "show_charging"
    const val SHOW_TIME = "show_time"
    const val SHOW_DIVIDER = "show_divider"
    const val SHOW_BAND = "show_band"
    const val SHOW_CARRIER = "show_carrier"
    const val FONT_SIZE = "font_size"

    /**
     * 「中间大字」候选指标开关。
     *
     * 语义与 SHOW_* 不同：这些不是「显不显示某个槽位」，而是「哪些指标进入大字轮播集合」。
     * 集合里同时只显示一项，双击组件切到下一项，当前位置存在 [CENTER_INDEX]。
     * 候选顺序、文案、能力依赖统一声明在 `WidgetRegistry.CENTER_METRIC_FIELDS`，
     * 渲染层与设置页共用那一份，避免两边顺序对不上导致「双击跳到别的指标」。
     */
    const val CENTER_FLOW = "center_flow"
    const val CENTER_DAILY = "center_daily"
    const val CENTER_SIGNAL = "center_signal"
    const val CENTER_BATTERY = "center_battery"
    const val CENTER_TEMP = "center_temp"
    const val CENTER_CPU = "center_cpu"
    const val CENTER_MEM = "center_mem"

    /** 大字轮播的当前位置。存的是「第几个已启用指标」，集合变小时渲染层取模兜底 */
    const val CENTER_INDEX = "center_index"

    /**
     * 外观（背景 / 配色 / 透明度 / 圆角）独立化的键组。
     *
     * [APPEARANCE_OVERRIDE] 是这一组的总闸：false 时整组回退到 `SPUtil` 的全局外观设置，
     * true 时整组读作用域键。**刻意不做逐项回退** —— 逐项回退会产出「配色是自定义的、
     * 底色却还是全局」这种用户无法推理的中间态；整组切换的代价只是打开开关时要把
     * 当前全局值快照写进作用域（见 `WidgetAppearance.snapshotFromGlobal`），
     * 换来的是「开关一按外观不跳变」。
     *
     * 键名与 `SPUtil` 里的全局键一一对应，review 时可以直接对照。
     */
    const val APPEARANCE_OVERRIDE = "appearance_override"
    const val THEME_MODE = "theme_mode"
    const val FOLLOW_APP_THEME = "follow_app_theme"
    const val COLOR_THEME = "color_theme"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val BG_IMAGE_ENABLED = "bg_image_enabled"
    const val BG_IMAGE_URI = "bg_image_uri"
    const val BG_OPACITY = "bg_opacity"
    const val CLIP_TO_OUTLINE = "clip_to_outline"

    /**
     * 动态取色（实验功能）的作用域键。
     *
     * 与上面那组共用 [APPEARANCE_OVERRIDE] 一个开关：动态取色本质上就是外观的一部分，
     * 再给它单独一个「独立」开关会出现「外观独立但取色跟随全局」这种没人能推理的组合。
     */
    const val DYNAMIC_CONTRAST = "dynamic_contrast"
    const val DYNAMIC_ADVANCED = "dynamic_advanced"
    const val DYN_ADV_LIGHT_BG = "dyn_adv_light_bg"
    const val DYN_ADV_LIGHT_TXT = "dyn_adv_light_txt"
    const val DYN_ADV_DARK_BG = "dyn_adv_dark_bg"
    const val DYN_ADV_DARK_TXT = "dyn_adv_dark_txt"
    const val DYN_ADV_SAT_BOOST = "dyn_adv_sat_boost"
    const val DYNAMIC_SOURCE = "dynamic_source"

    /** 外观键全集，用于「关闭独立外观」时整组清理，避免下次打开读到过期快照 */
    val APPEARANCE_KEYS: List<String> = listOf(
        APPEARANCE_OVERRIDE, THEME_MODE, FOLLOW_APP_THEME, COLOR_THEME, DYNAMIC_COLOR,
        BG_IMAGE_ENABLED, BG_IMAGE_URI, BG_OPACITY, CLIP_TO_OUTLINE,
        DYNAMIC_CONTRAST, DYNAMIC_ADVANCED, DYN_ADV_LIGHT_BG, DYN_ADV_LIGHT_TXT,
        DYN_ADV_DARK_BG, DYN_ADV_DARK_TXT, DYN_ADV_SAT_BOOST, DYNAMIC_SOURCE
    )

    /**
     * 背景图取景矩形，值形如 `<源图路径>|<left>,<top>,<right>,<bottom>`（归一化 0~1）。
     *
     * 每个形态的宽高比不同（4×2 约 2.27:1，2×2 是 1:1），而四套布局的背景层都是
     * `scaleType="fitXY"`（圆角画在 Bitmap 像素上，改成 centerCrop 会把圆角裁掉），
     * 所以同一张图必须按形态各自取一块出来，否则就是被拉伸变形。
     *
     * 存矩形而不是存裁好的副本：副本要跟着「换图/关独立外观/删组件」三件事做生命周期管理，
     * 而矩形只有 4 个数，且**与源图路径绑定** —— 换了图自动失配回落居中裁，不需要任何清理。
     *
     * 刻意**不放进** [APPEARANCE_KEYS]：取景回答的是「这个形态怎么框当前生效的那张图」，
     * 与「这个作用域外观是否独立」正交。放进去会导致关掉独立外观时把取景一起清掉，
     * 而那张图其实还在显示。
     */
    const val BG_CROP = "bg_crop"


    /**
     * 旧裸 key 映射：`kind → (规范 key → 历史 key)`。
     *
     * 4x2 的历史 key 就是不带后缀的裸名，2x1/4x1 带尺寸后缀。goform 版是新组件，无历史包袱。
     * 这张表同时用于一次性迁移和读取时的回退，两者必须用同一份数据，否则会出现
     * 「迁移漏了某个 key，回退也读不到」的静默丢配置。
     */
    private val LEGACY_KEYS: Map<String, Map<String, String>> = mapOf(
        "4x2" to mapOf(
            SHOW_MODEL to "show_model",
            SHOW_FLOW to "show_flow",
            SHOW_TEMP to "show_temp",
            SHOW_CPU to "show_cpu",
            SHOW_MEM to "show_mem",
            SHOW_SIGNAL to "show_signal",
            SHOW_BATTERY to "show_battery",
            SHOW_TIME to "show_time",
            SHOW_DIVIDER to "show_divider",
        ),
        "2x1" to mapOf(
            SHOW_SIGNAL to "show_signal_2x1",
            SHOW_BATTERY to "show_battery_2x1",
            FONT_SIZE to "font_size_2x1",
        ),
        "4x1" to mapOf(
            SHOW_MODEL to "show_model_4x1",
            SHOW_SIGNAL to "show_signal_4x1",
            SHOW_BATTERY to "show_battery_4x1",
            SHOW_TEMP to "show_temp_4x1",
            SHOW_TIME to "show_time_4x1",
            FONT_SIZE to "font_size_4x1",
        ),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private fun kindKey(kind: String, key: String) = "widget.$kind.$key"

    private fun instanceKey(kind: String, id: Int, key: String) = "widget.$kind.$id.$key"

    private fun legacyKey(kind: String, key: String): String? = LEGACY_KEYS[kind]?.get(key)

    // ══════════════════════════════════════════════
    // 读取
    // ══════════════════════════════════════════════

    fun getBool(ctx: Context, kind: String, key: String, default: Boolean, id: Int? = null): Boolean {
        val p = sp(ctx)
        if (id != null) {
            val ik = instanceKey(kind, id, key)
            if (p.contains(ik)) return p.getBoolean(ik, default)
        }
        val kk = kindKey(kind, key)
        if (p.contains(kk)) return p.getBoolean(kk, default)
        legacyKey(kind, key)?.let { if (p.contains(it)) return p.getBoolean(it, default) }
        return default
    }

    fun getInt(ctx: Context, kind: String, key: String, default: Int, id: Int? = null): Int {
        val p = sp(ctx)
        if (id != null) {
            val ik = instanceKey(kind, id, key)
            if (p.contains(ik)) return p.getInt(ik, default)
        }
        val kk = kindKey(kind, key)
        if (p.contains(kk)) return p.getInt(kk, default)
        legacyKey(kind, key)?.let { if (p.contains(it)) return p.getInt(it, default) }
        return default
    }

    /**
     * 字符串项的三层读取。
     *
     * 外观里的主题模式（`light/dark/follow_app`）与背景图 URI 都是字符串，
     * 没有 String 支持就只能把它们编码成 Int，代价是每处读写都要维护一张映射表。
     */
    fun getString(ctx: Context, kind: String, key: String, default: String, id: Int? = null): String {
        val p = sp(ctx)
        if (id != null) {
            val ik = instanceKey(kind, id, key)
            if (p.contains(ik)) return p.getString(ik, default) ?: default
        }
        val kk = kindKey(kind, key)
        if (p.contains(kk)) return p.getString(kk, default) ?: default
        legacyKey(kind, key)?.let { if (p.contains(it)) return p.getString(it, default) ?: default }
        return default
    }

    // ══════════════════════════════════════════════
    // 写入
    // ══════════════════════════════════════════════

    fun setBool(ctx: Context, kind: String, key: String, value: Boolean, id: Int? = null) {
        val target = if (id != null) instanceKey(kind, id, key) else kindKey(kind, key)
        sp(ctx).edit().putBoolean(target, value).apply()
    }

    fun setInt(ctx: Context, kind: String, key: String, value: Int, id: Int? = null) {
        val target = if (id != null) instanceKey(kind, id, key) else kindKey(kind, key)
        sp(ctx).edit().putInt(target, value).apply()
    }

    fun setString(ctx: Context, kind: String, key: String, value: String, id: Int? = null) {
        val target = if (id != null) instanceKey(kind, id, key) else kindKey(kind, key)
        sp(ctx).edit().putString(target, value).apply()
    }

    /**
     * 清掉某作用域的整组外观键。
     *
     * 关闭「独立外观」时必须清而不是只把 [APPEARANCE_OVERRIDE] 置 false：
     * 留着旧快照的话，用户下次再打开开关会看到几个版本前的配色突然回来。
     */
    fun clearAppearance(ctx: Context, kind: String, id: Int? = null) {
        val editor = sp(ctx).edit()
        for (key in APPEARANCE_KEYS) {
            editor.remove(if (id != null) instanceKey(kind, id, key) else kindKey(kind, key))
        }
        editor.apply()
    }

    /** 该实例是否有任何实例级覆盖（设置页用来标注「已单独配置」） */
    fun hasInstanceOverride(ctx: Context, kind: String, id: Int): Boolean {
        val prefix = "widget.$kind.$id."
        return sp(ctx).all.keys.any { it.startsWith(prefix) }
    }

    /**
     * 清理某个实例的所有覆盖项。
     *
     * 必须在 `onDeleted` 里调用：桌面删除组件后 appWidgetId 会被系统回收再分配，
     * 不清理的话新组件会莫名继承上一个组件的配置，且 SP 会无限膨胀。
     */
    fun clearInstance(ctx: Context, kind: String, id: Int) {
        val prefix = "widget.$kind.$id."
        val editor = sp(ctx).edit()
        sp(ctx).all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * 把旧裸 key 一次性复制到类型层。
     *
     * 只在旧 key 存在且类型层还没值时复制，幂等；跑完打标记避免每次启动都扫一遍 SP。
     * 旧 key 不删除 —— 读取侧仍保留回退，等两个版本后再统一清理，
     * 这样降级安装旧版本时用户配置不会丢。
     */
    fun migrateLegacyKeys(ctx: Context) {
        val p = sp(ctx)
        if (p.getBoolean(MIGRATION_FLAG, false)) return
        val editor = p.edit()
        for ((kind, keys) in LEGACY_KEYS) {
            for ((canonical, legacy) in keys) {
                if (!p.contains(legacy)) continue
                val target = kindKey(kind, canonical)
                if (p.contains(target)) continue
                when (canonical) {
                    FONT_SIZE -> editor.putInt(target, p.getInt(legacy, 9))
                    else -> editor.putBoolean(target, p.getBoolean(legacy, true))
                }
            }
        }
        editor.putBoolean(MIGRATION_FLAG, true).apply()
    }
}
