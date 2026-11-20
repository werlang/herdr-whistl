package io.github.werlang.whistl.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * One subscription window of one provider: how much of it is spent, and when it comes back.
 *
 * @property label what the window is called, already short enough for a card ("5h", "7d", "weekly").
 * @property usedPercent the percentage spent, `null` when the provider did not say.
 * @property resetsAtMs when the window resets, in Unix milliseconds, `null` when unknown.
 */
data class LimitWindow(val label: String, val usedPercent: Double?, val resetsAtMs: Long?)

/**
 * What one provider answered on one machine (0.7.4).
 *
 * @property id the provider's name in the document: `claude`, `codex`, `opencode`.
 * @property ok whether numbers came back at all.
 * @property error why the numbers are missing, when [ok] is false.
 * @property cached whether these numbers are the last good answer instead of a fresh one.
 * @property windows the windows the provider reports, fullest first.
 * @property errorCode the stable reason the command sent for a failure the app can explain, `null` when there is none.
 */
data class ProviderLimits(
    val id: String,
    val ok: Boolean,
    val error: String?,
    val cached: Boolean,
    val windows: List<LimitWindow>,
    val errorCode: String? = null,
) {
    /** The window closest to running out, which is the one a card shows. */
    val fullest: LimitWindow? = windows.maxByOrNull { it.usedPercent ?: -1.0 }

    /** The provider as a card names it. */
    val title: String = when (id) {
        "claude" -> "Claude"
        "codex" -> "Codex"
        "antigravity" -> "Antigravity"
        "opencode" -> "OpenCode"
        else -> id.replaceFirstChar { it.uppercase() }
    }
}

/**
 * The document the limits command prints on a machine: one entry per provider it could ask.
 *
 * @property providers the providers, in the order the command reported them.
 */
data class LimitsReport(val providers: List<ProviderLimits>) {
    /** The provider closest to running out, across every provider that answered. */
    val highest: ProviderLimits? =
        providers.filter { it.ok && it.fullest?.usedPercent != null }.maxByOrNull { it.fullest!!.usedPercent!! }
}

/**
 * Reads the document the limits command prints.
 *
 * Two shapes are accepted for a window, because the providers do not agree: Claude and Codex report `utilization` (percent
 * spent) with `resets_at` as an ISO date for Claude and as Unix seconds for Codex; OpenCode reports `percent` with
 * `resetsAt` in milliseconds. A provider that failed carries `ok: false` and an `error`, and is kept in the report so the
 * screen can say why instead of showing nothing.
 *
 * @param text the JSON the command printed.
 * @return the report, never `null`: a document that cannot be read at all comes back empty.
 */
fun parseLimits(text: String): LimitsReport {
    val root = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    } ?: return LimitsReport(emptyList())
    val providers = (root["providers"] as? JsonObject)?.map { (id, element) ->
        provider(id, element as? JsonObject ?: JsonObject(emptyMap()))
    }.orEmpty()
    return LimitsReport(providers)
}

/** One provider's entry: its verdict, and the windows it did report. */
private fun provider(id: String, obj: JsonObject): ProviderLimits {
    val ok = (obj["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val error = (obj["error"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    val errorCode = (obj["error_code"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    val cached = (obj["cached"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val windows = (obj["windows"] as? JsonObject)
        ?.mapNotNull { (name, value) -> window(name, value as? JsonObject ?: return@mapNotNull null) }
        ?.sortedByDescending { it.usedPercent ?: -1.0 }
        .orEmpty()
    return ProviderLimits(id = id, ok = ok, error = error, cached = cached, windows = windows, errorCode = errorCode)
}

/** One window, in either provider shape; `plan` and unknown keys are not windows and are dropped. */
private fun window(name: String, value: JsonObject): LimitWindow? {
    val used = (value["utilization"] as? JsonPrimitive)?.doubleOrNull
        ?: (value["percent"] as? JsonPrimitive)?.doubleOrNull
    val reset = (value["resets_at"] ?: value["resetsAt"])?.let(::resetMs)
    if (used == null && reset == null) return null
    return LimitWindow(label = label(name), usedPercent = used, resetsAtMs = reset)
}

/** The window's short name on a card. */
private fun label(name: String): String = when (name) {
    "five_hour" -> "5h"
    "seven_day" -> "7d"
    "seven_day_opus" -> "7d opus"
    "seven_day_sonnet" -> "7d sonnet"
    // Antigravity reports four quota lines plus its own model bundle; each one gets a name a card can hold.
    "gemini_weekly" -> "gemini 7d"
    "gemini_5h" -> "gemini 5h"
    "claude_gpt_weekly" -> "claude/gpt 7d"
    "claude_gpt_5h" -> "claude/gpt 5h"
    "argon_weekly" -> "argon 7d"
    else -> name
}

/**
 * A reset time as Unix milliseconds, from any of the shapes the providers use.
 *
 * A number below 100000000000 is seconds (Codex, and older Claude answers); above it, milliseconds (OpenCode). A string is an
 * ISO date. Anything else is unknown, which is honest: a guessed reset time is worse than none.
 */
private fun resetMs(element: JsonElement): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { return if (it < 100_000_000_000L) it * 1000 else it }
    primitive.doubleOrNull?.let { return if (it < 1e11) (it * 1000).toLong() else it.toLong() }
    val text = primitive.contentOrNull ?: return null
    return try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}
