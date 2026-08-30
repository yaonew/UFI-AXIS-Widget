package com.ufi_axis_widget.util.source

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/*
 * 局域网数据源共用的 HTTP 脚手架。
 *
 * GoformDataSource 与 UfiAxisDataSource 原先各自 `OkHttpClient.Builder()` 一份
 * 完全相同的超时配置，各自维护一套连接池与线程池 —— 白占内存，改超时还得改两处。
 */

/**
 * 设备直连专用 client。
 *
 * 刻意不挂 CookieJar：goform 的 session、UFI-AXIS 的 token 都是手工管理的，
 * 共享 cookie 池会与 [com.ufi_axis_widget.util.NetUtil] 的抓取链路互相干扰。
 * 超时压得比公网请求短：对面就在同一个局域网，连不上就是设备不在，早失败早重试。
 */
val deviceHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
}

/**
 * 登录 / 配对失败后是否还在退避窗口内。
 *
 * 线性 5s 递增、上限 60s。退避的意义不在于减轻设备负载（局域网请求很轻），
 * 而是别在「密码填错」这类必然失败上反复撞设备侧的密码锁定
 * —— UFI-AXIS core 是 15 分钟 5 次，撞满了连正确密码也要等。
 *
 * @param lastFailureAt 上次失败的 [System.currentTimeMillis]
 */
fun inLoginBackoff(consecutiveFailures: Int, lastFailureAt: Long, now: Long): Boolean {
    if (consecutiveFailures <= 0) return false
    val backoffMs = minOf(consecutiveFailures * 5_000L, 60_000L)
    return now - lastFailureAt < backoffMs
}
