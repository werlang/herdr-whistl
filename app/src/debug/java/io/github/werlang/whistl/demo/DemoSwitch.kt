package io.github.werlang.whistl.demo

import android.content.Context

/**
 * The debug build's demo switch (phase 4 F5). When it is on, the next start of the app runs on the in-memory [FakeCore]
 * instead of the real repositories. The release source set has an object with the same name where [available] is `false`
 * and nothing can be turned on, so the switch and the fake are never in a release build.
 */
object DemoSwitch {
    /** Demo mode can be switched on in this build. */
    const val available: Boolean = true

    private const val PREFS = "whistl.debug"
    private const val KEY = "demo_mode"

    /**
     * Whether demo mode is on.
     *
     * @param context any context.
     * @return `true` when the switch was turned on.
     */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    /**
     * Turns demo mode on or off. It takes effect at the next start.
     *
     * @param context any context.
     * @param on the new state.
     */
    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
