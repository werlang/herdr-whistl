package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.log.Log
import io.github.werlang.whistl.core.log.LogSink
import java.util.Collections
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** One message the app logged during a test: its level (`d` or `w`), its tag, and its text. */
data class LoggedLine(val level: String, val tag: String, val message: String)

/**
 * Replaces [Log.sink] with a recording sink for one test and puts the build's sink back afterwards (phase 7 r6 step 0). A test
 * that reaches a warning takes this rule instead of the Android stub, so it neither throws "not mocked" nor depends on it.
 *
 * Use it as `@get:Rule val log = RecordingLog()`, then assert on [lines].
 */
class RecordingLog : TestRule {
    /** Every message logged while the rule is active, in order. */
    val lines: MutableList<LoggedLine> = Collections.synchronizedList(mutableListOf())

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val previous = Log.sink
            Log.sink = object : LogSink {
                override fun debug(tag: String, message: String) {
                    lines += LoggedLine("d", tag, message)
                }

                override fun warning(tag: String, message: String) {
                    lines += LoggedLine("w", tag, message)
                }
            }
            try {
                base.evaluate()
            } finally {
                Log.sink = previous
            }
        }
    }
}
