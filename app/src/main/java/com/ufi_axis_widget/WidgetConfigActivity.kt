package com.ufi_axis_widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ufi_axis_widget.util.widget.WidgetFieldsDialog
import com.ufi_axis_widget.widget.BaseWifiWidget
import com.ufi_axis_widget.widget.WidgetRegistry

/**
 * 单个已放置小组件的配置入口（实例层）。
 *
 * 由桌面在添加组件、或长按组件选择「重新配置」时启动（provider xml 的 `android:configure`）。
 * 写入的是 `widget.<kind>.<appWidgetId>.*`，只影响这一个组件；设置页里改的是类型层，
 * 对该形态的所有实例生效。
 *
 * 关键点：`setResult(RESULT_OK)` 必须在 onCreate 里就设好。配置 Activity 若以
 * RESULT_CANCELED 结束，桌面会直接放弃添加这个组件 —— 用户点了「取消」只是不想改配置，
 * 不是不想要这个组件。
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // 先把结果设成成功，之后无论用户确定还是取消，组件都会被正常添加
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)

        // 归属校验：本 Activity 是 exported（桌面必须能拉起它），只校验 id 有效等于任何应用
        // 都能传一个别人的 appWidgetId 进来，把配置写进我们的 widget.<kind>.<id>.* 键里。
        // 系统正常的添加/重配流程给的 id 一定指向本应用的 provider，所以这条不影响正常路径。
        if (info?.provider?.packageName != packageName) {
            finish()
            return
        }

        // Android 12 以下没有 widgetFeatures：provider xml 里的 configuration_optional 会被忽略，
        // 配置页退化成「添加组件必经步骤」，每加一次组件都要先过一遍显示项弹窗；
        // 同版本也没有「长按重新配置」入口，所以这里只会是添加流程 —— 直接放行，
        // 让用户走应用内的小组件设置去改显示项。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            finish()
            return
        }

        val spec = info.provider?.className
            ?.let { runCatching { Class.forName(it) }.getOrNull() }
            ?.let { WidgetRegistry.byProvider(it) }

        if (spec == null) {
            // 拿不到形态声明就没法生成开关列表，直接放行，用组件的默认配置
            finish()
            return
        }

        WidgetFieldsDialog.show(
            activity = this,
            spec = spec,
            appWidgetId = appWidgetId,
            onSaved = { BaseWifiWidget.renderAllWidgets(this, force = true) },
            onDismiss = { finish() }
        )
    }
}
