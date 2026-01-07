package com.nothing.camera2magic.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nothing.camera2magic.hook.UIUtils
import com.nothing.camera2magic.view.IconLoader
import com.nothing.camera2magic.view.ModIcon
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs
import kotlin.math.hypot

@SuppressLint("StaticFieldLeak")
object FloatWindowManager : Application.ActivityLifecycleCallbacks {

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null

    // 分离：菜单容器 和 浮动按钮
    private var menuLayout: LinearLayout? = null
    private var fabIcon: ImageView? = null

    private var layoutParams: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var startedActivityCount = 0

    private var fabSize = 0
    private var fabMargin = 0

    private var collapsedWidth = 0
    private var collapsedHeight = 0

    private var isAnimating = false

    private var isEnableControl = false

    private var previewImage: ImageView? = null
    private var previewContainer: FrameLayout? = null

    private  var previewSizePx = 0

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    private fun createFloatWindow(context: Context) {
        if (rootContainer != null) return

        fabSize = UIUtils.dp2px(context, 36f).toInt()
        fabMargin = UIUtils.dp2px(context, 12f).toInt()
        val menuRadius = UIUtils.dp2px(context, 18f)

        collapsedWidth = fabSize + fabMargin *2
        collapsedHeight = fabSize + fabMargin *2

        previewSizePx = UIUtils.dp2px(context, 228f).toInt()


        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        rootContainer = FrameLayout(context)

        menuLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            // 菜单背景：浅紫色圆角矩形
            background = GradientDrawable().apply {
                setColor(UIUtils.COLOR_DARK_BG)
                cornerRadius = menuRadius
            }

            // 初始状态：不可见 (GONE 会导致宽高为0无法动画，INVISIBLE 导致占位遮挡，这里用 INVISIBLE + layoutParams 处理)
            visibility = View.INVISIBLE
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 设置 Padding，保证边缘美观
            setPadding(fabMargin, fabMargin, fabMargin, fabMargin)
        }
        menuLayout?.addView(buttonRow)

        val placeholder = View(context).apply {
            tag = "PLACEHOLDER"
            layoutParams = LinearLayout.LayoutParams(fabSize, fabSize).apply {
                marginEnd = UIUtils.dp2px(context, 8f).toInt() // 右边距
            }
        }
        buttonRow.addView(placeholder)

        val portModeButton = createButtonItem(context, ModIcon.IC_PORTRAIT) { view ->
            //
        }
        portModeButton.visibility = View.INVISIBLE
        buttonRow.addView(portModeButton)
        val flipperButton = createButtonItem(context, ModIcon.IC_FLIP) { view ->
            // TODO:
        }
        flipperButton.visibility = View.INVISIBLE
        buttonRow.addView(flipperButton)

        val rotationButton = createButtonItem(context, ModIcon.IC_ROTATE) { view ->
            // TODO:
        }
        rotationButton.visibility = View.INVISIBLE
        buttonRow.addView(rotationButton)

        val debugButton = createButtonItem(context, ModIcon.IC_DEBUG) { view ->
            // TODO:
        }
        debugButton.visibility = View.INVISIBLE
        buttonRow.addView(debugButton)

        val menuParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        rootContainer?.addView(menuLayout, menuParams)

