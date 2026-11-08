package io.github.werlang.whistl.support

import org.junit.Assert.fail

/**
 * Asserts that [block] throws an instance of [type], and returns it so the caller can check its fields.
 *
 * Works in suspend code, where `assertThrows` (a plain lambda) does not.
 *
 * @param type the expected exception class.
 * @param block the code that should throw.
 * @return the thrown exception.
 * @throws AssertionError when nothing is thrown or a different exception is thrown.
 */
suspend fun <T : Throwable> assertThrowsSuspend(type: Class<T>, block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (type.isInstance(e)) return type.cast(e)!!
        throw AssertionError("expected ${type.simpleName} but got ${e::class.java.simpleName}: ${e.message}", e)
    }
    fail("expected ${type.simpleName} but nothing was thrown")
    throw IllegalStateException("unreachable")
}
