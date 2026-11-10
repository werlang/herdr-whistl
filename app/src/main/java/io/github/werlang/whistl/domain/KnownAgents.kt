package io.github.werlang.whistl.domain

/**
 * A coding agent the app knows how to detect and start (SPEC §5).
 *
 * @property id the command name, also the value the detection script prints for it.
 * @property displayName the name shown in the UI.
 * @property argv the program to start, for example `["claude"]`.
 */
data class KnownAgent(
    val id: String,
    val displayName: String,
    val argv: List<String>,
)

/**
 * The table of agents this app detects (SPEC §5), plus the detection script built from it.
 *
 * Detection runs one constant `sh -lc` script through `machine.exec`. Nothing from user input is part of
 * that script or of any argv built here.
 *
 * Detection is a *probe*, not a whitelist: whatever the script reports comes back as an agent, so a binary
 * this table does not name is still offered (with its command name as the label) instead of being silently
 * dropped — that is how only `claude` and `opencode` used to appear on a machine that also had `codex`,
 * `gemini` and `aider` installed (0.3.5).
 */
object KnownAgents {
    /** The known agents, in the order the UI lists them. */
    val table: List<KnownAgent> = listOf(
        KnownAgent(id = "claude", displayName = "Claude Code", argv = listOf("claude")),
        KnownAgent(id = "codex", displayName = "Codex", argv = listOf("codex")),
        KnownAgent(id = "gemini", displayName = "Gemini CLI", argv = listOf("gemini")),
        KnownAgent(id = "agy", displayName = "Antigravity", argv = listOf("agy")),
        KnownAgent(id = "opencode", displayName = "OpenCode", argv = listOf("opencode")),
        KnownAgent(id = "cursor-agent", displayName = "Cursor Agent", argv = listOf("cursor-agent")),
        KnownAgent(id = "aider", displayName = "Aider", argv = listOf("aider")),
        KnownAgent(id = "goose", displayName = "Goose", argv = listOf("goose")),
        KnownAgent(id = "amp", displayName = "Amp", argv = listOf("amp")),
        KnownAgent(id = "crush", displayName = "Crush", argv = listOf("crush")),
        KnownAgent(id = "qwen", displayName = "Qwen Code", argv = listOf("qwen")),
        KnownAgent(id = "copilot", displayName = "GitHub Copilot", argv = listOf("copilot")),
        KnownAgent(id = "droid", displayName = "Droid", argv = listOf("droid")),
        KnownAgent(id = "cline", displayName = "Cline", argv = listOf("cline")),
    )

    /** The command names the detection script probes, in [table] order. */
    val probedIds: List<String> = table.map { it.id }

    /**
     * The detection script: byte-for-byte the `argv[2]` recorded in `docs/protocol-fixtures/exec_installed.json`.
     *
     * It prints the name of every probed command found on the machine's login-shell `PATH`, one per line, and
     * exits 0 even when none are found.
     */
    val DETECTION_SCRIPT: String =
        "for a in " + probedIds.joinToString(" ") +
            "; do command -v \"\\$a\" >/dev/null 2>&1 && echo \"\\$a\"; done; exit 0"

    /** The complete argv for the detection call: `sh -lc` with [DETECTION_SCRIPT]. */
    val detectionArgv: List<String> = listOf("sh", "-lc", DETECTION_SCRIPT)

    /**
     * Parses the detection output into agents.
     *
     * Blank lines are ignored and each command appears once, in output order. A name in [table] keeps its
     * display name and argv; any other name is kept as well, labelled with the command itself and started by
     * that command, because a probe result is a fact about the machine and dropping it hides agents the user
     * has installed.
     *
     * @param stdout the decoded stdout of the detection script.
     * @return the agents the machine has, in the order the script printed them.
     */
    fun parseInstalled(stdout: String): List<KnownAgent> {
        val byId = table.associateBy { it.id }
        return stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { name -> byId[name] ?: KnownAgent(id = name, displayName = name, argv = listOf(name)) }
            .distinctBy { it.id }
            .toList()
    }
}
