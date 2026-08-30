package com.ufi_axis_widget.util.widget

import android.content.Context
import android.content.res.Configuration
import com.ufi_axis_widget.util.SPUtil

/**
 * 背景图取景矩形的编解码。
 *
 * 归一化（0~1）而不是存像素：像素值会被 `inSampleSize` 下采样和「导入时等比压缩」打乱，
 * 归一化后与源图实际分辨率无关。
 *
 * 值里带着源图路径，是自失效机制 —— 换了背景图，旧矩形自动失配回落居中裁，
 * 不需要在「换图 / 关独立外观 / 删组件」三条路径上各写一遍清理。
 */
object BgCrop {

    /** 归一化取景矩形，四个值都在 0~1 之间且 right > left、bottom > top */
    class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** 参与位图缓存 key，精度到 0.001 足够区分用户能手动拖出的差异 */
        fun cacheKey(): String = String.format(
            java.util.Locale.US, "%.3f,%.3f,%.3f,%.3f", left, top, right, bottom
        )
    }

    fun encode(uri: String, r: Rect): String = String.format(
        java.util.Locale.US, "%s|%.5f,%.5f,%.5f,%.5f", uri, r.left, r.top, r.right, r.bottom
    )

    /**
     * 解出与 [expectedUri] 配对的矩形；路径不符、格式损坏、退化成零面积时都返回 null
     * （= 走居中裁兜底），所以调用方不需要额外校验。
     */
    fun decode(stored: String, expectedUri: String): Rect? {
        if (stored.isBlank() || expectedUri.isBlank()) return null
        val sep = stored.lastIndexOf('|')
        if (sep <= 0) return null
        if (stored.substring(0, sep) != expectedUri) return null
        val parts = stored.substring(sep + 1).split(',')
        if (parts.size != 4) return null
        val v = parts.map { it.trim().toFloatOrNull() ?: return null }
        val l = v[0].coerceIn(0f, 1f)
        val t = v[1].coerceIn(0f, 1f)
        val r = v[2].coerceIn(0f, 1f)
        val b = v[3].coerceIn(0f, 1f)
        if (r - l <= 0.001f || b - t <= 0.001f) return null
        return Rect(l, t, r, b)
    }
}

/**
 * 一个小组件作用域最终生效的外观参数。
 *
 * 历史上外观是全纯全局的：渲染层直接读 8 组 `SPUtil` 键，四个形态共用一套底色、
 * 背景图、透明度与圆角。这里把「读哪一层」收敛成一个解析器，渲染层只拿结果，
 * 于是「按形态/按实例独立外观」不需要在四个 themer 里各写一遍分支。
 *
 * 三层优先级与 [WidgetPrefs] 一致（实例 → 类型 → 全局），但**整组切换**：
 * [WidgetPrefs.APPEARANCE_OVERRIDE] 决定这一组走作用域还是走全局，不做逐项回退。
 * 理由见 [WidgetPrefs.APPEARANCE_OVERRIDE] 的说明。
 */
