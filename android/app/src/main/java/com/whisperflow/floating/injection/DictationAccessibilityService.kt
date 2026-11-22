package com.whisperflow.floating.injection

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

enum class InjectResult {
    /** Text was inserted into the focused field. */
    INSERTED,

    /** No editable field has input focus (or the service isn't enabled). */
    NO_FIELD,

    /** The focused field is a password field; nothing is inserted anywhere. */
    PASSWORD_FIELD,

    /** A field was focused but refused the edit. */
    FAILED
}

/**
 * Used ONLY to insert dictated text into the field that currently has input focus.
 * It ignores every accessibility event and never reads, stores or sends screen content.
 */
class DictationAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: DictationAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        /** Inserts [text] at the cursor (replacing any selection) of the focused text field. */
        fun injectText(text: String): InjectResult {
            val service = instance ?: return InjectResult.NO_FIELD
            val root = service.rootInActiveWindow ?: return InjectResult.NO_FIELD
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return InjectResult.NO_FIELD

            // Never touch password fields.
            if (node.isPassword) return InjectResult.PASSWORD_FIELD
            if (!node.isEditable) return InjectResult.NO_FIELD

            // Hint text ("Type a message") is reported as the field's text; treat it as empty.
            val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val length = existing.length
            val rawStart = node.textSelectionStart
            val rawEnd = node.textSelectionEnd
            val start = if (rawStart in 0..length) rawStart else length
            val end = if (rawEnd in 0..length) rawEnd else start
            val low = minOf(start, end)
            val high = maxOf(start, end)

            val updated = existing.substring(0, low) + text + existing.substring(high)
            val setTextArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
                return InjectResult.FAILED
            }

            // Put the cursor right after the inserted text.
            val caret = low + text.length
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            return InjectResult.INSERTED
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
