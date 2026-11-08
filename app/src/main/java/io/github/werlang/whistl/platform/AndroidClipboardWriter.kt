package io.github.werlang.whistl.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.werlang.whistl.ui.machines.ClipboardWriter

/**
 * Copies text to the system clipboard through [ClipboardManager]. Only public data is copied through it: the Add machine sheet
 * copies this device's public key.
 *
 * @param context the context whose clipboard service is used.
 */
class AndroidClipboardWriter(context: Context) : ClipboardWriter {
    private val manager: ClipboardManager = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override fun copy(text: String) {
        manager.setPrimaryClip(ClipData.newPlainText("whistl", text))
    }
}
