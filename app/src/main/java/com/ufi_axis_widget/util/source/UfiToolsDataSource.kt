package com.ufi_axis_widget.util.source

import android.content.Context
import com.ufi_axis_widget.util.DataSourceType
import com.ufi_axis_widget.util.DeviceCapabilities
import com.ufi_axis_widget.util.NotificationBaseInfo
import com.ufi_axis_widget.util.SPUtil
import com.ufi_axis_widget.util.WifiCrawlUfiTools
import com.ufi_axis_widget.util.WifiEntity

/**
 * UFI-TOOLS 数据源。
 *
 * 薄适配器：全部逻辑仍在 [WifiCrawlUfiTools] 里，
 * 这里只是把它接到 [DeviceDataSource] 契约上。
 *
 * 之所以不拆 [WifiCrawlUfiTools]：它有 1300 余行，其中 AT 响应解析涉及大量 3GPP
 * 换算与正则，且项目目前没有单元测试兜底，纯搬家的收益远小于搬错的风险。
 * 保持原样 + 薄适配是当前信息下风险最低的做法。
 */
object UfiToolsDataSource : DeviceDataSource {

    override val type = DataSourceType.UFI_TOOLS

    override val capabilities = DeviceCapabilities.UFI_TOOLS

    override val lastError: String
        get() = WifiCrawlUfiTools.lastError

    override val lastRawResponse: String
        get() = WifiCrawlUfiTools.lastRawResponse

    override suspend fun getWifiData(context: Context, quickStart: Boolean): WifiEntity? =
        WifiCrawlUfiTools.getWifiData(context, quickStart)

    override suspend fun fetchNotificationBaseInfo(context: Context): NotificationBaseInfo? =
        WifiCrawlUfiTools.fetchNotificationBaseInfo(context)

    override suspend fun probeProtocol(context: Context): String? =
        WifiCrawlUfiTools.probeProtocol(context)

    /** UFI-TOOLS 服务端口就是设备地址里配置的那个（默认 2333） */
    override fun probePort(context: Context): Int = SPUtil.getDevicePortInt(context)
}
