package com.whisperflow.floating.injection

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: DictationAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun injectText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

            // Try direct text commit if supported
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!success) {
                // Fallback to paste action
                return focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
