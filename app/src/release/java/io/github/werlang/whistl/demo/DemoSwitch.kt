package io.github.werlang.whistl.demo

import android.content.Context

/**
 * The release build's demo switch: none. [available] is `false`, [isOn] is always `false`, and [set] does nothing, so
 * the demo cannot be turned on in a release build (phase 4 F5). The debug source set has an object with the same name.
 */
object DemoSwitch {
    /** Demo mode cannot be switched on in this build. */
    const val available: Boolean = false

    /**
     * Always `false` in a release build.
     *
     * @param context unused.
     * @return `false`.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isOn(context: Context): Boolean = false

    /**
     * Does nothing in a release build.
     *
     * @param context unused.
     * @param on unused.
     */
    @Suppress("UNUSED_PARAMETER")
    fun set(context: Context, on: Boolean) = Unit
}
