package io.github.werlang.whistl.data

/**
 * The fake data set the debug build can run on instead of the real repositories (demo mode, phase 4).
 *
 * Main code never names a fake. It asks the `DemoCores` provider for a [DemoCore], which exists in two source sets:
 * debug returns the fake, release returns `null`. So no fake class is compiled into a release build.
 */
interface DemoCore {
    /** The machines to show. */
    val machines: MachinesRepository

    /** The agents to show. */
    val agents: AgentsRepository

    /** The spaces to show. */
    val spaces: SpacesRepository
}
