package io.github.werlang.whistl.ui.components

import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.machines.MachineIssue
import io.github.werlang.whistl.ui.machines.MachineStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The machine-status text shared by the Machines, Spaces, New space and Space detail screens (SPEC §2.1–§2.3, §3): one
 * mapping, so the same state reads the same everywhere.
 */
class MachineStatusTest {

    /** Every status has its label, and an error names the reason the user has to act on. */
    @Test
    fun eachStatusAndIssueHasItsLabel() {
        assertEquals(R.string.machine_status_connected, machineStatusLabelRes(MachineStatus.Connected, null))
        assertEquals(R.string.machine_status_connecting, machineStatusLabelRes(MachineStatus.Connecting, null))
        assertEquals(R.string.machine_status_offline, machineStatusLabelRes(MachineStatus.Offline, null))
        assertEquals(R.string.status_error, machineStatusLabelRes(MachineStatus.Error, null))

        val issues = listOf(
            MachineIssue.LoginRefused to R.string.machine_issue_login_refused,
            MachineIssue.UnsupportedCore to R.string.machine_issue_unsupported_core,
            MachineIssue.HostKeyToConfirm to R.string.machine_issue_host_key,
            MachineIssue.HostKeyChanged to R.string.machine_issue_host_key_changed,
            MachineIssue.ConnectionRefused to R.string.machine_issue_connection_refused,
            MachineIssue.Internal to R.string.machine_issue_internal,
            MachineIssue.CoreMissing to R.string.machine_issue_core_missing,
        )
        issues.forEach { (issue, expected) ->
            assertEquals("label for $issue", expected, machineStatusLabelRes(MachineStatus.Error, issue))
        }
    }
}
