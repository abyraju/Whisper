package com.whisperflow.floating.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.whisperflow.floating.injection.TextInjector
import com.whisperflow.floating.service.DictationUiState
import com.whisperflow.floating.service.FloatingAction
import kotlinx.coroutines.flow.StateFlow

/**
 * Hosts the floating overlay in a system window with buttery-smooth 60/120fps drag & drop.
 */
class FloatingWindowManager(
    private val context: Context,
    private val uiStateFlow: StateFlow<DictationUiState>,
    private val onAction: (FloatingAction) -> Unit
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val textInjector = TextInjector(context)
    private val density = context.resources.displayMetrics.density

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var rootView: DragLayout? = null
    private var closeTarget: TextView? = null
    private var snapAnimator: ValueAnimator? = null
    private var closeArmed = false

    private val overlayParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(180)
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    private val closeParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        width = dp(CLOSE_TARGET_SIZE_DP)
        height = dp(CLOSE_TARGET_SIZE_DP)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    private fun dragZoneBottomPx(): Int =
        if (uiStateFlow.value.isExpanded) dp(HEADER_DRAG_ZONE_DP) else Int.MAX_VALUE

    private fun closeTargetCenterX(): Float = screenWidth() / 2f
    private fun closeTargetCenterY(): Float = (screenHeight() - dp(CLOSE_TARGET_BOTTOM_DP)).toFloat()

    fun show() {
        if (rootView != null) return

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowManager)
            setViewTreeViewModelStoreOwner(this@FloatingWindowManager)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowManager)

            setContent {
                val state by uiStateFlow.collectAsState()

                FloatingOverlayView(
                    state = state,
                    onAction = onAction,
                    onInject = {
                        val fullText = state.fullTranscript.ifEmpty { state.draftText }
                        textInjector.injectOrCopy(fullText)
                        onAction(FloatingAction.ToggleExpanded)
                    }
                )
            }
        }

        val root = DragLayout(context).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowManager)
            setViewTreeViewModelStoreOwner(this@FloatingWindowManager)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowManager)
            addView(
                compose,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addCloseTarget()
        rootView = root
        windowManager.addView(root, overlayParams)
    }

    fun hide() {
        snapAnimator?.cancel()
        snapAnimator = null

        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        rootView = null

        closeTarget?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        closeTarget = null

        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private fun addCloseTarget() {
        val target = TextView(context).apply {
            text = "×"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 220, 38, 38))
                setStroke(dp(2), Color.WHITE)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        positionCloseTarget()
        try {
            windowManager.addView(target, closeParams)
            closeTarget = target
        } catch (_: Exception) {
            closeTarget = null
        }
    }

    private fun positionCloseTarget() {
        closeParams.x = (closeTargetCenterX() - closeParams.width / 2f).toInt()
        closeParams.y = (closeTargetCenterY() - closeParams.height / 2f).toInt()
        closeTarget?.let {
            try {
                windowManager.updateViewLayout(it, closeParams)
            } catch (_: Exception) {}
        }
    }

    private fun showCloseTarget() {
        positionCloseTarget()
        closeTarget?.let {
            it.scaleX = 1f
            it.scaleY = 1f
            it.visibility = View.VISIBLE
        }
    }

    private fun hideCloseTarget() {
        closeTarget?.let {
            it.visibility = View.INVISIBLE
            it.scaleX = 1f
            it.scaleY = 1f
        }
    }

    private fun updateCloseArmed(rawX: Float, rawY: Float, feedbackView: View) {
        val dx = rawX - closeTargetCenterX()
        val dy = rawY - closeTargetCenterY()
        val radius = dp(CLOSE_TRIGGER_RADIUS_DP).toFloat()
        val nowArmed = dx * dx + dy * dy <= radius * radius
        if (nowArmed == closeArmed) return

        closeArmed = nowArmed
        val scale = if (nowArmed) 1.25f else 1f
        closeTarget?.animate()?.scaleX(scale)?.scaleY(scale)?.setDuration(120)?.start()
        if (nowArmed) {
            feedbackView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun snapToEdge(view: View) {
        val margin = dp(12)
        val screenW = screenWidth()
        val viewW = view.width.takeIf { it > 0 } ?: dp(56)
        val centreX = overlayParams.x + viewW / 2
        val targetX = if (centreX < screenW / 2) {
            margin
        } else {
            maxOf(margin, screenW - viewW - margin)
        }

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(overlayParams.x, targetX).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                overlayParams.x = animator.animatedValue as Int
                if (view.isAttachedToWindow) {
                    try {
                        windowManager.updateViewLayout(view, overlayParams)
                    } catch (_: Exception) {}
                }
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "ViewConstructor")
    private inner class DragLayout(context: Context) : FrameLayout(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var canDrag = false
        private var isDragging = false
        private var lastUpdateX = 0
        private var lastUpdateY = 0

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = overlayParams.x
                    startY = overlayParams.y
                    lastUpdateX = startX
                    lastUpdateY = startY
                    canDrag = ev.y <= dragZoneBottomPx()
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (canDrag) {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (dx * dx + dy * dy > touchSlop * touchSlop) {
                            isDragging = true
                            closeArmed = false
                            parent?.requestDisallowInterceptTouchEvent(true)
                            showCloseTarget()
                            return true
                        }
                    }
                }
            }
            return isDragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!canDrag) return false

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY

                    if (!isDragging) {
                        if (dx * dx + dy * dy > touchSlop * touchSlop) {
                            isDragging = true
                            closeArmed = false
                            parent?.requestDisallowInterceptTouchEvent(true)
                            showCloseTarget()
                        } else {
                            return true
                        }
                    }

                    val maxX = maxOf(0, screenWidth() - width)
                    val maxY = maxOf(0, screenHeight() - height)
                    val newX = (startX + dx.toInt()).coerceIn(0, maxX)
                    val newY = (startY + dy.toInt()).coerceIn(0, maxY)

                    // Throttle updates to avoid flooding WindowManager IPC
                    if (Math.abs(newX - lastUpdateX) >= 2 || Math.abs(newY - lastUpdateY) >= 2) {
                        overlayParams.x = newX
                        overlayParams.y = newY
                        lastUpdateX = newX
                        lastUpdateY = newY
                        try {
                            windowManager.updateViewLayout(this, overlayParams)
                        } catch (_: Exception) {}
                    }

                    updateCloseArmed(ev.rawX, ev.rawY, this)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        hideCloseTarget()
                        val shouldClose = ev.actionMasked == MotionEvent.ACTION_UP && closeArmed
                        closeArmed = false
                        if (shouldClose) {
                            onAction(FloatingAction.CloseService)
                        } else {
                            snapToEdge(this)
                        }
                        return true
                    }
                    isDragging = false
                    return false
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    private companion object {
        const val HEADER_DRAG_ZONE_DP = 52
        const val CLOSE_TARGET_SIZE_DP = 64
        const val CLOSE_TARGET_BOTTOM_DP = 96
        const val CLOSE_TRIGGER_RADIUS_DP = 110
    }
}
