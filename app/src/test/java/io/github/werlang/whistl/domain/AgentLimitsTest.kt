package io.github.werlang.whistl.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document the limits command prints, read into the pieces a card shows (0.7.4).
 *
 * The payloads are the shapes the providers really answer: Claude sends ISO reset dates, Codex sends Unix seconds, and
 * OpenCode sends milliseconds — three ways to say the same thing.
 */
class AgentLimitsTest {

    /** A real Claude answer: utilization per window, ISO reset dates. */
    @Test
    fun claudeWindowsCarryIsoResetDates() {
        val report = parseLimits(
            """
            {"providers": {"claude": {"ok": true, "windows": {
              "five_hour": {"utilization": 12.0, "resets_at": "2026-11-18T17:19:45+00:00"},
              "seven_day": {"utilization": 70.0, "resets_at": "2026-11-19T12:54:24+00:00"}
            }}}}
            """,
        )

        val claude = report.providers.single()
        assertEquals("Claude", claude.title)
        assertEquals(listOf("7d", "5h"), claude.windows.map { it.label })
        assertEquals(70.0, claude.fullest!!.usedPercent!!, 0.001)
        assertEquals(70.0, report.highest!!.fullest!!.usedPercent!!, 0.001)
    }

    /** A real Codex answer: Unix seconds for the reset, and a plan field that is not a window. */
    @Test
    fun codexSecondsBecomeMillisecondsAndThePlanIsNotAWindow() {
        val report = parseLimits(
            """
            {"providers": {"codex": {"ok": true, "windows": {
              "five_hour": {"utilization": 0.0, "resets_at": 1795022385},
              "seven_day": {"utilization": 70.0, "resets_at": 1795092864},
              "plan": "plus"
            }}}}
            """,
        )

        val codex = report.providers.single()
        assertEquals(listOf("7d", "5h"), codex.windows.map { it.label })
        assertEquals(1795092864000L, codex.windows.first().resetsAtMs)
        assertEquals(2, codex.windows.size)
    }

    /** The OpenCode daemon reports `percent` spent and `resetsAt` already in milliseconds. */
    @Test
    fun opencodePercentAndMilliseconds() {
        val report = parseLimits(
            """
            {"providers": {"opencode": {"ok": true, "windows": {
              "rolling": {"percent": 1, "limited": false, "resetsAt": 1795018154000},
              "monthly": {"percent": 55, "limited": false, "resetsAt": 1795962240000},
              "workspace": "Default"
            }}}}
            """,
        )

        val windows = report.providers.single().windows
        assertEquals(listOf("monthly", "rolling"), windows.map { it.label })
        assertEquals(55.0, windows.first().usedPercent!!, 0.001)
        assertEquals(1795962240000L, windows.first().resetsAtMs)
    }

    /** Antigravity reports four quota lines under its own names; each one gets a label a card can hold. */
    @Test
    fun antigravityQuotasTakeShortNames() {
        val report = parseLimits(
            """
            {"providers": {"antigravity": {"ok": true, "windows": {
              "gemini_weekly": {"utilization": 5.0, "resets_at": "2026-11-25T23:27:19Z"},
              "gemini_5h": {"utilization": 0.0, "resets_at": "2026-11-19T14:27:19Z"},
              "claude_gpt_weekly": {"utilization": 2.0, "resets_at": "2026-11-25T23:27:38Z"},
              "argon_weekly": {"utilization": 0.0, "resets_at": "2026-11-25T23:27:54Z"}
            }}}}
            """,
        )

        val antigravity = report.providers.single()
        assertEquals("Antigravity", antigravity.title)
        assertEquals(listOf("gemini 7d", "claude/gpt 7d", "gemini 5h", "argon 7d"), antigravity.windows.map { it.label })
    }

    /** A provider that could not be asked is kept, with the reason: "nothing left" and "could not ask" must not look alike. */
    @Test
    fun aFailedProviderKeepsItsReason() {
        val report = parseLimits(
            """
            {"providers": {"claude": {"ok": false, "error": "the limits endpoint refused the request (429): try again later", "ms": 3}}}
            """,
        )

        val claude = report.providers.single()
        assertEquals(false, claude.ok)
        assertEquals("the limits endpoint refused the request (429): try again later", claude.error)
        assertTrue(claude.windows.isEmpty())
        assertNull(report.highest)
    }

    /** A failure carries the stable code the machine sent, so the sheet can say what to do in its own words (0.7.9). */
    @Test
    fun aFailedProviderCarriesItsErrorCode() {
        val report = parseLimits(
            """
            {"providers": {"claude": {"ok": false, "error": "run `claude setup-token`", "error_code": "claude_token_setup_required"}}}
            """,
        )

        val claude = report.providers.single()
        assertEquals(false, claude.ok)
        assertEquals("claude_token_setup_required", claude.errorCode)
    }

    /** A provider that sent no code, or a null one, has none: the sheet shows its own reason as an error. */
    @Test
    fun aFailedProviderWithoutACodeHasNone() {
        val report = parseLimits(
            """
            {"providers": {
              "claude": {"ok": false, "error": "HTTP 429"},
              "codex": {"ok": false, "error": "HTTP 500", "error_code": null}
            }}
            """,
        )

        assertNull(report.providers.first { it.id == "claude" }.errorCode)
        assertNull(report.providers.first { it.id == "codex" }.errorCode)
    }

    /** Cached numbers are still numbers, and the card is told they are cached. */
    @Test
    fun cachedNumbersAreKeptAndMarked() {
        val report = parseLimits(
            """
            {"providers": {"codex": {"ok": true, "cached": true, "warning": "HTTP 429",
              "windows": {"seven_day": {"utilization": 91.0, "resets_at": 1795092864}}}}}
            """,
        )

        val codex = report.providers.single()
        assertTrue(codex.cached)
        assertEquals(91.0, report.highest!!.fullest!!.usedPercent!!, 0.001)
    }

    /** The provider closest to running out wins across providers, not the first one in the document. */
    @Test
    fun theFullestProviderIsTheHighest() {
        val report = parseLimits(
            """
            {"providers": {
              "claude": {"ok": true, "windows": {"five_hour": {"utilization": 40.0}}},
              "opencode": {"ok": true, "windows": {"monthly": {"percent": 88.0}}}
            }}
            """,
        )

        assertEquals("opencode", report.highest!!.id)
    }

    /** Output that is not the document, or an empty machine, yields an empty report instead of throwing. */
    @Test
    fun unreadableOutputYieldsAnEmptyReport() {
        assertTrue(parseLimits("Traceback (most recent call last): ...").providers.isEmpty())
        assertTrue(parseLimits("").providers.isEmpty())
        assertTrue(parseLimits("""{"providers": {}}""").providers.isEmpty())
    }

    /** A window with neither a percentage nor a reset time is not a window. */
    @Test
    fun aWindowWithNothingInItIsDropped() {
        val report = parseLimits("""{"providers": {"claude": {"ok": true, "windows": {"five_hour": {}}}}}""")

        assertTrue(report.providers.single().windows.isEmpty())
    }
}
