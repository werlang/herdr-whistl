package io.github.werlang.whistl.ui.machines

import androidx.compose.ui.test.ExperimentalTestApi
import io.github.werlang.whistl.ui.ScreenHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The address text the screens show (SPEC §2.1): `user@host`, with `:port` only when the port is not 22 and an IPv6 host in
 * brackets. The text comes from the string resources (phase 6 P0-11), so it is rendered here, not joined in code.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class MachineAddressTextTest : ScreenHarness() {

    /** The default port is left out, a custom port is shown, and an IPv6 host is bracketed before its port. */
    @Test
    fun theTextShowsThePortOnlyWhenItIsNotTwentyTwoAndBracketsAnIpv6Host() {
        var shown: List<String> = emptyList()
        composeRule.setContent {
            shown = listOf(
                MachineAddress(user = "ci", host = "build.lan", port = 22).text(),
                MachineAddress(user = "ci", host = "build.lan", port = 2222).text(),
                MachineAddress(user = "ci", host = "fe80::1", port = 22).text(),
                MachineAddress(user = "ci", host = "fe80::1", port = 2222).text(),
            )
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf("ci@build.lan", "ci@build.lan:2222", "ci@[fe80::1]", "ci@[fe80::1]:2222"),
            shown,
        )
    }
}
