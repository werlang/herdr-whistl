package io.github.werlang.whistl.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One test per SPEC §6 validation rule. Each test lists accepted and rejected inputs, and checks the typed result.
 * Invalid input is a [Validation.Invalid] value; no test expects an exception.
 */
class ValidationTest {

    private fun assertAccepted(expected: Any, actual: Validation<*>) {
        assertEquals(expected, (actual as? Validation.Valid)?.value)
    }

    private fun assertRejected(reason: Rejection, actual: Validation<*>) {
        assertEquals(reason, (actual as? Validation.Invalid)?.reason)
    }

    /** Host name: RFC 1123 labels, 1–63 letters, digits and hyphens, no leading or trailing hyphen, at most 253 characters. */
    @Test
    fun hostnameRule() {
        assertAccepted(HostAddress.Name("example.com"), validateHost("example.com"))
        assertAccepted(HostAddress.Name("homelab"), validateHost("homelab"))
        assertAccepted(HostAddress.Name("a-b.c1"), validateHost("a-b.c1"))
        assertAccepted(HostAddress.Name("x".repeat(63) + ".com"), validateHost("x".repeat(63) + ".com"))

        assertRejected(Rejection.EMPTY, validateHost(""))
        assertRejected(Rejection.BAD_FORMAT, validateHost("-bad.example"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("a..b"))
        assertRejected(Rejection.BAD_CHARACTERS, validateHost("bad_host"))
        assertRejected(Rejection.BAD_CHARACTERS, validateHost("evil;rm -rf"))
        assertRejected(Rejection.TOO_LONG, validateHost("x".repeat(64)))
        assertRejected(Rejection.TOO_LONG, validateHost("a.".repeat(127) + "a"))
    }

    /** IPv4: four decimal octets, each 0–255, no leading zeros. Digits-and-dots input is never a host name. */
    @Test
    fun ipv4Rule() {
        assertAccepted(HostAddress.IPv4("192.168.0.1"), validateHost("192.168.0.1"))
        assertAccepted(HostAddress.IPv4("10.0.0.255"), validateHost("10.0.0.255"))
        assertAccepted(HostAddress.IPv4("0.0.0.0"), validateHost("0.0.0.0"))

        assertRejected(Rejection.OUT_OF_RANGE, validateHost("256.1.1.1"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("01.2.3.4"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("1.2.3"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("1.2.3.4.5"))
    }

    /** IPv6, with or without brackets. A colon outside brackets is IPv6 only, so a port cannot be typed into the host. */
    @Test
    fun ipv6Rule() {
        assertAccepted(HostAddress.IPv6("::1"), validateHost("::1"))
        assertAccepted(HostAddress.IPv6("::1"), validateHost("[::1]"))
        assertAccepted(HostAddress.IPv6("fe80::1"), validateHost("fe80::1"))
        assertAccepted(HostAddress.IPv6("2001:db8::8a2e:370:7334"), validateHost("2001:db8::8a2e:370:7334"))
        assertAccepted(HostAddress.IPv6("::ffff:192.0.2.1"), validateHost("::ffff:192.0.2.1"))

        assertRejected(Rejection.BAD_FORMAT, validateHost("[::1"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("::1::2"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("12345::"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("[example.com]"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("host:22"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("1:2:3:4:5:6:7:8:9"))
        assertRejected(Rejection.BAD_FORMAT, validateHost("::1%eth0"))
    }

    /** Port: an integer from 1 to 65535 inclusive. */
    @Test
    fun portRule() {
        assertAccepted(1, validatePort(1))
        assertAccepted(22, validatePort(22))
        assertAccepted(65535, validatePort(65535))

        assertRejected(Rejection.OUT_OF_RANGE, validatePort(0))
        assertRejected(Rejection.OUT_OF_RANGE, validatePort(65536))
        assertRejected(Rejection.OUT_OF_RANGE, validatePort(-1))
    }

    /** User: POSIX portable `[a-z_][a-z0-9_-]*\$?`, at most 32 characters. */
    @Test
    fun userRule() {
        assertAccepted("root", validateUser("root"))
        assertAccepted("_svc", validateUser("_svc"))
        assertAccepted("deploy-1", validateUser("deploy-1"))
        assertAccepted("build$", validateUser("build$"))
        assertAccepted("a".repeat(32), validateUser("a".repeat(32)))

        assertRejected(Rejection.EMPTY, validateUser(""))
        assertRejected(Rejection.BAD_CHARACTERS, validateUser("evil;rm -rf"))
        assertRejected(Rejection.BAD_CHARACTERS, validateUser("Root"))
        assertRejected(Rejection.BAD_FORMAT, validateUser("1user"))
        assertRejected(Rejection.BAD_FORMAT, validateUser("a\$b"))
        assertRejected(Rejection.TOO_LONG, validateUser("a".repeat(33)))
    }

    /** Folder: non-empty, no NUL byte, no `\n`, no `\r`. Paths are otherwise left to the core. */
    @Test
    fun folderRule() {
        assertAccepted("~/code/web-app", validateFolder("~/code/web-app"))
        assertAccepted("/tmp", validateFolder("/tmp"))
        assertAccepted("relative/dir", validateFolder("relative/dir"))

        assertRejected(Rejection.EMPTY, validateFolder(""))
        assertRejected(Rejection.EMPTY, validateFolder("   "))
        assertRejected(Rejection.NUL_OR_LINE_BREAK, validateFolder("a\u0000b"))
        assertRejected(Rejection.NUL_OR_LINE_BREAK, validateFolder("a\nb"))
        assertRejected(Rejection.NUL_OR_LINE_BREAK, validateFolder("a\rb"))
    }

    /** Machine display name: non-blank after trimming, at most 64 characters; the trimmed value is kept. */
    @Test
    fun machineNameRule() {
        assertAccepted("homelab", validateMachineName("homelab"))
        assertAccepted("web", validateMachineName("  web  "))
        assertAccepted("a".repeat(64), validateMachineName("a".repeat(64)))

        assertRejected(Rejection.EMPTY, validateMachineName(""))
        assertRejected(Rejection.EMPTY, validateMachineName("   "))
        assertRejected(Rejection.TOO_LONG, validateMachineName("a".repeat(65)))
    }

    /** Space name: non-blank after trimming, at most 64 characters; the trimmed value is kept. */
    @Test
    fun spaceNameRule() {
        assertAccepted("web-app", validateSpaceName("web-app"))
        assertAccepted("web", validateSpaceName("  web  "))
        assertAccepted("a".repeat(64), validateSpaceName("a".repeat(64)))

        assertRejected(Rejection.EMPTY, validateSpaceName(""))
        assertRejected(Rejection.EMPTY, validateSpaceName("\t \n"))
        assertRejected(Rejection.TOO_LONG, validateSpaceName("a".repeat(65)))
    }
}