        previewContainer = FrameLayout(context).apply {
            visibility = View.VISIBLE

            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = UIUtils.dp2px(context,12f)
            }
            val p = UIUtils.dp2px(context, 4f).toInt()
            setPadding(p,p,p,p)
        }

        val previewParams = FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            previewSizePx
        ).apply {
            setMargins(fabMargin,fabMargin/2,fabMargin,fabMargin)
        }

        menuLayout?.addView(previewContainer, previewParams)

        previewImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        previewContainer?.addView(previewImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        val textView = TextView(context).apply {
            text = "NV21 preview"
            textSize = 5f
            setTextColor(0xFFCCCCCC.toInt())
        }

        previewContainer?.addView(textView, FrameLayout.LayoutParams(
            UIUtils.dp2px(context, 40f).toInt(),
            UIUtils.dp2px(context, 8f).toInt(),
        ).apply { gravity = Gravity.RIGHT or Gravity.BOTTOM })

        fabIcon = ImageView(context).apply {
            setImageDrawable(IconLoader.getDrawable(context, ModIcon.IC_PLUS))
            val p = UIUtils.dp2px(context, 8f).toInt()
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.FIT_CENTER

            // FAB 背景：粉色圆形
            background = UIUtils.createCircleBackground(UIUtils.COLOR_ITEM_BG)

            setOnClickListener { toggleMenu(context) }
        }

        val fabParams = FrameLayout.LayoutParams(fabSize, fabSize).apply {
            gravity = Gravity.TOP or Gravity.START
            // 加上菜单容器的 padding 偏移量，确保对齐
            leftMargin = fabMargin
            topMargin = fabMargin
        }
        rootContainer?.addView(fabIcon, fabParams)

        layoutParams = WindowManager.LayoutParams(
            collapsedWidth,
            collapsedHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        fabIcon?.let { fab ->
            rootContainer?.let { root ->
                layoutParams?.let { lp ->
                    windowManager?.let { wm ->
                        fab.setOnTouchListener(WindowDragListener(root, lp, wm))
                    }
                }
            }
        }

        try {
            rootContainer?.visibility = View.VISIBLE
            windowManager?.addView(rootContainer, layoutParams)
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
    }

    private fun createButtonItem(context: Context, icon: ModIcon, onClick: (View) -> Unit): ImageView {
        val size = fabSize
        val margin = UIUtils.dp2px(context, 8f).toInt()
        val padding = UIUtils.dp2px(context, 8f).toInt()

        return ImageView(context).apply {
            setImageDrawable(IconLoader.getDrawable(context, icon))
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = UIUtils.createCircleBackground(UIUtils.COLOR_ITEM_BG)
            UIUtils.addBounceEffect(this)
            setOnClickListener { view ->
                onClick(view)
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = margin
            }
        }
    }

    private fun toggleMenu(context: Context) {
        if (isAnimating) return

        val menu = menuLayout ?: return
        val icon = fabIcon ?: return
        val lp = layoutParams ?: return

        isExpanded = !isExpanded

        val cx = icon.left + icon.width / 2
        val cy = icon.top + icon.height / 2
        val endRadius = hypot(menu.width.toDouble(), menu.height.toDouble()).toFloat()
        val startRadius = (icon.width / 2).toFloat()

        icon.animate().rotation(if (isExpanded) 135f else 0f).setDuration(300).start()

        if (isExpanded) {
            icon.setImageDrawable(IconLoader.getDrawable(context, ModIcon.IC_PLUS))

            resetChildrenState(menu)
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            windowManager?.updateViewLayout(rootContainer, lp)

            menu.visibility = View.VISIBLE

            val revealAnim =
                ViewAnimationUtils.createCircularReveal(menu, cx, cy, startRadius, endRadius)
            revealAnim.duration = 400
            revealAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    isAnimating = true
                    runEnterAnimation(menu)
                }

                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    forceAlphaOnEnd(menu)
                    windowManager?.updateViewLayout(rootContainer, lp)
                }
            })
            revealAnim.start()

        } else {

            runExitAnimation(menu)
            val hideAnim =
                ViewAnimationUtils.createCircularReveal(menu, cx, cy, endRadius, startRadius)
            hideAnim.duration = 350
            hideAnim.startDelay = 50

            hideAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    isAnimating = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    menu.visibility = View.GONE
                    icon.setImageDrawable(IconLoader.getDrawable(context, ModIcon.IC_PLUS))
                    lp.width = collapsedWidth
                    lp.height = collapsedHeight
                    layoutParams?.let { windowManager?.updateViewLayout(rootContainer, it) }
                }
            })
            hideAnim.start()
        }
    }
    private fun resetChildrenState(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)

            if (child is ViewGroup) {
                child.visibility = View.VISIBLE
                child.alpha = 1f
                resetChildrenState(child)
            } else {
                if (child.tag != "PLACEHOLDER") {
                    child.animate().cancel()
                    child.visibility = View.VISIBLE
                    child.alpha = 0f
                }
            }
        }
    }

    private fun runEnterAnimation(parent: ViewGroup) {
        var delay = 50L
        fun animateView(view: View) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    animateView(view.getChildAt(i))
                }
            } else {
                if (view.tag != "PLACEHOLDER") {
                    view.animate()
                        .alpha(1f)
                        .setDuration(200)
                        .setStartDelay(delay)
                        .start()
                    delay += 20
                }
            }
        }
        animateView(parent)
    }

    private fun runExitAnimation(parent: ViewGroup) {
        fun animateView(view: View) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    animateView(view.getChildAt(i))
                }
            } else {
                if (view.tag != "PLACEHOLDER") {
                    view.animate()
                        .alpha(0f)
                        .setDuration(150)
                        .setStartDelay(0)
                        .start()
                }
            }
        }
        animateView(parent)
    }

    private fun forceAlphaOnEnd(parent: ViewGroup) {
        fun setAlpha(view: View) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    setAlpha(view.getChildAt(i))
                }
            } else {
                if (view.tag != "PLACEHOLDER") {
                    view.alpha = 1f
                }
            }
        }
        setAlpha(parent)
    }

    fun updateFloatWindowVisibility(activity: Activity, enable: Boolean) {
        isEnableControl = enable

        if (enable) {
            if (rootContainer == null) {
                createFloatWindow(activity.applicationContext)
            }
            if (startedActivityCount > 0) {
                rootContainer?.visibility = View.VISIBLE
            }
        } else {
            rootContainer?.visibility = View.GONE
        }
    }

    fun updatePreview(bitmap: Bitmap) {
        previewImage?.post {
            if (isExpanded && previewContainer?.visibility == View.VISIBLE) {
                previewImage?.setImageBitmap(bitmap)
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++
        if (startedActivityCount == 1) {
            if (rootContainer == null) {
                createFloatWindow(activity.applicationContext)
            }
            rootContainer?.visibility = View.VISIBLE
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--
        if (startedActivityCount <= 0) {
            rootContainer?.visibility = View.GONE
            if (isExpanded) {
                isExpanded = false
                isAnimating = false
                menuLayout?.visibility = View.INVISIBLE
                fabIcon?.rotation = 0f
                fabIcon?.setImageDrawable(
                    IconLoader.getDrawable(
                        rootContainer!!.context,
                        ModIcon.IC_PLUS
                    )
                )
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (isEnableControl && rootContainer != null) {
            rootContainer?.visibility = View.VISIBLE
        }
    }

    override fun onActivityDestroyed(activity: Activity) {}
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}

class WindowDragListener(
    private val targetView: View,
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager
) : View.OnTouchListener {

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = 15

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()

                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                }

                if (isDragging) {
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(targetView, params)
                    } catch (e: Exception) {
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    view.performClick()
                }
                return true
            }
        }
        return false
    }
}