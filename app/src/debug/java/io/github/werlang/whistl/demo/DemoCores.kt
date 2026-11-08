package io.github.werlang.whistl.demo

import io.github.werlang.whistl.data.DemoCore

/**
 * The provider of demo data for the debug build. The release source set holds an object with the same name that returns
 * `null`, so the fake is never compiled into a release build; main code only ever calls [fake].
 */
object DemoCores {
    /**
     * The demo data, in a new in-memory core.
     *
     * @return the core; never `null` in the debug build.
     */
    fun fake(): DemoCore? = FakeCore()
}
