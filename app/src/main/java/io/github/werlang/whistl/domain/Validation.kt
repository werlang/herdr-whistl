package io.github.werlang.whistl.domain

/**
 * Why an input was rejected. Typed, so the UI can choose its own message (i18n stays in resources).
 */
enum class Rejection {
    /** Empty, or blank where a value is required. */
    EMPTY,

    /** Longer than the cap for its kind (see the `MAX_*` constants). */
    TOO_LONG,

    /** A character outside the allowed set. */
    BAD_CHARACTERS,

    /** Right characters in the wrong shape (for example a label starting with `-`, or a bad IPv4 layout). */
    BAD_FORMAT,

    /** A number outside its range (port 1–65535, IPv4 octet 0–255). */
    OUT_OF_RANGE,

    /** A NUL byte or a line break, which can never appear in a folder path. */
    NUL_OR_LINE_BREAK,
}

/**
 * The result of a validation rule: a value on success, or the [Rejection] reason. Invalid input never throws.
 *
 * @param T the normalised value type.
 */
sealed interface Validation<out T> {
    /**
     * Accepted input.
     *
     * @property value the normalised value.
     */
    data class Valid<out T>(val value: T) : Validation<T>

    /**
     * Rejected input.
     *
     * @property reason the rule that failed.
     */
    data class Invalid(val reason: Rejection) : Validation<Nothing>
}

/** A host as accepted by [validateHost]. IPv6 is stored without brackets. */
sealed interface HostAddress {
    /** The host text, normalised: IPv6 without brackets. */
    val text: String

    /** An RFC 1123 host name. */
    data class Name(override val text: String) : HostAddress

    /** A dotted IPv4 address. */
    data class IPv4(override val text: String) : HostAddress

    /** An IPv6 address, with or without brackets in the input. */
    data class IPv6(override val text: String) : HostAddress
}

/** Longest host text (RFC 1123), in characters. */
const val MAX_HOST_LENGTH: Int = 253

/** Longest RFC 1123 label, in characters. */
const val MAX_LABEL_LENGTH: Int = 63

/** Longest user name, in characters (SPEC §6 caps it at 32). */
const val MAX_USER_LENGTH: Int = 32

/** Longest machine display name or space name, in characters after trimming. Choice: 64. */
const val MAX_NAME_LENGTH: Int = 64

/** Lowest valid SSH port. */
const val MIN_PORT: Int = 1

/** Highest valid SSH port. */
const val MAX_PORT: Int = 65535

/**
 * Validates a host (SPEC §6): an RFC 1123 host name (labels of 1–63 letters, digits and hyphens, not starting or
 * ending with a hyphen, at most 253 characters), a dotted IPv4 address, or an IPv6 address with or without brackets.
 *
 * Input made only of digits and dots is always an IPv4 address, so `1.2.3` and `256.1.1.1` are rejected rather than
 * read as host names. A colon outside brackets is IPv6 only, so `host:22` is rejected.
 *
 * @param input the text the user typed.
 * @return the normalised [HostAddress], or the rejection.
 */
fun validateHost(input: String): Validation<HostAddress> {
    if (input.isEmpty()) return Validation.Invalid(Rejection.EMPTY)
    if (input.startsWith('[') || input.contains(':')) {
        val inner = if (input.startsWith('[')) {
            if (!input.endsWith(']')) return Validation.Invalid(Rejection.BAD_FORMAT)
            input.substring(1, input.length - 1)
        } else {
            input
        }
        return if (inner.contains(':') && isIPv6(inner)) {
            Validation.Valid(HostAddress.IPv6(inner))
        } else {
            Validation.Invalid(Rejection.BAD_FORMAT)
        }
    }
    if (input.all { it in '0'..'9' || it == '.' }) {
        return validateIPv4(input).let { result ->
            when (result) {
                is Validation.Valid -> Validation.Valid(HostAddress.IPv4(input))
                is Validation.Invalid -> result
            }
        }
    }
    if (input.length > MAX_HOST_LENGTH) return Validation.Invalid(Rejection.TOO_LONG)
    if (!input.all { it.isAsciiLetterOrDigit() || it == '-' || it == '.' }) {
        return Validation.Invalid(Rejection.BAD_CHARACTERS)
    }
    val labels = input.split('.')
    for (label in labels) {
        if (label.isEmpty() || label.startsWith('-') || label.endsWith('-')) {
            return Validation.Invalid(Rejection.BAD_FORMAT)
        }
        if (label.length > MAX_LABEL_LENGTH) return Validation.Invalid(Rejection.TOO_LONG)
    }
    return Validation.Valid(HostAddress.Name(input))
}

/**
 * Validates an SSH port (SPEC §6): 1 to 65535 inclusive.
 *
 * @param input the port number.
 * @return the port, or [Rejection.OUT_OF_RANGE].
 */
