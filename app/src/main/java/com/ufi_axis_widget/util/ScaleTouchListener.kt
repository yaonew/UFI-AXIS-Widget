package com.ufi_axis_widget.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * 优雅的按压缩放动画 TouchListener。
 * 按下时平滑缩小到 96%，松开时弹性回弹到 100%（带 OvershootInterpolator）。
 * 适用于 MaterialButton、LinearLayout 等可点击 View。
 */
class ScaleTouchListener(
    private val pressScale: Float = 0.96f,
    private val pressDuration: Long = 100L,
    private val releaseDuration: Long = 250L,
    private val tension: Float = 1.3f, // OvershootInterpolator 张力
) : View.OnTouchListener {

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(pressScale)
                    .scaleY(pressScale)
                    .setDuration(pressDuration)
                    .start()
            }
            MotionEvent.ACTION_UP -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(releaseDuration)
                    .setInterpolator(OvershootInterpolator(tension))
                    .withEndAction { v.performClick() }
                    .start()
                // 不在此处调 performClick()，让 withEndAction 在动画完成后调用
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150)
                    .start()
            }
        }
        return true
    }
}
