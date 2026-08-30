package com.ufi_axis_widget.service

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ufi_axis_widget.R
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.StatusSummary
import com.ufi_axis_widget.widget.BaseWifiWidget

/**
 * 快捷设置磁贴：下拉通知栏直接看设备状态、点一下立即采集。
 *
 * 与数据源无关：只读 `wifi_data` 这份公共缓存（[SPUtil.saveData] 写入），
 * 不管当前是 UFI-TOOLS 还是 goform，磁贴显示逻辑完全一致。
 *
 * 点击走 [BaseWifiWidget.requestImmediateRefresh]，因此和小组件、Worker
 * 共用同一套采集准入守卫 —— 息屏暂停 / 指定 Wi-Fi 在这里不会被绕开。
 */
class StatusTileService : TileService() {

    companion object {
        private const val TAG = "StatusTile"

        /**
         * 请求系统回调一次 [onStartListening] 以刷新磁贴。
         *
         * 磁贴不可见时系统会直接忽略，所以这里不需要额外判条件；
         * 采集完成后统一从 [BaseWifiWidget.renderAllWidgets] 调一次即可。
         */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(
                    context, ComponentName(context, StatusTileService::class.java)
                )
            } catch (e: Exception) {
                // 部分 ROM 阉掉了 QS 磁贴，抛异常不能影响小组件渲染主流程
                DebugLogger.d(TAG, "requestUpdate failed: ${e.message}")
            }
        }
    }

    /** 刚被拖进快捷设置面板时也要立刻给一次内容，否则会停在系统的「不可用」占位态 */
    override fun onTileAdded() {
        super.onTileAdded()
        render()
    }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val triggered = BaseWifiWidget.requestImmediateRefresh(this, "QS 磁贴")
        if (triggered) {
            // 采集是异步的，先给一个「已受理」的反馈；真实数据等 renderAllWidgets
            // 回调 requestUpdate 时再刷进来
            applyTile(normal = true, subtitle = "正在刷新…")
        } else {
            render()
        }
    }

    /** 依当前缓存与状态刷新磁贴 */
    private fun render() {
        applyTile(
            normal = StatusSummary.isNormal(this),
            subtitle = StatusSummary.line(this)
        )
    }

    /**
     * 写入磁贴内容。
     *
     * 状态**恒为** [Tile.STATE_ACTIVE]：磁贴的功能是「点一下立即采集」，
     * 这件事在离线或暂停时同样可用。用 INACTIVE 会被系统画成灰色，
     * 用户看到的就是「磁贴不可用」——状态信息交给图标和副标题表达就够了。
     */
    private fun applyTile(normal: Boolean, subtitle: String) {
        val tile = qsTile
        if (tile == null) {
            DebugLogger.d(TAG, "applyTile: qsTile 为空（系统尚未绑定磁贴）")
            return
        }
        try {
            tile.state = Tile.STATE_ACTIVE
            tile.icon = Icon.createWithResource(
                this, if (normal) R.drawable.ic_router else R.drawable.ic_router_off
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 数据放标题、完整摘要放副标题：部分 ROM 不渲染第三方磁贴副标题，
                // 只写 subtitle 的话用户会觉得「磁贴只能点，不显示数据」
                tile.label = StatusSummary.tileLabel(this)
                tile.subtitle = subtitle
            } else {
                // Android 9 及以下没有副标题，只能把状态挤进标题里
                tile.label = subtitle
            }
            tile.updateTile()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "applyTile failed: ${e.message}")
        }
    }
}
