package com.ufi_axis_widget

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ufi_axis_widget.util.AnimationUtil
import com.ufi_axis_widget.util.BackgroundUtil
import com.ufi_axis_widget.util.ThemeUtil

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        // ThemeUtil.applyTheme 内部已调用 BackgroundUtil.initActivity → applyWindowBackground
        // 此处不再重复调用，避免背景位图被解码两次
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)

        // 返回
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_back)) { finish() }

        // ===== 通知管理 → NotificationSettingsActivity（第一位） =====
        findViewById<android.view.View>(R.id.card_notification).setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        // ===== 软件设置 → AppSettingsActivity =====
        findViewById<android.view.View>(R.id.card_app_settings).setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }

        // ===== 配置修改 → ConfigModifyActivity =====
        findViewById<android.view.View>(R.id.card_config_modify).setOnClickListener {
            startActivity(Intent(this, ConfigModifyActivity::class.java))
        }

        // ===== 小组件设置 → WidgetSettingsActivity =====
        findViewById<android.view.View>(R.id.card_widget_settings).setOnClickListener {
            startActivity(Intent(this, WidgetSettingsActivity::class.java))
        }

        // ===== 实验功能 → ExperimentalFeaturesActivity =====
        findViewById<android.view.View>(R.id.card_experimental).setOnClickListener {
            startActivity(Intent(this, ExperimentalFeaturesActivity::class.java))
        }

        // ===== 关于 → AboutActivity =====
        findViewById<android.view.View>(R.id.card_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // ===== 流量记录 → TrafficHistoryActivity =====
        // 不再按 dailyTraffic 置灰：没有日流量字段的数据源（goform）现在由
        // TrafficRecordManager 用月累计跨天做差推导，记录功能对所有数据源都可用
        findViewById<android.view.View>(R.id.card_traffic_history).setOnClickListener {
            startActivity(Intent(this, TrafficHistoryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // ThemeUtil.applyTheme 内部已调用 BackgroundUtil.initActivity → applyWindowBackground
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.SETTINGS_LIST)
    }
}
