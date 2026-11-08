package io.github.werlang.whistl.core.ssh

/** The exit status a POSIX shell gives when it cannot find the command ("command not found"). */
const val EXIT_COMMAND_NOT_FOUND: Int = 127

/**
 * Whether the hc command's exit status means herdr-core is not installed for the login on the machine (phase 7 r3, the
 * amendment's row-1 finding). On a real machine hc may live in another user's home, so `whistl-test` gets "command not found".
 * That is a different failure from a network one, and it must not read as "could not reach the machine".
 *
 * @param exitStatus the command's exit status, or `null` when it did not end.
 * @return `true` only for `127`.
 */
fun isCoreMissing(exitStatus: Int?): Boolean = exitStatus == EXIT_COMMAND_NOT_FOUND