fun validatePort(input: Int): Validation<Int> =
    if (input in MIN_PORT..MAX_PORT) Validation.Valid(input) else Validation.Invalid(Rejection.OUT_OF_RANGE)

/**
 * Validates an SSH user name (SPEC §6): the POSIX portable form `[a-z_][a-z0-9_-]*\$?`, at most 32 characters.
 *
 * The trailing `$` is accepted because Samba-style machine accounts use it. Anything else, including a space or `;`,
 * is rejected as a bad character.
 *
 * @param input the user name the user typed.
 * @return the user name, or the rejection.
 */
fun validateUser(input: String): Validation<String> {
    if (input.isEmpty()) return Validation.Invalid(Rejection.EMPTY)
    if (input.length > MAX_USER_LENGTH) return Validation.Invalid(Rejection.TOO_LONG)
    val allowed = input.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == '$' }
    if (!allowed) return Validation.Invalid(Rejection.BAD_CHARACTERS)
    val first = input.first()
    if (!(first in 'a'..'z' || first == '_')) return Validation.Invalid(Rejection.BAD_FORMAT)
    if (input.indexOf('$').let { it >= 0 && it != input.length - 1 }) return Validation.Invalid(Rejection.BAD_FORMAT)
    return Validation.Valid(input)
}

/**
 * Validates a space folder (SPEC §6): non-blank, with no NUL byte and no line break (`\n` or `\r`).
 *
 * The path is not otherwise normalised here: `~` and relative paths are the core's business.
 *
 * @param input the folder path.
 * @return the folder path, or the rejection.
 */
fun validateFolder(input: String): Validation<String> {
    if (input.isBlank()) return Validation.Invalid(Rejection.EMPTY)
    if (input.any { it == '\u0000' || it == '\n' || it == '\r' }) {
        return Validation.Invalid(Rejection.NUL_OR_LINE_BREAK)
    }
    return Validation.Valid(input)
}

/**
 * Validates a machine display name: non-blank after trimming, at most [MAX_NAME_LENGTH] characters.
 *
 * @param input the name the user typed.
 * @return the trimmed name, or the rejection.
 */
fun validateMachineName(input: String): Validation<String> = validateDisplayName(input)

/**
 * Validates a space name: non-blank after trimming, at most [MAX_NAME_LENGTH] characters.
 *
 * @param input the name the user typed.
 * @return the trimmed name, or the rejection.
 */
fun validateSpaceName(input: String): Validation<String> = validateDisplayName(input)

private fun validateDisplayName(input: String): Validation<String> {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return Validation.Invalid(Rejection.EMPTY)
    if (trimmed.length > MAX_NAME_LENGTH) return Validation.Invalid(Rejection.TOO_LONG)
    return Validation.Valid(trimmed)
}

/** A strict dotted IPv4: four decimal octets, no leading zeros, each 0–255. */
private fun validateIPv4(text: String): Validation<Unit> {
    val parts = text.split('.')
    if (parts.size != 4) return Validation.Invalid(Rejection.BAD_FORMAT)
    for (part in parts) {
        if (part.isEmpty() || part.length > 3) return Validation.Invalid(Rejection.BAD_FORMAT)
        if (!part.all { it in '0'..'9' }) return Validation.Invalid(Rejection.BAD_FORMAT)
        if (part.length > 1 && part.startsWith('0')) return Validation.Invalid(Rejection.BAD_FORMAT)
        if (part.toInt() > 255) return Validation.Invalid(Rejection.OUT_OF_RANGE)
    }
    return Validation.Valid(Unit)
}

private fun isStrictIPv4(text: String): Boolean = validateIPv4(text) is Validation.Valid

/**
 * IPv6 syntax (RFC 4291): eight groups of one to four hex digits, one `::` for a run of zero groups, and an
 * optional dotted IPv4 tail. Zone identifiers (`%eth0`) are rejected.
 */
private fun isIPv6(text: String): Boolean {
    var s = text
    val lastColon = s.lastIndexOf(':')
    if (lastColon < 0) return false
    val tail = s.substring(lastColon + 1)
    if (tail.contains('.')) {
        if (!isStrictIPv4(tail)) return false
        s = s.substring(0, lastColon + 1) + "0:0"
    }
    val doubleColon = s.indexOf("::")
    if (doubleColon >= 0) {
        if (s.indexOf("::", doubleColon + 1) >= 0) return false
        val left = s.substring(0, doubleColon)
        val right = s.substring(doubleColon + 2)
        val groups = (if (left.isEmpty()) emptyList() else left.split(':')) +
            (if (right.isEmpty()) emptyList() else right.split(':'))
        return groups.all(::isHexGroup) && groups.size <= 7
    }
    val groups = s.split(':')
    return groups.size == 8 && groups.all(::isHexGroup)
}

private fun isHexGroup(group: String): Boolean =
    group.length in 1..4 && group.all { it.isHexDigit() }

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
