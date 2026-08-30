package com.ufi_axis_widget.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.ufi_axis_widget.widget.WidgetRegistry
import com.ufi_axis_widget.widget.WidgetSpec

/**
 * 小组件名称（桌面标签）隐藏/显示。
 *
 * 桌面只从 receiver 的 `android:label` 读组件名，而 label 是 Manifest 里写死的、
 * 运行时改不了。所以每个形态都注册了两个 receiver：
 *
 * - 主 receiver：`android:label="UFI xxx"`
 * - 影子 receiver：`android:label`（零宽空格，视觉上没有名字）
 *
 * 两者共用同一份布局与渲染逻辑（影子类直接继承主类），靠 [setComponentEnabledSetting]
 * 互斥切换 enabled，顺带触发桌面重新扫描组件元数据。
 *
 * **按形态独立切换**：禁用一个 provider receiver 会让桌面把该 provider 名下已放置的实例
 * 全部判为失效并移除。早期实现是一次遍历所有形态，于是「在 2×2 作用域下开隐藏名称」
 * 会把 1×1 / 4×1 / 4×2 的实例一起清掉。现在 [apply] 只动传入的那一个形态，
 * 想整组切的调用方显式用 [applyAll]。
 *
 * 状态**以 PackageManager 为准**，不再另存 SP 标志：组件状态是这个功能真正的开关，
 * 多存一份只会在「用户从系统设置里手动改过组件状态」或迁移遗漏时和界面显示打架。
 *
 * 注意：切换后被停用的那个 provider 名下的实例一定会消失，这是方案本身的代价，
 * 调用方必须在 UI 上讲清楚。
 */
object WidgetLabelToggle {

    private const val TAG = "WidgetLabelToggle"

    /** 声明了影子 receiver 的形态才支持隐藏名称 */
    val togglableSpecs: List<WidgetSpec>
        get() = WidgetRegistry.enabled.filter { it.shadowProvider != null }

    /**
     * 切换**单个**形态的标签显示/隐藏状态。
     *
     * @param hideLabel true = 隐藏名称（启用影子、禁用主体），false = 反之
     */
    fun apply(context: Context, spec: WidgetSpec, hideLabel: Boolean) {
        val shadowClass = spec.shadowProvider ?: return
        val pm = context.packageManager
        val pkg = context.packageName
        val main = ComponentName(pkg, spec.provider.name)
        val shadow = ComponentName(pkg, shadowClass.name)
        // 先启用要用的那个再禁用另一个：反过来会出现「两个都是 disabled」的瞬间，
        // 有些桌面正好在这时扫描就会把已放置的实例判成失效
        if (hideLabel) {
            setComponentState(pm, shadow, true)
            setComponentState(pm, main, false)
        } else {
            setComponentState(pm, main, true)
            setComponentState(pm, shadow, false)
        }
        DebugLogger.d(TAG, "hideLabel=$hideLabel applied to ${spec.kind}")
    }

    /** 整组切换。只有「全局默认」作用域该走这里 —— 所有尺寸的已放置实例都会被移除 */
    fun applyAll(context: Context, hideLabel: Boolean) {
        togglableSpecs.forEach { apply(context, it, hideLabel) }
    }

    /** 该形态当前是否处于隐藏名称状态（即影子 receiver 生效中） */
    fun isShadowActive(context: Context, spec: WidgetSpec): Boolean {
        val shadow = spec.shadowProvider ?: return false
        return try {
            val state = context.packageManager.getComponentEnabledSetting(
                ComponentName(context.packageName, shadow.name)
            )
            // 影子 receiver 在 Manifest 里是 enabled="false"，所以 DEFAULT 等于「没隐藏」
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 是否**所有**可切换形态都隐藏了名称。
     *
     * 全局作用域下开关只有开/关两态，混合状态（一部分隐藏）按「关」显示：
     * 这样用户拨一下就能把全部对齐到隐藏，而不是先拨关再拨开两步。
     */
    fun isShadowActiveForAll(context: Context): Boolean =
        togglableSpecs.isNotEmpty() && togglableSpecs.all { isShadowActive(context, it) }

    /**
     * 该形态当前生效的 provider 类（主体或影子）。
     *
     * 钉选组件、指定渲染目标都要用它：隐藏名称时主 receiver 是 disabled 的，
     * 拿它去 requestPinAppWidget / updateAppWidget 都不会成功。
     */
    fun activeProviderOf(context: Context, spec: WidgetSpec): Class<*> {
        val shadow = spec.shadowProvider ?: return spec.provider
        return if (isShadowActive(context, spec)) shadow else spec.provider
    }

    private fun setComponentState(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        try {
            pm.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "setComponentEnabled(${component.className}, $enabled) failed: ${e.message}")
        }
    }
}