class WidgetAppearance(
    /** 已解析的明暗，渲染层不再关心 follow_app / 系统模式这些中间状态 */
    val isDark: Boolean,
    /** 已解析的配色方案 id（跟随应用主题时就是应用的那个 id） */
    val colorThemeIndex: Int,
    val dynamicColor: Boolean,
    /** 实际生效的背景图，空串表示用纯色兜底（开关关闭时也是空串） */
    val bgImageUri: String,
    val bgOpacity: Int,
    val clipToOutline: Boolean,
    /** 动态取色的调参，只在 [dynamicColor] 为 true 时被读取 */
    val dynamic: DynamicParams,
    /** 本形态对 [bgImageUri] 的取景，null 表示按目标比例居中裁 */
    val bgCrop: BgCrop.Rect? = null,
    /**
     * 背景位图的目标尺寸（px）。默认值是 4×2 那一套，渲染层会按真实形态传入。
     *
     * 放在这里而不是让 `applyChassis` 多收两个参数：外观本来就是「按作用域解析出的
     * 一组渲染参数」，而目标尺寸同样由作用域（形态）决定，放一起才不会出现
     * 「外观按 2×2 解析、位图仍按 4×2 生成」这种半截状态。
     */
    val bmpW: Int = 640,
    val bmpH: Int = 320,
    /**
     * 组件在桌面上的实测尺寸（dp），0 表示未知。
     *
     * 只用于把圆角半径从 dp 折算成**位图像素**：位图会被 `fitXY` 缩放到组件实际大小，
     * 直接按 `radiusDp * density` 在位图里画，缩放后的圆角就不是设定的那个 dp 了
     * （位图比组件大就变小、小就变大）。有了这一对值，半径按 `位图边长 / 组件 dp`
     * 折算，缩放后正好落在设定值上。
     */
    val widgetWDp: Int = 0,
    val widgetHDp: Int = 0,
) {
    /**
     * 动态取色（实验功能）的调参集合。
     *
     * 单独包一层是因为这几项只有开了动态取色才有意义，且要整体参与
     * `ThemeColors` 的 Palette 缓存 key —— 散在外层容易漏掉某一项，
     * 表现是「改了对比度但组件颜色不变」（命中了旧缓存）。
     */
    class DynamicParams(
        val contrast: Int,
        val advanced: Boolean,
        val lightBg: Int,
        val lightTxt: Int,
        val darkBg: Int,
        val darkTxt: Int,
        val satBoost: Int,
        val source: Int,
    ) {
        /** 影响 Palette 输出的全部输入，拼成缓存 key 的一段 */
        fun cacheKey(): String =
            if (advanced) "$source|$contrast|1|$satBoost|$lightBg|$lightTxt|$darkBg|$darkTxt"
            else "$source|$contrast|0"
    }

    companion object {

        /** 主题模式取值，与 `SPUtil.getWidgetTheme` 同一套字符串 */
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"
        const val MODE_FOLLOW_APP = "follow_app"

        /**
         * 作用域标识，用于给每个作用域的背景裁剪图起文件名，也用于设置页展示。
         *
         * 必须包含 kind 与 id：桌面删除组件后 appWidgetId 会被系统回收再分配，
         * 只用 id 命名会让新组件捡到上一个组件的背景图。
         */
        fun scopeTag(kind: String?, appWidgetId: Int? = null): String = when {
            kind == null -> "global"
            appWidgetId != null -> "${kind}_$appWidgetId"
            else -> kind
        }

        /** 该作用域是否启用了独立外观（实例层没写时自动看类型层） */
        fun isOverridden(context: Context, kind: String, appWidgetId: Int? = null): Boolean =
            WidgetPrefs.getBool(
                context, kind, WidgetPrefs.APPEARANCE_OVERRIDE, false, appWidgetId
            )

        /**
         * 渲染层入口：scope 为空（全局预览）时直接给全局外观。
         *
         * [bmpW]/[bmpH] 由调用方按形态传入（见 `WidgetSpec.bgBitmapWidthPx`）。
         * 取景矩形**不**受独立外观开关影响：它描述「这个形态怎么框当前生效的那张图」，
         * 所以走全局外观的组件也能有自己的取景。
         */
        fun of(
            context: Context,
            kind: String?,
            appWidgetId: Int? = null,
            bmpW: Int = 640,
            bmpH: Int = 320,
            widgetWDp: Int = 0,
            widgetHDp: Int = 0,
        ): WidgetAppearance {
            val cropRaw = if (kind == null) "" else WidgetPrefs.getString(
                context, kind, WidgetPrefs.BG_CROP, "", appWidgetId
            )
            if (kind == null || !isOverridden(context, kind, appWidgetId)) {
                return global(context, cropRaw, bmpW, bmpH, widgetWDp, widgetHDp)
            }
            return scoped(context, kind, appWidgetId, cropRaw, bmpW, bmpH, widgetWDp, widgetHDp)
        }

        /** 全局外观：与改造前渲染层逐字节一致，老用户升级后外观不变 */
        fun global(
            context: Context,
            cropRaw: String = "",
            bmpW: Int = 640,
            bmpH: Int = 320,
            widgetWDp: Int = 0,
            widgetHDp: Int = 0,
        ): WidgetAppearance {
            val uri = SPUtil.getAppliedWidgetBgImageUri(context)
            return WidgetAppearance(
                isDark = SPUtil.isWidgetDark(context),
                colorThemeIndex = if (SPUtil.getWidgetFollowAppTheme(context)) {
                    SPUtil.getColorThemeIndex(context)
                } else {
                    SPUtil.getWidgetColorThemeIndex(context)
                },
                dynamicColor = SPUtil.getWidgetDynamicColor(context),
                bgImageUri = uri,
                bgOpacity = SPUtil.getWidgetBgOpacity(context),
                clipToOutline = SPUtil.getWidgetClipToOutline(context),
                dynamic = globalDynamic(context),
                bgCrop = BgCrop.decode(cropRaw, uri),
                bmpW = bmpW,
                bmpH = bmpH,
                widgetWDp = widgetWDp,
                widgetHDp = widgetHDp,
            )
        }

        private fun globalDynamic(context: Context) = DynamicParams(
            contrast = SPUtil.getWidgetDynamicContrast(context),
            advanced = SPUtil.getWidgetDynamicAdvanced(context),
            lightBg = SPUtil.getDynAdvLightBg(context),
            lightTxt = SPUtil.getDynAdvLightTxt(context),
            darkBg = SPUtil.getDynAdvDarkBg(context),
            darkTxt = SPUtil.getDynAdvDarkTxt(context),
            satBoost = SPUtil.getDynAdvSatBoost(context),
            source = SPUtil.getWidgetDynamicColorSource(context),
        )

        private fun scoped(
            context: Context,
            kind: String,
            id: Int?,
            cropRaw: String,
            bmpW: Int,
            bmpH: Int,
            widgetWDp: Int,
            widgetHDp: Int,
        ): WidgetAppearance {
            val follow = WidgetPrefs.getBool(
                context, kind, WidgetPrefs.FOLLOW_APP_THEME,
                SPUtil.getWidgetFollowAppTheme(context), id
            )
            val mode = WidgetPrefs.getString(
                context, kind, WidgetPrefs.THEME_MODE, SPUtil.getWidgetTheme(context), id
            )
            // 背景图两项也以全局值兜底，与其余键的「作用域缺键 = 继承全局」保持一致：
            // 若这里改成 false/""，设置页（走同一套继承规则）和渲染层会给出不同结果
            val bgEnabled = WidgetPrefs.getBool(
                context, kind, WidgetPrefs.BG_IMAGE_ENABLED,
                SPUtil.getWidgetBgImageEnabled(context), id
            )
            val bgUri = WidgetPrefs.getString(
                context, kind, WidgetPrefs.BG_IMAGE_URI, SPUtil.getWidgetBgImageUri(context), id
            )

            return WidgetAppearance(
                isDark = resolveDark(context, follow, mode),
                // 跟随应用主题时配色 id 只能来自应用侧：应用配色本身就是全局概念，
                // 在作用域里再存一份会出现「跟随应用但颜色和应用不一样」
                colorThemeIndex = if (follow) {
                    SPUtil.getColorThemeIndex(context)
                } else {
                    WidgetPrefs.getInt(
                        context, kind, WidgetPrefs.COLOR_THEME,
                        SPUtil.getWidgetColorThemeIndex(context), id
                    )
                },
                dynamicColor = WidgetPrefs.getBool(
                    context, kind, WidgetPrefs.DYNAMIC_COLOR,
                    SPUtil.getWidgetDynamicColor(context), id
                ),
                bgImageUri = if (bgEnabled) bgUri else "",
                bgOpacity = WidgetPrefs.getInt(
                    context, kind, WidgetPrefs.BG_OPACITY, SPUtil.getWidgetBgOpacity(context), id
                ),
                clipToOutline = WidgetPrefs.getBool(
                    context, kind, WidgetPrefs.CLIP_TO_OUTLINE,
                    SPUtil.getWidgetClipToOutline(context), id
                ),
                dynamic = DynamicParams(
                    contrast = WidgetPrefs.getInt(
                        context, kind, WidgetPrefs.DYNAMIC_CONTRAST,
                        SPUtil.getWidgetDynamicContrast(context), id
                    ),
                    advanced = WidgetPrefs.getBool(
                        context, kind, WidgetPrefs.DYNAMIC_ADVANCED,
                        SPUtil.getWidgetDynamicAdvanced(context), id
                    ),
                    lightBg = WidgetPrefs.getInt(
                        context, kind, WidgetPrefs.DYN_ADV_LIGHT_BG,
                        SPUtil.getDynAdvLightBg(context), id
                    ),
                    lightTxt = WidgetPrefs.getInt(
                        context, kind, WidgetPrefs.DYN_ADV_LIGHT_TXT,
                        SPUtil.getDynAdvLightTxt(context), id
                    ),
                    darkBg = WidgetPrefs.getInt(
                        context, kind, WidgetPrefs.DYN_ADV_DARK_BG,
                        SPUtil.getDynAdvDarkBg(context), id
                    ),
                    darkTxt = WidgetPrefs.getInt(
                        context, kind, WidgetPrefs.DYN_ADV_DARK_TXT,
                        SPUtil.getDynAdvDarkTxt(context), id
                    ),
                    satBoost = WidgetPrefs.getInt(
                        context, kind, WidgetPrefs.DYN_ADV_SAT_BOOST,
                        SPUtil.getDynAdvSatBoost(context), id
                    ),
                    source = WidgetPrefs.getInt(
                        context, kind, WidgetPrefs.DYNAMIC_SOURCE,
                        SPUtil.getWidgetDynamicColorSource(context), id
                    ),
                ),
                // 取景要按「实际生效的那张图」配对：作用域关了背景开关时 bgImageUri 是空串，
                // 此时 decode 直接返回 null，不会把旧矩形套到别的图上
                bgCrop = BgCrop.decode(cropRaw, if (bgEnabled) bgUri else ""),
                bmpW = bmpW,
                bmpH = bmpH,
                widgetWDp = widgetWDp,
                widgetHDp = widgetHDp,
            )
        }

        /**
         * 明暗判定，与 [SPUtil.isWidgetDark] 保持同一套优先级。
         *
         * 不复用那个函数是因为它只读全局键；这里的 follow/mode 来自作用域。
         * 两处逻辑必须同步改 —— 不一致的表现是「同一套设置，全局和独立作用域一个亮一个暗」。
         */
        private fun resolveDark(context: Context, follow: Boolean, mode: String): Boolean =
            when (mode) {
                MODE_LIGHT -> false
                MODE_DARK -> true
                // follow_app：跟随开关开着才看应用主题，否则直接认系统
                else -> if (follow) {
                    when (SPUtil.getAppTheme(context)) {
                        MODE_LIGHT -> false
                        MODE_DARK -> true
                        else -> isSystemNight(context)
                    }
                } else {
                    isSystemNight(context)
                }
            }

        private fun isSystemNight(context: Context): Boolean {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return night == Configuration.UI_MODE_NIGHT_YES
        }

        /**
         * 打开「独立外观」时把当前全局值快照进作用域。
         *
         * 不快照的话开关一按外观就会跳到一堆默认值上，用户会以为是 bug；
         * 快照之后第一眼看到的与之前完全一样，改哪项是哪项。
         */
        fun snapshotFromGlobal(context: Context, kind: String, id: Int? = null) {
            WidgetPrefs.setString(
                context, kind, WidgetPrefs.THEME_MODE, SPUtil.getWidgetTheme(context), id
            )
            WidgetPrefs.setBool(
                context, kind, WidgetPrefs.FOLLOW_APP_THEME,
                SPUtil.getWidgetFollowAppTheme(context), id
            )
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.COLOR_THEME,
                SPUtil.getWidgetColorThemeIndex(context), id
            )
            WidgetPrefs.setBool(
                context, kind, WidgetPrefs.DYNAMIC_COLOR, SPUtil.getWidgetDynamicColor(context), id
            )
            // 背景图不复制文件，只复制「开关 + 路径」：同一张图被两个作用域引用是允许的，
            // 真正独立发生在用户为这个作用域重新选图时（每次选图都拷成带时间戳的新文件）
            WidgetPrefs.setBool(
                context, kind, WidgetPrefs.BG_IMAGE_ENABLED,
                SPUtil.getWidgetBgImageEnabled(context), id
            )
            WidgetPrefs.setString(
                context, kind, WidgetPrefs.BG_IMAGE_URI, SPUtil.getWidgetBgImageUri(context), id
            )
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.BG_OPACITY, SPUtil.getWidgetBgOpacity(context), id
            )
            WidgetPrefs.setBool(
                context, kind, WidgetPrefs.CLIP_TO_OUTLINE,
                SPUtil.getWidgetClipToOutline(context), id
            )
            // 动态取色的调参一起快照：它们与配色同属外观，共用同一个总开关
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.DYNAMIC_CONTRAST,
                SPUtil.getWidgetDynamicContrast(context), id
            )
            WidgetPrefs.setBool(
                context, kind, WidgetPrefs.DYNAMIC_ADVANCED,
                SPUtil.getWidgetDynamicAdvanced(context), id
            )
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.DYN_ADV_LIGHT_BG, SPUtil.getDynAdvLightBg(context), id
            )
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.DYN_ADV_LIGHT_TXT, SPUtil.getDynAdvLightTxt(context), id
            )
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.DYN_ADV_DARK_BG, SPUtil.getDynAdvDarkBg(context), id
            )
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.DYN_ADV_DARK_TXT, SPUtil.getDynAdvDarkTxt(context), id
            )
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.DYN_ADV_SAT_BOOST, SPUtil.getDynAdvSatBoost(context), id
            )
            WidgetPrefs.setInt(
                context, kind, WidgetPrefs.DYNAMIC_SOURCE,
                SPUtil.getWidgetDynamicColorSource(context), id
            )
            WidgetPrefs.setBool(context, kind, WidgetPrefs.APPEARANCE_OVERRIDE, true, id)
        }
    }
}
