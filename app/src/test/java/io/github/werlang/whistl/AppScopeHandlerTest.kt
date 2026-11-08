package io.github.werlang.whistl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's long-lived scope has a last-resort handler (phase 6 P0-2): an uncaught failure is reported by class name, does not
 * end the scope, and carries no message text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppScopeHandlerTest {

    /** A child that fails with a message containing remote text reports only its class; the scope keeps running. */
    @Test
    fun anUncaughtFailureIsReportedByTypeAndTheScopeLives() = runTest {
        val reports = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + appExceptionHandler { reports += it })

        scope.launch { throw IllegalStateException("terminal text that must not be logged") }
        runCurrent()
        val later = scope.launch { }
        runCurrent()

        assertEquals("the failure is reported by its class name only", listOf("IllegalStateException"), reports)
        assertFalse("no message text is reported", reports.any { it.contains("terminal text") })
        assertTrue("the scope is still active", scope.isActive)
        assertTrue("a later child ran", later.isCompleted)
    }
}
