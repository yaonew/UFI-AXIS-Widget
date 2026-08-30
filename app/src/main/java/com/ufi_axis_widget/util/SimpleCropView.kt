package com.ufi_axis_widget.util

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * 图片裁切视图。
 * 根据目标宽高比在视图中绘制裁切框蒙层，用户拖动图片选择裁切区域。
 */
class SimpleCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sourceBitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private var lastX = 0f
    private var lastY = 0f

    // 缩放手势识别
    private val scaleDetector: ScaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val sf = detector.scaleFactor
                val currentScale = getCurrentScale()
                val newScale = currentScale * sf
                val minAllowed = baseScale
                val maxAllowed = baseScale * maxScaleMultiplier
                if (newScale < minAllowed || newScale > maxAllowed) return false

                drawMatrix.postScale(sf, sf, detector.focusX, detector.focusY)
                checkBounds()
                invalidate()
                return true
            }
        })
    /** 初始缩放基底（刚好填满裁切框的缩放值） */
    private var baseScale = 1f
    private val maxScaleMultiplier = 5f

    /** 目标裁切宽高比 = targetW / targetH */
    private var targetAspectRatio = 1f
    /** 裁切框在视图中的位置 */
    private val cropFrame = RectF()
    /** 最终输出尺寸 */
    private var outputW = 1080
    private var outputH = 1920

    // 蒙层绘制
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88000000.toInt()
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun setImageBitmap(bitmap: Bitmap, targetW: Int, targetH: Int) {
        this.sourceBitmap = bitmap
        this.targetAspectRatio = targetW.toFloat() / targetH.toFloat()
        this.outputW = targetW
        this.outputH = targetH

        post {
            calcCropFrame()
            initMatrix()
            invalidate()
        }
    }

    /**
     * 恢复上一次的取景（归一化 0~1）。
     *
     * 重新取景时必须回到用户原来框的位置，否则每次进来都跳回居中，
     * 「微调一下」就变成了「从头再框一次」。
     */
    fun setInitialCropRect(left: Float, top: Float, right: Float, bottom: Float) {
        initialCropRect = RectF(left, top, right, bottom)
        post {
            calcCropFrame()
            initMatrix()
            invalidate()
        }
    }

    /** 待恢复的取景，应用一次后清空，避免后续 onSizeChanged 把用户的拖动重置回去 */
    private var initialCropRect: RectF? = null

    private fun getCurrentScale(): Float {
        val values = FloatArray(9)
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** 计算裁切框：在视图内居中，保持目标宽高比，尽量填满 */
    private fun calcCropFrame() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return

        val viewRatio = vw / vh

        if (viewRatio > targetAspectRatio) {
            // 视图比目标更宽 → 高度撑满，宽度按比例缩
            val fh = vh
            val fw = fh * targetAspectRatio
            cropFrame.set((vw - fw) / 2f, 0f, (vw + fw) / 2f, fh)
        } else {
            // 视图比目标更窄/等高 → 宽度撑满，高度按比例缩
            val fw = vw
            val fh = fw / targetAspectRatio
            cropFrame.set(0f, (vh - fh) / 2f, fw, (vh + fh) / 2f)
        }
    }

    private fun initMatrix() {
        val bmp = sourceBitmap ?: return
        if (cropFrame.width() <= 0 || cropFrame.height() <= 0) return

        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()

        // 缩放图片使其至少填满裁切框（不留空白）
        baseScale = Math.max(cropFrame.width() / imgW, cropFrame.height() / imgH)

        val restore = initialCropRect
        if (restore != null && restore.width() > 0f && restore.height() > 0f) {
            initialCropRect = null
            // 让归一化区域正好铺满裁切框：缩放取宽高两个方向的较大值，
            // 保证覆盖（比例本来就锁死，两者理论上相等，取 max 只为吃掉取整误差）
            val scale = Math.max(
                cropFrame.width() / (restore.width() * imgW),
                cropFrame.height() / (restore.height() * imgH)
            ).coerceAtLeast(baseScale)
            drawMatrix.setScale(scale, scale)
            drawMatrix.postTranslate(
                cropFrame.left - restore.left * imgW * scale,
                cropFrame.top - restore.top * imgH * scale
            )
            checkBounds()
            return
        }

        drawMatrix.setScale(baseScale, baseScale)

        // 居中到裁切框
        val dx = cropFrame.centerX() - imgW * baseScale / 2f
        val dy = cropFrame.centerY() - imgH * baseScale / 2f
        drawMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress) {
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                drawMatrix.postTranslate(dx, dy)
                checkBounds()
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指缩放结束，更新为剩余手指的位置
                val idx = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(idx)
                lastY = event.getY(idx)
                return true
            }
        }
        return true
    }

    /** 确保图片始终覆盖裁切框，不留空白 */
    private fun checkBounds() {
        val bmp = sourceBitmap ?: return
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        drawMatrix.mapRect(rect)

        var dx = 0f
        var dy = 0f

        if (rect.left > cropFrame.left) dx = cropFrame.left - rect.left
        if (rect.right < cropFrame.right) dx = cropFrame.right - rect.right
        if (rect.top > cropFrame.top) dy = cropFrame.top - rect.top
        if (rect.bottom < cropFrame.bottom) dy = cropFrame.bottom - rect.bottom

        drawMatrix.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            calcCropFrame()
            initMatrix()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cropFrame.isEmpty) return

        sourceBitmap?.let { bmp ->
            // 1. 绘制图片
            canvas.drawBitmap(bmp, drawMatrix, null)

            // 2. 绘制蒙层（裁切框外半透明遮挡）
            // 上部蒙层
            if (cropFrame.top > 0) {
                canvas.drawRect(0f, 0f, width.toFloat(), cropFrame.top, dimPaint)
            }
            // 下部蒙层
            if (cropFrame.bottom < height) {
                canvas.drawRect(0f, cropFrame.bottom, width.toFloat(), height.toFloat(), dimPaint)
            }
            // 左部蒙层
            if (cropFrame.left > 0) {
                canvas.drawRect(0f, cropFrame.top, cropFrame.left, cropFrame.bottom, dimPaint)
            }
            // 右部蒙层
            if (cropFrame.right < width) {
                canvas.drawRect(cropFrame.right, cropFrame.top, width.toFloat(), cropFrame.bottom, dimPaint)
            }

            // 3. 裁切框边框
            canvas.drawRect(cropFrame, framePaint)

            // 4. 九宫格辅助线
            val gw = cropFrame.width() / 3f
            val gh = cropFrame.height() / 3f
            for (i in 1..2) {
                val x = cropFrame.left + gw * i
                canvas.drawLine(x, cropFrame.top, x, cropFrame.bottom, gridPaint)
                val y = cropFrame.top + gh * i
                canvas.drawLine(cropFrame.left, y, cropFrame.right, y, gridPaint)
            }
        }
    }

    /**
     * 取景框在原图上的归一化位置（0~1）。
     *
     * 与 [getCroppedBitmap] 同一套逆矩阵推导，只是不生成像素 —— 小组件背景存的是这个矩形，
     * 渲染时再按形态比例从原图取那一块，所以不需要落一份裁好的副本。
     */
    fun getCropRectNormalized(): RectF? {
        val src = sourceBitmap ?: return null
        if (cropFrame.width() <= 0 || cropFrame.height() <= 0) return null
        if (src.width <= 0 || src.height <= 0) return null

        drawMatrix.invert(inverseMatrix)
        val srcRect = RectF()
        inverseMatrix.mapRect(srcRect, cropFrame)

        val l = (srcRect.left / src.width).coerceIn(0f, 1f)
        val t = (srcRect.top / src.height).coerceIn(0f, 1f)
        val r = (srcRect.right / src.width).coerceIn(0f, 1f)
        val b = (srcRect.bottom / src.height).coerceIn(0f, 1f)
        if (r - l <= 0f || b - t <= 0f) return null
        return RectF(l, t, r, b)
    }

    /**
     * 执行裁切：将裁切框内的图片区域提取为 Bitmap，输出到目标尺寸。
     */
    fun getCroppedBitmap(targetW: Int, targetH: Int): Bitmap? {
        val src = sourceBitmap ?: return null
        if (cropFrame.width() <= 0 || cropFrame.height() <= 0) return null

        // 逆推裁切框在原始图片上的对应区域
        drawMatrix.invert(inverseMatrix)
        val srcRect = RectF()
        inverseMatrix.mapRect(srcRect, cropFrame)

        val left = Math.max(0, srcRect.left.toInt())
        val top = Math.max(0, srcRect.top.toInt())
        val w = Math.min(src.width - left, srcRect.width().toInt())
        val h = Math.min(src.height - top, srcRect.height().toInt())

        if (w <= 0 || h <= 0) return null

        return try {
            val cropped = Bitmap.createBitmap(src, left, top, w, h)
            if (w != targetW || h != targetH) {
                val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                if (scaled != cropped) cropped.recycle()
                scaled
            } else {
                cropped
            }
        } catch (e: Exception) {
            DebugLogger.w("SimpleCropView", "裁剪输出失败: ${e.message}")
            null
        }

    }
}
