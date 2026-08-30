package com.ufi_axis_widget.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Log
import com.ufi_axis_widget.util.widget.BgCrop

/**
 * 小组件背景 Bitmap 缓存，减少重复分配（~820KB/张）带来的 GC 压力。
 *
 * 实现 [ComponentCallbacks2] 监听系统内存压力，自动清理缓存防止 OOM。
 * 需在 [Application.onCreate] 中调用 [register] 注册回调。
 *
 * 两类缓存，都是按 key 的小 LRU：
 * - solidCache：纯色圆角 Bitmap，key = `颜色|圆角`
 * - imageCache：自定义背景图 Bitmap，key = `URI|圆角`
 *
 * 为什么不是单槽：外观支持按形态/实例独立后，一轮渲染里 4 种形态的底色与背景图
 * 可以各不相同。单槽缓存在这种场景下每个形态都未命中，一轮渲染要重复
 * 「建 640×320 ARGB → 圆角再建一张 → 回收原图」四次（约 6.5MB 瞬时分配），
 * 周期性后台刷新下这笔开销直接体现在耗电上。
 */
object WidgetBitmapCache : ComponentCallbacks2 {

    private const val TAG = "WidgetBitmapCache"

    /**
     * 位图尺寸上限的兜底值（4×2 那一套）。
     *
     * 真实尺寸由调用方按形态传入（`WidgetSpec.bgBitmapWidthPx`）：各形态比例不同，
     * 统一按 640×320 生成再靠 `fitXY` 拉伸，窄形态上就是变形 + 圆角被拉成椭圆。
     */
    private const val TARGET_W = 640
    private const val TARGET_H = 320

    /** 条数上限：按当前形态数（4 种）取整，再多也只是更少见的实例级独立外观 */
    private const val MAX_SOLID_ENTRIES = 4
    private const val MAX_IMAGE_ENTRIES = 4

    /**
     * 两个缓存合计的字节预算。
     *
     * 条数上限之外还要卡字节：背景图按形态尺寸生成，最大的一档（640×282）约 722KB，
     * 纯色图固定 820KB，只按条数算最坏能到 7MB —— 对一个后台刷新的进程偏大。
     */
    private const val BYTE_BUDGET = 6 * 1024 * 1024

