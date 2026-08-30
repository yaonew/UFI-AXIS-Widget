package com.ufi_axis_widget

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ufi_axis_widget.util.AnimationUtil
import com.ufi_axis_widget.util.DebugLogger
import com.ufi_axis_widget.util.SimpleCropView
import com.ufi_axis_widget.util.ThemeColors
import com.ufi_axis_widget.util.ThemeUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import com.ufi_axis_widget.util.ToastStyle
import com.ufi_axis_widget.util.ToastUtil

/**
 * 背景图片裁切页面。
 */
class ImageCropActivity : AppCompatActivity() {

    private companion object {
        /**
         * 取景/裁切页预览解码的长边上限。
         *
         * 不能压太狠：应用背景那条路径要从这张图输出 1080×1920 的成品，
         * 低于 1920 会掉画质。2048 是「不掉画质」与「不 OOM」的折中。
         */
        const val PREVIEW_MAX_EDGE = 2048
    }


    private lateinit var cropView: SimpleCropView
    private var sourceUri: Uri? = null
    private var targetW: Int = 1080
    private var targetH: Int = 1920
    private var saveSubDir: String = "widget_bg"
    private var saveFileName: String = "custom_bg.jpg"

    /**
     * true = 只回传归一化取景矩形，不写文件（小组件背景走这条路）。
     *
     * 小组件背景要按 4 种形态各取一块，落 4 份副本就得管副本的生成/替换/删除；
     * 存矩形则只有 4 个数，且与源图路径绑定、自动失效。应用背景只有一种比例，
     * 继续走「写文件」那条老路。
     */
    private var returnRectOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)

        // 应用主题
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.FORM)

        cropView = findViewById(R.id.crop_view)
        sourceUri = intent.data
        targetW = intent.getIntExtra("targetW", 1080)
        targetH = intent.getIntExtra("targetH", 1920)
        saveSubDir = intent.getStringExtra("saveSubDir") ?: "widget_bg"
        saveFileName = intent.getStringExtra("saveFileName") ?: "custom_bg.jpg"
        returnRectOnly = intent.getBooleanExtra("returnRect", false)

        if (sourceUri == null) {
            finish()
            return
        }

        loadSourceImage()

        // 恢复上次取景：图片是异步 post 到 view 里初始化的，这里也用 post 排在其后
        val init = intent.getFloatArrayExtra("initRect")
        if (init != null && init.size == 4) {
            cropView.setInitialCropRect(init[0], init[1], init[2], init[3])
        }

        // 返回按钮（参照 AppSettingsActivity 使用公共缩放动画组件）
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.btn_cancel)) { finish() }

        // 确定按钮（使用 layout_common_action_button 公共组件，参照 AboutActivity 检查更新按钮样式）
        val btnDoneRoot = findViewById<View>(R.id.btn_done)
        val btnDoneText = btnDoneRoot.findViewById<TextView>(R.id.common_btn_text)
        btnDoneText.text = if (returnRectOnly) "确定取景" else "确定并裁切"
        btnDoneText.textSize = 15f
        btnDoneText.background = GradientDrawable().apply {
            setColor(ThemeColors.btnBg(this@ImageCropActivity))
            cornerRadius = 12f * resources.displayMetrics.density
        }
        AnimationUtil.applyScaleClickAnimation(btnDoneRoot) { performCrop() }
    }

    private fun loadSourceImage() {
        try {
            // 先读尺寸算采样率：取景页保留的是未裁的原图（长边最多 2560），
            // 直接 ARGB_8888 全解码要 20MB 左右，低端机上就是 OOM。
            // 取景结果是归一化矩形，降采样不影响精度
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                contentResolver.openInputStream(sourceUri!!)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            } catch (_: Exception) {
                sourceUri?.path?.let { p ->
                    if (File(p).exists()) {
                        FileInputStream(File(p)).use { BitmapFactory.decodeStream(it, null, bounds) }
                    }
                }
            }
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = if (longEdge > PREVIEW_MAX_EDGE) {
                Integer.highestOneBit(maxOf(longEdge / PREVIEW_MAX_EDGE, 1))
            } else 1

            val bitmap: Bitmap? = try {
                contentResolver.openInputStream(sourceUri!!)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = sample
                    }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (_: Exception) {
                // contentResolver 失败时尝试直接 FileInputStream（兼容 file:// URI）
                val path = sourceUri?.path
                if (path != null && File(path).exists()) {
                    FileInputStream(File(path)).use { stream ->
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inSampleSize = sample
                        }
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                } else null
            }
            if (bitmap != null) {
                cropView.setImageBitmap(bitmap, targetW, targetH)
            } else {
                throw Exception("Bitmap decode failed")
            }
        } catch (e: Exception) {
            DebugLogger.w("ImageCropActivity", "图片加载失败: ${e.message}")
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "图片加载失败")
            finish()
        }
    }

    private fun performCrop() {
        if (returnRectOnly) {
            val rect = cropView.getCropRectNormalized()
            if (rect == null) {
                ToastUtil.showDropToast(this, ToastStyle.WARNING, "取景失败")
                return
            }
            val resultIntent = Intent().apply {
                putExtra("crop_rect", floatArrayOf(rect.left, rect.top, rect.right, rect.bottom))
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            return
        }

        val cropped = cropView.getCroppedBitmap(targetW, targetH)
        if (cropped == null) {
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "裁切失败")
            return
        }

        // 保存到内部存储（filesDir/{saveSubDir}/{saveFileName}），确保 Widget 进程可访问
        try {
            val dir = File(filesDir, saveSubDir)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, saveFileName)

            FileOutputStream(file).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            val resultIntent = Intent().apply {
                putExtra("cropped_file_path", file.absolutePath)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            DebugLogger.w("ImageCropActivity", "裁切结果保存失败: ${e.message}")
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "保存失败")
        }
    }
}
