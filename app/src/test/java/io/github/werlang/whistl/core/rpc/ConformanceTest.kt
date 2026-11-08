package io.github.werlang.whistl.core.rpc

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Runs every row of [ConformanceCases.all] as its own test. Each row replays its recorded fixture through
 * [HcClient] and asserts on the parsed values.
 */
@RunWith(Parameterized::class)
class ConformanceTest(private val case: ConformanceCase) {

    /** Runs the row on virtual time; no test sleeps or touches the network. */
    @Test
    fun replaysFixtureAndAssertsParsedValues() = runTest {
        case.check(this)
    }

    companion object {
        /** One parameter per table row. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = ConformanceCases.all.map { arrayOf(it) }
    }
}
