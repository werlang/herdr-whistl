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
 * The fixed table of agents this app detects (SPEC §5), plus the constant detection script.
 *
 * Detection runs one constant `sh -lc` script through `machine.exec`. Nothing from user input
 * is part of that script or of any argv built here.
 */
object KnownAgents {
    /** The known agents, in the order the UI lists them. */
    val table: List<KnownAgent> = listOf(
        KnownAgent(id = "claude", displayName = "Claude Code", argv = listOf("claude")),
        KnownAgent(id = "codex", displayName = "Codex", argv = listOf("codex")),
        KnownAgent(id = "gemini", displayName = "Gemini CLI", argv = listOf("gemini")),
        KnownAgent(id = "opencode", displayName = "OpenCode", argv = listOf("opencode")),
        KnownAgent(id = "aider", displayName = "Aider", argv = listOf("aider")),
    )

    /**
     * The detection script, byte-for-byte the `argv[2]` recorded in `docs/protocol-fixtures/exec_installed.json`.
     *
     * It prints the name of every known command found on the machine's login-shell `PATH`, one per line,
     * and exits 0 even when none are found.
     */
    const val DETECTION_SCRIPT: String =
        "for a in claude codex gemini opencode aider; do command -v \"\$a\" >/dev/null 2>&1 && echo \"\$a\"; done; exit 0"

    /** The complete argv for the detection call: `sh -lc` with [DETECTION_SCRIPT]. */
    val detectionArgv: List<String> = listOf("sh", "-lc", DETECTION_SCRIPT)

    /**
     * Parses the detection output into known agents.
     *
     * Blank lines and names not in [table] are ignored. Each agent appears once, in output order.
     *
     * @param stdout the decoded stdout of the detection script.
     * @return the agents the machine has, in the order the script printed them.
     */
    fun parseInstalled(stdout: String): List<KnownAgent> {
        val byId = table.associateBy { it.id }
        return stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { byId[it] }
            .distinct()
            .toList()
    }
}
