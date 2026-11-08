package io.github.werlang.whistl.ui.machines

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.werlang.whistl.R
import io.github.werlang.whistl.domain.HostAddress
import io.github.werlang.whistl.domain.Machine
import io.github.werlang.whistl.domain.Rejection
import io.github.werlang.whistl.domain.Validation
import io.github.werlang.whistl.domain.validateHost
import io.github.werlang.whistl.domain.validateMachineName
import io.github.werlang.whistl.domain.validatePort
import io.github.werlang.whistl.domain.validateUser

/** The SSH port a machine gets when the field is left empty (SPEC §1). */
const val DEFAULT_SSH_PORT: Int = 22

/** The fields of the Add machine form. */
enum class AddField {
    /** The host name or address. */
    Host,

    /** The SSH user name. */
    User,

    /** The SSH port. */
    Port,

    /** The display name. */
    Name,

    /** The saved password, in password mode. */
    Password,
}

/** Why a field is rejected. Each has its own message in the string resources. */
enum class FieldProblem {
    /** The host is empty. */
    HostEmpty,

    /** The host has characters that are not allowed. */
    HostBadCharacters,

    /** The host is not a valid name or address. */
    HostBadFormat,

    /** The host is longer than the limit. */
    HostTooLong,

    /** The user name is empty. */
    UserEmpty,

    /** The user name has characters that are not allowed. */
    UserBadCharacters,

    /** The user name has the wrong shape. */
    UserBadFormat,

    /** The user name is longer than the limit. */
    UserTooLong,

    /** The port contains something other than digits. */
    PortNotANumber,

    /** The port is outside 1 to 65535. */
    PortOutOfRange,

    /** The display name is empty. */
    NameEmpty,

    /** The display name is longer than the limit. */
    NameTooLong,

    /** Password mode, and the password is empty. */
    PasswordEmpty,
}

/**
 * The form after its rules: the values a machine can be added with.
 *
 * @property host the host text, IPv6 without brackets.
 * @property port the SSH port.
 * @property user the SSH user name.
 * @property name the display name, given or defaulted.
 */
data class AddDraft(
    val host: String,
    val port: Int,
    val user: String,
    val name: String,
)

/** The outcome of checking the form: a draft, or a problem for each field that failed. */
sealed interface FormCheck {
    /**
     * Every field passed.
     *
     * @property draft the values to add the machine with.
     */
    data class Valid(val draft: AddDraft) : FormCheck

    /**
     * At least one field failed.
     *
     * @property problems the problem of each field that failed.
     */
    data class Invalid(val problems: Map<AddField, FieldProblem>) : FormCheck
}

/**
 * The display name a machine gets when none is typed (phase 4b §2.5): the first label of a host name (`build.lan` → `build`),
 * or the whole literal for an IPv4 or IPv6 address (`192.168.0.10` stays `192.168.0.10`).
 *
 * @param host the validated host.
 * @return the default name.
 */
fun defaultMachineName(host: HostAddress): String = when (host) {
    is HostAddress.IPv4, is HostAddress.IPv6 -> host.text
    is HostAddress.Name -> host.text.substringBefore('.')
}

/**
 * Checks the Add machine form (phase 4b §2.5). A blank port means [DEFAULT_SSH_PORT]; a blank name means the default name of the
 * host, when the host is valid. The password is only checked for presence: its text is not kept here.
 *
 * @param host the host as typed.
 * @param user the user name as typed.
 * @param port the port as typed.
 * @param name the display name as typed.
 * @param password the password in password mode, or `null` in key mode.
 * @return the draft, or the problem of each field.
 */
fun checkAddForm(
    host: String,
    user: String,
    port: String,
    name: String,
    passwordMode: Boolean,
    password: CharArray?,
): FormCheck {
    val problems = LinkedHashMap<AddField, FieldProblem>()
    val validHost: HostAddress? = when (val r = validateHost(host.trim())) {
        is Validation.Valid -> r.value
        is Validation.Invalid -> {
            problems[AddField.Host] = when (r.reason) {
                Rejection.EMPTY -> FieldProblem.HostEmpty
                Rejection.BAD_CHARACTERS -> FieldProblem.HostBadCharacters
                Rejection.TOO_LONG -> FieldProblem.HostTooLong
                else -> FieldProblem.HostBadFormat
            }
            null
        }
    }
    val validUser = when (val r = validateUser(user.trim())) {
        is Validation.Valid -> r.value
        is Validation.Invalid -> {
            problems[AddField.User] = when (r.reason) {
                Rejection.EMPTY -> FieldProblem.UserEmpty
                Rejection.BAD_CHARACTERS -> FieldProblem.UserBadCharacters
                Rejection.TOO_LONG -> FieldProblem.UserTooLong
                else -> FieldProblem.UserBadFormat
            }
            null
        }
    }
    val (validPort, portProblem) = checkPortValue(port.trim())
    if (portProblem != null) problems[AddField.Port] = portProblem
    val displayName = name.trim().ifEmpty { validHost?.let { defaultMachineName(it) }.orEmpty() }
    val validName = if (displayName.isEmpty()) {
        if (validHost != null) problems[AddField.Name] = FieldProblem.NameEmpty
        null
    } else {
        when (val r = validateMachineName(displayName)) {
            is Validation.Valid -> r.value
            is Validation.Invalid -> {
                problems[AddField.Name] = if (r.reason == Rejection.TOO_LONG) FieldProblem.NameTooLong else FieldProblem.NameEmpty
                null
            }
        }
    }
    if (passwordMode && (password == null || password.isEmpty())) {
        problems[AddField.Password] = FieldProblem.PasswordEmpty
    }
    if (problems.isNotEmpty() || validHost == null || validUser == null || validPort == null || validName == null) {
        return FormCheck.Invalid(problems)
    }
    return FormCheck.Valid(AddDraft(host = validHost.text, port = validPort, user = validUser, name = validName))
}

