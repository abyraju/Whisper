package com.whisperflow.floating.injection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast

class TextInjector(private val context: Context) {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Inserts [text] at the cursor of the focused field. The clipboard is only used as a fallback
     * when there is no field to insert into, so transcripts aren't left in the clipboard (where
     * other apps and keyboard clipboard history could read them) after a successful insert.
     */
    fun injectOrCopy(text: String) {
        if (text.isBlank()) return

        when (DictationAccessibilityService.injectText(text)) {
            InjectResult.INSERTED -> Unit
            InjectResult.PASSWORD_FIELD -> toast("Won't insert text into a password field")
            InjectResult.NO_FIELD, InjectResult.FAILED -> copyAsFallback(text)
        }
    }

    private fun copyAsFallback(text: String) {
        val clip = ClipData.newPlainText("WhisperFlow Dictation", text)
        // Ask Android (13+) to hide the clip from clipboard previews and keyboard history.
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        try {
            clipboard.setPrimaryClip(clip)
            toast("No text field focused. Copied to clipboard.")
        } catch (_: Exception) {
            toast("Couldn't copy to clipboard")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