    // accessOrder = true：get 也算一次访问，逐出的才是真正最久没用到的那张
    private val solidCache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private val imageCache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)

    // ── 系统内存压力回调注册状态 ──
    private var isRegistered = false


    /**
     * 注册 [ComponentCallbacks2] 内存压力回调，建议在 [Application.onCreate] 中调用。
     * 可重复调用（第二次及以后自动跳过）。
     */
    @Synchronized
    fun register(context: Context) {
        if (isRegistered) return
        context.applicationContext.registerComponentCallbacks(this)
        isRegistered = true
        Log.d(TAG, "ComponentCallbacks2 registered")
    }

    /**
     * 取消注册内存压力回调。通常在应用销毁时调用，但单例缓存随进程生命周期，
     * 多数场景下不需要主动 unregister。
     */
    @Synchronized
    fun unregister(context: Context) {
        if (!isRegistered) return
        context.applicationContext.unregisterComponentCallbacks(this)
        isRegistered = false
        Log.d(TAG, "ComponentCallbacks2 unregistered")
    }

    // ── ComponentCallbacks2 实现 ──

    // 必须与其他缓存入口共用同一把锁：这个回调在主线程执行，而 renderAllWidgets 走后台线程，
    // TRIM_MEMORY_UI_HIDDEN 恰好在应用退到后台时触发 —— 也正是后台刷新在跑的时刻。
    // 不加锁会与 getOrCreate* 并发读写同一个 LinkedHashMap，或把刚返回给渲染线程的位图 recycle 掉
    @Synchronized
    override fun onTrimMemory(level: Int) {
        when {
            // 系统内存严重不足，清空所有缓存
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.d(TAG, "onTrimMemory(MODERATE=$level): evicting all caches")
                evictAll()
            }
            // 进入后台列表/后台进程列表，释放较重的 image 缓存
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Log.d(TAG, "onTrimMemory(BACKGROUND=$level): evicting image cache")
                recycleAll(imageCache)
            }
            // UI 不可见，轻量释放
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d(TAG, "onTrimMemory(UI_HIDDEN=$level): evicting image cache")
                recycleAll(imageCache)
            }
        }
    }

    override fun onLowMemory() {
        Log.d(TAG, "onLowMemory: evicting all caches")
        evictAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // no-op
    }

    /**
     * 获取或创建纯色圆角 Bitmap。同一「颜色+圆角+尺寸」命中缓存，零分配。
     *
     * 尺寸进 key 是必须的：圆角半径按位图像素算，尺寸不同得到的圆角弧度也不同，
     * 共用一张会让窄形态的圆角被 `fitXY` 拉成椭圆。
     */
    @Synchronized
    fun getOrCreateSolidBitmap(
        context: Context,
        color: Int,
        cornerRadiusDp: Float,
        targetW: Int = TARGET_W,
        targetH: Int = TARGET_H,
        widgetWDp: Int = 0,
        widgetHDp: Int = 0,
    ): Bitmap? {
        val key = "$color|$cornerRadiusDp|${targetW}x$targetH|${widgetWDp}x$widgetHDp"
        alive(solidCache, key)?.let { return it }
        val bmp = createSolidRoundedBitmap(
            context, color, cornerRadiusDp, targetW, targetH, widgetWDp, widgetHDp
        ) ?: return null
        solidCache[key] = bmp
        trimToBudget()
        return bmp
    }

    /**
     * 获取或创建自定义背景图 Bitmap，输出**恰好**是 [targetW]×[targetH]。
     *
     * 输出尺寸严格等于目标尺寸，所以布局里的 `fitXY` 变成恒等变换，图不会被拉伸；
     * [crop] 为 null 时按目标比例居中裁。
     */
    @Synchronized
    fun getOrCreateImageBitmap(
        context: Context,
        uri: String,
        cornerRadiusDp: Float,
        crop: BgCrop.Rect? = null,
        targetW: Int = TARGET_W,
        targetH: Int = TARGET_H,
        widgetWDp: Int = 0,
        widgetHDp: Int = 0,
    ): Bitmap? {
        if (uri.isEmpty()) return null
        val key = "$uri|$cornerRadiusDp|${targetW}x$targetH|" +
            "${widgetWDp}x$widgetHDp|${crop?.cacheKey() ?: "auto"}"
        alive(imageCache, key)?.let { return it }
        val bmp = loadCroppedBitmap(
            context, uri, crop, targetW, targetH, cornerRadiusDp, widgetWDp, widgetHDp
        ) ?: return null
        imageCache[key] = bmp
        trimToBudget()
        return bmp
    }

    /** 回收所有缓存（主题/配置变更后调用） */
    @Synchronized
    fun evictAll() {
        recycleAll(solidCache)
        recycleAll(imageCache)
    }

    // ── 内部方法 ──

    /**
     * 取缓存里仍可用的那张。
     *
     * 已被 recycle 的条目要顺手删掉：留着会让 [trimToBudget] 把逐出额度浪费在
     * 一个空壳上，下次还是命中不了。
     */
    private fun alive(cache: LinkedHashMap<String, Bitmap>, key: String): Bitmap? {
        val bmp = cache[key] ?: return null
        if (bmp.isRecycled) {
            cache.remove(key)
            return null
        }
        return bmp
    }

    /**
     * 按条数与字节预算逐出。
     *
     * 逐出即 recycle 是安全的：渲染串行执行，且 `updateAppWidget` 是同步 binder 调用
     * ——返回时 Bitmap 已被写进 parcel，本轮不会再有人读这张。
     * 字节超预算时先动图片缓存：单张最大 1MB，比纯色的 820KB 更值得先让位。
     */
    private fun trimToBudget() {
        while (imageCache.size > MAX_IMAGE_ENTRIES) evictOldest(imageCache)
        while (solidCache.size > MAX_SOLID_ENTRIES) evictOldest(solidCache)
        while (totalBytes() > BYTE_BUDGET && imageCache.isNotEmpty()) evictOldest(imageCache)
        // 纯色缓存至少留一张：全部逐光会让下一轮渲染必然重建，失去缓存意义
        while (totalBytes() > BYTE_BUDGET && solidCache.size > 1) evictOldest(solidCache)
    }

    private fun totalBytes(): Int {
        var sum = 0
        for (b in solidCache.values) if (!b.isRecycled) sum += b.byteCount
        for (b in imageCache.values) if (!b.isRecycled) sum += b.byteCount
        return sum
    }

    private fun evictOldest(cache: LinkedHashMap<String, Bitmap>) {
        val oldest = cache.entries.firstOrNull() ?: return
        cache.remove(oldest.key)
        oldest.value.let { if (!it.isRecycled) it.recycle() }
    }

    private fun recycleAll(cache: LinkedHashMap<String, Bitmap>) {
        for (b in cache.values) if (!b.isRecycled) b.recycle()
        cache.clear()
    }


    /**
     * 生成指定颜色的圆角纯色 Bitmap，尺寸与目标一致（圆角才不会被 `fitXY` 拉变形）。
     */
    private fun createSolidRoundedBitmap(
        context: Context,
        color: Int,
        cornerRadiusDp: Float,
        targetW: Int,
        targetH: Int,
        widgetWDp: Int,
        widgetHDp: Int,
    ): Bitmap? {
        return try {
            val source = Bitmap.createBitmap(
                targetW.coerceAtLeast(1), targetH.coerceAtLeast(1), Bitmap.Config.ARGB_8888
            )
            source.eraseColor(color)
            applyRoundedCorners(context, source, cornerRadiusDp, widgetWDp, widgetHDp)
        } catch (e: Exception) {
            Log.e(TAG, "createSolidRoundedBitmap failed: ${e.message}")
            null
        }
    }

    /**
     * 按取景矩形加载背景图，输出恰好 [targetW]×[targetH] 的圆角 Bitmap。
     *
     * 用 [BitmapRegionDecoder] 只解码要用的那一块：用户放大 5 倍框选一个小区域时，
     * 「整图解码再裁」需要把整张图先塞进内存（2560×1920 ARGB 就是 19MB），
     * 对一个后台周期性刷新的进程来说不可接受。区域解码失败时回落整图路径。
     */
    private fun loadCroppedBitmap(
        context: Context,
        uriString: String,
        crop: BgCrop.Rect?,
        targetW: Int,
        targetH: Int,
        cornerRadiusDp: Float,
        widgetWDp: Int,
        widgetHDp: Int,
    ): Bitmap? {
        return try {
            val isFilePath = !uriString.startsWith("content://") && !uriString.startsWith("file://")
            val uri = if (isFilePath) Uri.fromFile(java.io.File(uriString)) else Uri.parse(uriString)

            // 1. 读原始尺寸。注意 inJustDecodeBounds 模式下 decodeStream 按约定返回 null，
            // 尺寸只写进 bounds —— 不能用它的返回值做空判断，否则永远走不下去
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(context, uri, isFilePath, uriString)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            // 2. 换算成源图像素区域：没有取景矩形时按目标比例居中裁
            val region = regionOf(crop, srcW, srcH, targetW, targetH)
            if (region.width() <= 0 || region.height() <= 0) return null

            // 3. 采样率按「区域尺寸」而不是整图算，否则放大取景会被过度降采样成马赛克
            var sample = 1
            if (region.width() > targetW || region.height() > targetH) {
                val ratioW = region.width() / targetW.coerceAtLeast(1)
                val ratioH = region.height() / targetH.coerceAtLeast(1)
                sample = Integer.highestOneBit(maxOf(ratioW, ratioH, 1))
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }

            val raw = decodeRegion(context, uri, isFilePath, uriString, region, opts)
                ?: decodeFullThenCrop(context, uri, isFilePath, uriString, region, opts)
                ?: return null

            // 4. 缩放到目标尺寸：这一步让输出比例严格等于形态比例，fitXY 成为恒等变换
            val scaled = if (raw.width != targetW || raw.height != targetH) {
                val s = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                if (s !== raw) raw.recycle()
                s
            } else raw

            applyRoundedCorners(context, scaled, cornerRadiusDp, widgetWDp, widgetHDp)
        } catch (e: Exception) {
            Log.e(TAG, "loadCroppedBitmap failed: ${e.message}")
            null
        }
    }

    /** 归一化取景 → 源图像素区域；无取景时按目标比例居中裁，保证输出不变形 */
    private fun regionOf(crop: BgCrop.Rect?, srcW: Int, srcH: Int, targetW: Int, targetH: Int): Rect {
        if (crop != null) {
            val l = (crop.left * srcW).toInt().coerceIn(0, srcW - 1)
            val t = (crop.top * srcH).toInt().coerceIn(0, srcH - 1)
            val r = (crop.right * srcW).toInt().coerceIn(l + 1, srcW)
            val b = (crop.bottom * srcH).toInt().coerceIn(t + 1, srcH)
            return Rect(l, t, r, b)
        }
        val targetAspect = targetW.toFloat() / targetH.coerceAtLeast(1)
        val srcAspect = srcW.toFloat() / srcH
        return if (srcAspect > targetAspect) {
            // 源图更宽 → 左右各裁掉一半多余
            val w = (srcH * targetAspect).toInt().coerceIn(1, srcW)
            val l = (srcW - w) / 2
            Rect(l, 0, l + w, srcH)
        } else {
            val h = (srcW / targetAspect).toInt().coerceIn(1, srcH)
            val t = (srcH - h) / 2
            Rect(0, t, srcW, t + h)
        }
    }

    /** 打开图片流：contentResolver 失败时回落 FileInputStream（兼容内部存储路径） */
    private fun openStream(
        context: Context,
        uri: Uri,
        isFilePath: Boolean,
        rawPath: String,
    ): java.io.InputStream? {
        try {
            context.contentResolver.openInputStream(uri)?.let { return it }
        } catch (_: Exception) {
        }
        if (isFilePath && java.io.File(rawPath).exists()) {
            return try { java.io.FileInputStream(java.io.File(rawPath)) } catch (_: Exception) { null }
        }
        return null
    }

    private fun decodeRegion(
        context: Context,
        uri: Uri,
        isFilePath: Boolean,
        rawPath: String,
        region: Rect,
        opts: android.graphics.BitmapFactory.Options,
    ): Bitmap? {
        return try {
            openStream(context, uri, isFilePath, rawPath)?.use { stream ->
                val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(stream)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(stream, false)
                } ?: return null
                try {
                    decoder.decodeRegion(region, opts)
                } finally {
                    decoder.recycle()
                }
            }
        } catch (e: Exception) {
            // 部分格式（如某些 WebP/GIF）不支持区域解码，交给整图回落路径
            Log.w(TAG, "decodeRegion failed, falling back: ${e.message}")
            null
        }
    }

    private fun decodeFullThenCrop(
        context: Context,
        uri: Uri,
        isFilePath: Boolean,
        rawPath: String,
        region: Rect,
        opts: android.graphics.BitmapFactory.Options,
    ): Bitmap? {
        val full = openStream(context, uri, isFilePath, rawPath)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, opts)
        } ?: return null
        // 整图已按 sample 降采样，区域坐标要同比缩小
        val s = opts.inSampleSize.coerceAtLeast(1)
        val l = (region.left / s).coerceIn(0, full.width - 1)
        val t = (region.top / s).coerceIn(0, full.height - 1)
        val w = ((region.width() / s).coerceAtLeast(1)).coerceAtMost(full.width - l)
        val h = ((region.height() / s).coerceAtLeast(1)).coerceAtMost(full.height - t)
        return try {
            val cropped = Bitmap.createBitmap(full, l, t, w, h)
            if (cropped !== full) full.recycle()
            cropped
        } catch (e: Exception) {
            Log.w(TAG, "decodeFullThenCrop failed: ${e.message}")
            full
        }
    }

    /**
     * 给 Bitmap 画圆角（PorterDuff SRC_IN 裁形），radiusDp <= 0 时原样返回。
     *
     * 半径要换算到**位图像素**：位图会被布局里的 `fitXY` 缩放到组件实际大小，
     * 直接用 `radiusDp * density` 画，缩放后就不是设定的那个 dp 了。
     * 已知组件实测 dp 时按 `位图边长 / 组件 dp` 折算（density 在推导中约掉），
     * 缩放回去正好等于 radiusDp：
     *
     * ```
     * 屏幕上的半径 = rx * (组件dp * density / 位图宽) = radiusDp * density  ✔
     * ```
     *
     * x/y 分别算：`fitXY` 两个方向的缩放比独立，共用一个半径会把圆角压成椭圆
     * （位图比例与组件比例有偏差时尤其明显），`drawRoundRect` 正好收 rx、ry 两个值。
     *
     * @param widgetWDp 组件实测宽（dp），0 表示未知 —— 退回 `radiusDp * density`，
     *                  即改造前的行为
     */
    private fun applyRoundedCorners(
        context: Context,
        source: Bitmap,
        radiusDp: Float,
        widgetWDp: Int = 0,
        widgetHDp: Int = 0,
    ): Bitmap? {
        if (radiusDp <= 0f) return source
        if (source.isRecycled) {
            Log.w(TAG, "applyRoundedCorners: source bitmap is already recycled")
            return null  // 返回 null 而非已回收的 bitmap，避免下游使用时崩溃
        }
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "applyRoundedCorners: invalid bitmap size ${w}x${h}")
            return source
        }
        val density = context.resources.displayMetrics.density
        val known = widgetWDp > 0 && widgetHDp > 0
        val rx = (if (known) radiusDp * w / widgetWDp else radiusDp * density)
            .coerceAtMost(w / 2f)
        val ry = (if (known) radiusDp * h / widgetHDp else radiusDp * density)
            .coerceAtMost(h / 2f)

        return try {
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawRoundRect(rect, rx, ry, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(source, 0f, 0f, paint)
            if (source !== output) source.recycle()
            output
        } catch (e: Exception) {
            Log.e(TAG, "applyRoundedCorners failed: ${e.message}, returning original")
            source
        }
    }
}
