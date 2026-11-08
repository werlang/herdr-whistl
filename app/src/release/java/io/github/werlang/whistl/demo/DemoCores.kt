package io.github.werlang.whistl.demo

import io.github.werlang.whistl.data.DemoCore

/**
 * The provider of demo data for the release build, which has none. The debug source set holds an object with the same
 * name that returns the demo core, so no demo class is compiled into a release build.
 */
object DemoCores {
    /**
     * Always `null`: a release build runs on the real repositories only.
     *
     * @return `null`.
     */
    fun fake(): DemoCore? = null
}
