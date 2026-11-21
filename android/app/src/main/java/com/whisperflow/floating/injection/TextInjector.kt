package com.whisperflow.floating.injection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

class TextInjector(private val context: Context) {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun injectOrCopy(text: String) {
        if (text.isEmpty()) return

        // 1. Always copy to clipboard as safe guarantee
        val clip = ClipData.newPlainText("WhisperFlow Dictation", text)
        clipboard.setPrimaryClip(clip)

        // 2. Attempt direct Accessibility auto-paste if enabled
        val injected = DictationAccessibilityService.injectText(text)

        if (!injected) {
            Toast.makeText(context, "Copied to clipboard! Tap to paste", Toast.LENGTH_SHORT).show()
        }
    }
}
