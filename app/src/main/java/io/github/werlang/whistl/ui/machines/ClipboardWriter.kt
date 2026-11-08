package io.github.werlang.whistl.ui.machines

/**
 * Copies text to the system clipboard. The Add machine sheet copies the device's public key through it; the Android
 * implementation lives in `platform/`, and tests record the copy.
 */
fun interface ClipboardWriter {
    /**
     * Copies text.
     *
     * @param text the text to copy. Public data only.
     */
    fun copy(text: String)
}
