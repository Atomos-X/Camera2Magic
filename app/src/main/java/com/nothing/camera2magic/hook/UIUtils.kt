package com.nothing.camera2magic.hook

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt

object UIUtils {
    val COLOR_ITEM_BG = "#7E57C2".toColorInt()
    val COLOR_DARK_BG = "#282828".toColorInt()

    fun createBackground(color: Int, radiusDp: Float, context: Context): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp2px(context, radiusDp)
        }
    }

    fun createCircleBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            shape = GradientDrawable.OVAL
        }
    }

    fun addBounceEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            false
        }
    }

    fun dp2px(context: Context, dp: Float): Float {
        return (dp * context.resources.displayMetrics.density)
    }
}