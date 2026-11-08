package io.github.werlang.whistl.domain

/**
 * A terminal (a process hc runs) as the app uses it. Built from hc's `Terminal` object (SPEC §5).
 *
 * Fields the app does not need are left out. Membership of a space is the label
 * `whistl.space=<space id>` (see [Agent.spaceId]).
 *
 * @property address hc's terminal id (`address` on the wire), for example `t15s`.
 * @property name the name given at run time, if any.
 * @property argv the program and its arguments, as passed to `terminal.run`.
 * @property cwd working directory on the core.
 * @property labels hc labels, including `whistl.space`.
 * @property state live or finished; `null` when hc sends a state this client does not know.
 * @property status agent status; `null` for a plain program or an unknown status.
 * @property turn hc's turn counter.
 * @property exitCode exit code once finished, else `null`.
 * @property createdMs creation time in epoch milliseconds, as hc reports it.
 * @property finishedMs finish time in epoch milliseconds, or `null` while live.
 * @property title terminal title, if any.
 * @property pid process id on the core, if reported.
 * @property signal terminating signal number, if the process was killed.
 * @property reason why it finished (for example `exited`), if reported.
 * @property keep whether hc keeps the terminal after it finishes.
 */
data class Terminal(
    val address: String,
    val name: String?,
    val argv: List<String>,
    val cwd: String?,
    val labels: Map<String, String>,
    val state: TerminalState?,
    val status: AgentStatus?,
    val turn: Long,
    val exitCode: Int?,
    val createdMs: Long?,
    val finishedMs: Long?,
    val title: String?,
    val pid: Long?,
    val signal: Int?,
    val reason: String?,
    val keep: Boolean,
)