/**
 * The port a text stands for, with the problem if it does not. A blank text is the default port; otherwise digits only, then the range.
 *
 * @param text the port text, trimmed.
 * @return the port (or `null`), and the problem (or `null`).
 */
fun checkPortValue(text: String): Pair<Int?, FieldProblem?> {
    if (text.isEmpty()) return DEFAULT_SSH_PORT to null
    if (!text.all { it in '0'..'9' }) return null to FieldProblem.PortNotANumber
    val number = text.toIntOrNull() ?: return null to FieldProblem.PortOutOfRange
    return when (val r = validatePort(number)) {
        is Validation.Valid -> r.value to null
        is Validation.Invalid -> null to FieldProblem.PortOutOfRange
    }
}

/**
 * A machine's address as the app shows it: `user@host[:port]` (SPEC §2.1), the port only when it is not 22, and an IPv6 host in
 * brackets. The view models hold these parts; the screen turns them into text with [text], from string resources, so no
 * visible text is joined in code (phase 6 P0-11).
 *
 * @property user the login name.
 * @property host the host name or address.
 * @property port the SSH port.
 */
data class MachineAddress(val user: String, val host: String, val port: Int)

/**
 * The address a machine is shown with.
 *
 * @return the machine's user, host and port.
 */
fun Machine.address(): MachineAddress = MachineAddress(user = user, host = host, port = port)

/**
 * The address as text, formatted from the string resources.
 *
 * @return `user@host[:port]`, in the form the app shows.
 */
@Composable
fun MachineAddress.text(): String {
    val shownHost = if (host.contains(':')) stringResource(R.string.address_host_ipv6, host) else host
    val withPort = if (port == DEFAULT_SSH_PORT) shownHost else stringResource(R.string.address_host_port, shownHost, port)
    return stringResource(R.string.address_user_host, user, withPort)
}

/**
 * The problem a repository rejection stands for, so the form shows it under the right field.
 *
 * @param field the field the repository rejected.
 * @param reason why.
 * @return the field problem.
 */
fun problemFor(field: io.github.werlang.whistl.data.MachineField, reason: Rejection): FieldProblem = when (field) {
    io.github.werlang.whistl.data.MachineField.Host -> when (reason) {
        Rejection.EMPTY -> FieldProblem.HostEmpty
        Rejection.BAD_CHARACTERS -> FieldProblem.HostBadCharacters
        Rejection.TOO_LONG -> FieldProblem.HostTooLong
        else -> FieldProblem.HostBadFormat
    }
    io.github.werlang.whistl.data.MachineField.User -> when (reason) {
        Rejection.EMPTY -> FieldProblem.UserEmpty
        Rejection.BAD_CHARACTERS -> FieldProblem.UserBadCharacters
        Rejection.TOO_LONG -> FieldProblem.UserTooLong
        else -> FieldProblem.UserBadFormat
    }
    io.github.werlang.whistl.data.MachineField.Port -> if (reason == Rejection.OUT_OF_RANGE) FieldProblem.PortOutOfRange else FieldProblem.PortNotANumber
    io.github.werlang.whistl.data.MachineField.Name -> if (reason == Rejection.TOO_LONG) FieldProblem.NameTooLong else FieldProblem.NameEmpty
    io.github.werlang.whistl.data.MachineField.Password -> FieldProblem.PasswordEmpty
}

/**
 * The form field a repository field stands for.
 *
 * @param field the repository's field.
 * @return the form field.
 */
fun addFieldFor(field: io.github.werlang.whistl.data.MachineField): AddField = when (field) {
    io.github.werlang.whistl.data.MachineField.Host -> AddField.Host
    io.github.werlang.whistl.data.MachineField.User -> AddField.User
    io.github.werlang.whistl.data.MachineField.Port -> AddField.Port
    io.github.werlang.whistl.data.MachineField.Name -> AddField.Name
    io.github.werlang.whistl.data.MachineField.Password -> AddField.Password
}
