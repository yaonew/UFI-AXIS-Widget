package com.ufi_axis_widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.ufi_axis_widget.util.WidgetLabelToggle
import com.ufi_axis_widget.widget.WidgetRegistry
import com.ufi_axis_widget.util.ToastStyle
import com.ufi_axis_widget.util.ToastUtil

class AddWidgetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val widgetSize = intent.getStringExtra("widget_size") ?: WidgetRegistry.KIND_4X2
        Log.d("AddWidget", "Requesting size: $widgetSize")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pinned = tryPinWidget(widgetSize)
            if (!pinned) {
                tryPinShortcut(widgetSize)
            }
        }

        window.decorView.postDelayed({ finish() }, 800)
    }

    /** 尝试钉选小组件（现代 API，无需权限） */
    private fun tryPinWidget(widgetSize: String): Boolean {
        return try {
            val appWidgetManager = getSystemService(AppWidgetManager::class.java)
            if (appWidgetManager?.isRequestPinAppWidgetSupported != true) return false

            val spec = WidgetRegistry.byKind(widgetSize)?.takeIf { it.enabled }
                ?: WidgetRegistry.byKind(WidgetRegistry.KIND_4X2)!!
            // 隐藏桌面标签时主 receiver 是 disabled 的，钉选一个被禁用的组件不会成功，
            // 必须换成当前生效的那个（影子）。现在每个形态都有影子，不再按 kind 特判
            val widgetClass = WidgetLabelToggle.activeProviderOf(this, spec)
            val intent = Intent(this, WidgetAddedReceiver::class.java).apply {
                putExtra("widget_size", spec.kind)
            }
            val callback = PendingIntent.getBroadcast(
                this, spec.kind.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            appWidgetManager.requestPinAppWidget(ComponentName(this, widgetClass), null, callback)
            ToastUtil.showDropToast(this, ToastStyle.INFO, "正在请求添加「${spec.displayName}」")
            true
        } catch (e: Exception) {
            Log.e("AddWidget", "Pin widget failed", e)
            false
        }
    }

    /** 尝试钉选桌面快捷方式（ShortcutManager 现代 API，无需权限） */
    private fun tryPinShortcut(widgetSize: String): Boolean {
        return try {
            val shortcutManager = getSystemService(ShortcutManager::class.java)
            if (shortcutManager?.isRequestPinShortcutSupported != true) return false

            val shortcutInfo = ShortcutInfo.Builder(this, "add_widget_$widgetSize")
                .setShortLabel("UFI 小组件")
                .setLongLabel("添加 UFI 工具小组件")
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                })
                .build()

            shortcutManager.requestPinShortcut(shortcutInfo, null)
            ToastUtil.showDropToast(this, ToastStyle.INFO, "正在请求添加桌面快捷方式")
            true
        } catch (e: Exception) {
            Log.e("AddWidget", "Pin shortcut failed", e)
            false
        }
    }
}
