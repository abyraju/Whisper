package com.whisperflow.floating.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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

class FloatingWindowManager(
    private val context: Context,
    private val uiStateFlow: StateFlow<DictationUiState>,
    private val onAction: (FloatingAction) -> Unit
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val textInjector = TextInjector(context)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var composeView: ComposeView? = null
    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        x = 50
        y = 200
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (composeView != null) return

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        composeView = ComposeView(context).apply {
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

            setOnTouchListener { view, event ->
                handleTouch(event)
            }
        }

        windowManager.addView(composeView, layoutParams)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()

                // If dragged more than 8dp, update window position
                if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                    layoutParams.x = initialX + deltaX
                    layoutParams.y = initialY + deltaY
                    composeView?.let { windowManager.updateViewLayout(it, layoutParams) }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                // Snap to left or right edge
                val metrics = context.resources.displayMetrics
                val screenWidth = metrics.widthPixels
                if (layoutParams.x + 100 > screenWidth / 2) {
                    layoutParams.x = screenWidth - 180
                } else {
                    layoutParams.x = 20
                }
                composeView?.let { windowManager.updateViewLayout(it, layoutParams) }
                return false
            }
        }
        return false
    }

    fun hide() {
        composeView?.let {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            windowManager.removeView(it)
        }
        composeView = null
    }
}
