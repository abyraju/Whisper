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
 * Hosts the floating overlay in a system window and handles moving it.
 *
 * Behaviour:
 *  - Drag the pill (or the header of the expanded card) anywhere on screen.
 *  - While dragging, a red close target appears at the bottom centre. Drop the overlay on it
 *    (drag it down) to close the service, like a chat bubble.
 *  - Releasing anywhere else snaps the overlay to the nearest screen edge.
 *
 * Security: the window is FLAG_SECURE (excluded from screenshots / screen recording) and drops
 * touches while another app's window is drawn on top of it (tap-jacking protection).
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

    // Named overlayParams (not layoutParams) so it can't be confused with View.layoutParams
    // inside DragLayout.
    private val overlayParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(200)
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

    /** Pixels from the top of the overlay in which a drag may start. */
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

        // Compose resolves its lifecycle from the TOPMOST view in the window, which is now the
        // drag container, so the owners must be set on it as well.
        val root = DragLayout(context).apply {
            filterTouchesWhenObscured = true
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

        // The close target is added first so the overlay is always drawn above it.
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
            } catch (_: IllegalArgumentException) {
                // Already removed.
            }
        }
        rootView = null

        closeTarget?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
                // Already removed.
            }
        }
        closeTarget = null

        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Close target ("drag down to close")
    // ---------------------------------------------------------------------------------------

    private fun addCloseTarget() {
        val target = TextView(context).apply {
            text = "×"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 185, 28, 28))
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
        closeTarget?.let { windowManager.updateViewLayout(it, closeParams) }
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

    /** Arms the close target when the finger is over it, with a tick of haptic feedback. */
    private fun updateCloseArmed(rawX: Float, rawY: Float, feedbackView: View) {
        val dx = rawX - closeTargetCenterX()
        val dy = rawY - closeTargetCenterY()
        val radius = dp(CLOSE_TRIGGER_RADIUS_DP).toFloat()
        val nowArmed = dx * dx + dy * dy <= radius * radius
        if (nowArmed == closeArmed) return

        closeArmed = nowArmed
        val scale = if (nowArmed) 1.3f else 1f
        closeTarget?.let {
            it.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
        }
        if (nowArmed) {
            feedbackView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Positioning helpers
    // ---------------------------------------------------------------------------------------

    private fun snapToEdge(view: View) {
        val margin = dp(8)
        val screenW = screenWidth()
        val centreX = overlayParams.x + view.width / 2
        val targetX = if (centreX < screenW / 2) {
            margin
        } else {
            maxOf(margin, screenW - view.width - margin)
        }

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(overlayParams.x, targetX).apply {
            duration = 180
            addUpdateListener { animator ->
                overlayParams.x = animator.animatedValue as Int
                if (view.isAttachedToWindow) {
                    windowManager.updateViewLayout(view, overlayParams)
                }
            }
            start()
        }
    }

    /** Keeps the overlay fully on screen, e.g. after the pill expands into the card. */
    private fun clampToScreen(view: View) {
        if (!view.isAttachedToWindow) return
        val maxX = maxOf(0, screenWidth() - view.width)
        val maxY = maxOf(0, screenHeight() - view.height)
        val newX = overlayParams.x.coerceIn(0, maxX)
        val newY = overlayParams.y.coerceIn(0, maxY)
        if (newX != overlayParams.x || newY != overlayParams.y) {
            overlayParams.x = newX
            overlayParams.y = newY
            windowManager.updateViewLayout(view, overlayParams)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Drag container
    // ---------------------------------------------------------------------------------------

    /**
     * Wraps the Compose content. It uses RAW screen coordinates, so moving the window under the
     * finger can't feed back into the gesture, and only takes over once the finger moves past the
     * touch slop, so taps and button presses inside Compose keep working.
     */
    @SuppressLint("ClickableViewAccessibility", "ViewConstructor")
    private inner class DragLayout(context: Context) : FrameLayout(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var canDrag = false
        private var dragging = false

        private fun rememberDown(ev: MotionEvent) {
            downRawX = ev.rawX
            downRawY = ev.rawY
            startX = overlayParams.x
            startY = overlayParams.y
            canDrag = ev.y <= dragZoneBottomPx()
            dragging = false
        }

        private fun pastSlop(ev: MotionEvent): Boolean {
            val dx = ev.rawX - downRawX
            val dy = ev.rawY - downRawY
            return dx * dx + dy * dy > touchSlop * touchSlop
        }

        private fun startDrag() {
            dragging = true
            closeArmed = false
            parent?.requestDisallowInterceptTouchEvent(true)
            showCloseTarget()
        }

        private fun dragTo(ev: MotionEvent) {
            val maxX = maxOf(0, screenWidth() - width)
            val maxY = maxOf(0, screenHeight() - height)
            overlayParams.x = (startX + (ev.rawX - downRawX).toInt()).coerceIn(0, maxX)
            overlayParams.y = (startY + (ev.rawY - downRawY).toInt()).coerceIn(0, maxY)
            windowManager.updateViewLayout(this, overlayParams)
            updateCloseArmed(ev.rawX, ev.rawY, this)
        }

        private fun finishDrag(released: Boolean) {
            hideCloseTarget()
            val shouldClose = released && closeArmed
            closeArmed = false
            if (shouldClose) {
                onAction(FloatingAction.CloseService)
            } else {
                snapToEdge(this)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    rememberDown(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (canDrag && !dragging && pastSlop(ev)) {
                        startDrag()
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rememberDown(ev)
                    return canDrag
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!canDrag) return false
                    if (!dragging) {
                        if (!pastSlop(ev)) return true
                        startDrag()
                    }
                    dragTo(ev)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    finishDrag(ev.actionMasked == MotionEvent.ACTION_UP)
                    return true
                }
            }
            return false
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            post { clampToScreen(this) }
        }
    }

    private companion object {
        const val HEADER_DRAG_ZONE_DP = 56
        const val CLOSE_TARGET_SIZE_DP = 64
        const val CLOSE_TARGET_BOTTOM_DP = 96
        const val CLOSE_TRIGGER_RADIUS_DP = 110
    }
}
