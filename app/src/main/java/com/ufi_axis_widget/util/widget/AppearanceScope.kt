package com.ufi_axis_widget.util.widget

import android.app.Dialog
import android.content.Context
import com.ufi_axis_widget.R
import com.ufi_axis_widget.util.CommonDialogHelper
import com.ufi_axis_widget.widget.WidgetRegistry

/**
 * 「当前正在设置哪个组件的外观」这件事的状态与读写入口。
 *
 * 外观相关的设置分散在两个页面（小组件设置 / 实验功能 → 动态取色），两边都要：
 * 选作用域、显示作用域名字、按作用域读写键。抄两份的直接后果是某一页漏判
 * [WidgetPrefs.APPEARANCE_OVERRIDE]，表现成「在这一页改的永远是全局」。
 *
 * 只有两种作用域：全局默认（[kind] 为 null）和桌面上某个具体组件。
 * 形态层（`widget.<kind>.*`）仍是 [WidgetAppearance] 的回退层，但不在 UI 里暴露。
 */
class AppearanceScope {

    /** null = 全局默认 */
    var kind: String? = null
        private set

    /** 非空表示正在设置某个具体的桌面实例 */
    var appWidgetId: Int? = null
        private set

    /** 自动开启独立外观时的回调，供页面刷新开关状态并提示用户 */
    var onAutoOverride: (() -> Unit)? = null

    fun select(kind: String?, appWidgetId: Int?) {
        this.kind = kind
        this.appWidgetId = appWidgetId
    }

    /** 当前作用域是否读写独立外观键。全局作用域恒为 false，即永远走 SPUtil */
    fun isOverridden(context: Context): Boolean =
        kind?.let { WidgetAppearance.isOverridden(context, it, appWidgetId) } ?: false

    /**
     * 已添加到桌面的组件：(kind, appWidgetId, 显示名)。
     *
     * 同一形态放了多个才在名字后面缀 `#id`：只有一个时缀上去纯属噪音。
     */
    fun placed(context: Context): List<Triple<String, Int, String>> {
        val out = mutableListOf<Triple<String, Int, String>>()
        for (spec in WidgetRegistry.enabled) {
            val ids = WidgetRegistry.placedIds(context, spec)
            for (id in ids) {
                out.add(
                    Triple(
                        spec.kind, id,
                        if (ids.size > 1) "${spec.displayName} #$id" else spec.displayName
                    )
                )
            }
        }
        return out
    }

    /** 作用域显示名，用于「正在设置」副标题、分区标题与弹窗标题 */
    fun name(context: Context): String {
        val k = kind ?: return "全局默认"
        val id = appWidgetId
        if (id != null) {
            placed(context).firstOrNull { it.first == k && it.second == id }?.let { return it.third }
        }
        return WidgetRegistry.byKind(k)?.displayName ?: k
    }

    /** 当前选中的组件是否还在桌面上；已被删除时回落到全局并返回 true */
    fun resetIfGone(context: Context): Boolean {
        val id = appWidgetId ?: return false
        if (placed(context).any { it.first == kind && it.second == id }) return false
        select(null, null)
        return true
    }

    // ── 按作用域读写。全局值同时充当作用域键缺失时的默认值，表现为「继承全局」──

    fun bool(context: Context, key: String, global: (Context) -> Boolean): Boolean {
        val k = kind
        return if (k != null && isOverridden(context)) {
            WidgetPrefs.getBool(context, k, key, global(context), appWidgetId)
        } else global(context)
    }

    fun setBool(context: Context, key: String, value: Boolean, global: (Context, Boolean) -> Unit) {
        val k = kind
        if (k != null && ensureOverridden(context)) {
            WidgetPrefs.setBool(context, k, key, value, appWidgetId)
        } else global(context, value)
    }

    fun int(context: Context, key: String, global: (Context) -> Int): Int {
        val k = kind
        return if (k != null && isOverridden(context)) {
            WidgetPrefs.getInt(context, k, key, global(context), appWidgetId)
        } else global(context)
    }

    fun setInt(context: Context, key: String, value: Int, global: (Context, Int) -> Unit) {
        val k = kind
        if (k != null && ensureOverridden(context)) {
            WidgetPrefs.setInt(context, k, key, value, appWidgetId)
        } else global(context, value)
    }

    fun str(context: Context, key: String, global: (Context) -> String): String {
        val k = kind
        return if (k != null && isOverridden(context)) {
            WidgetPrefs.getString(context, k, key, global(context), appWidgetId)
        } else global(context)
    }

    fun setStr(context: Context, key: String, value: String, global: (Context, String) -> Unit) {
        val k = kind
        if (k != null && ensureOverridden(context)) {
            WidgetPrefs.setString(context, k, key, value, appWidgetId)
        } else global(context, value)
    }

    /**
     * 写入前确保当前作用域是独立的，返回是否应该写作用域键。
     *
     * 用户在「设置哪个组件」里挑中了某个实例，意图已经很明确 —— 此时还要求他
     * 先去拨「单独设置外观」开关才生效，实际表现是「改了却全局都变」，
     * 是个必然踩的坑。所以第一次写入时自动开启（内部会把当前全局值快照进作用域，
     * 所以只有这一个键跟着变，其余外观保持原样）。
     */
    private fun ensureOverridden(context: Context): Boolean {
        val k = kind ?: return false
        if (isOverridden(context)) return true
        WidgetAppearance.snapshotFromGlobal(context, k, appWidgetId)
        onAutoOverride?.invoke()
        return true
    }

    /** 打开/关闭当前作用域的「单独设置」。关闭时整组清理，避免下次打开读到过期快照 */
    fun setOverride(context: Context, enabled: Boolean) {
        val k = kind ?: return
        if (enabled) WidgetAppearance.snapshotFromGlobal(context, k, appWidgetId)
        else WidgetPrefs.clearAppearance(context, k, appWidgetId)
    }

    /**
     * 作用域选择弹窗：双栏网格，与「软件设置 → 主题配色」同一套观感。
     *
     * 选项都是短标签，单栏排下来一屏放不了几项还得滚动。
     * 只列已放置的组件：没添加到桌面的形态改了也看不见效果。
     */
    fun showPicker(context: Context, onPicked: () -> Unit): Dialog {
        val items = placed(context)
        return CommonDialogHelper.showSelectionDialog(
            context = context,
            title = "设置哪个组件",
            iconRes = R.drawable.ic_widget_large,
            onFill = { content, dialog ->
                val grid = CommonDialogHelper.addTwoColumnGrid(context, content)

                grid.addView(
                    CommonDialogHelper.asGridCell(
                        context,
                        CommonDialogHelper.buildOptionView(
                            context = context,
                            label = "全局默认",
                            subtitle = "所有组件通用",
                            selected = kind == null,
                            onClick = {
                                dialog.dismiss()
                                select(null, null)
                                onPicked()
                            }
                        )
                    )
                )
                for ((k, id, label) in items) {
                    grid.addView(
                        CommonDialogHelper.asGridCell(
                            context,
                            CommonDialogHelper.buildOptionView(
                                context = context,
                                label = label,
                                subtitle = if (WidgetAppearance.isOverridden(context, k, id)) "已单独设置" else "跟随全局",
                                selected = kind == k && appWidgetId == id,
                                onClick = {
                                    dialog.dismiss()
                                    select(k, id)
                                    onPicked()
                                }
                            )
                        )
                    )
                }
            }
        )
    }
}
