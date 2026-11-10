package io.github.werlang.whistl.domain

/**
 * A semantic version, as the app and the release feed write it (phase 9, SPEC §2.7).
 *
 * The feed tags releases `v0.3.5`; the app carries `0.3.5`. Both parse to the same value, and comparison is numeric per
 * field — never lexicographic, so `0.3.10` is newer than `0.3.9` and `0.10.0` is newer than `0.9.9`.
 *
 * @property major the major field.
 * @property minor the minor field.
 * @property patch the patch field.
 * @property preRelease whether a pre-release suffix is present (`1.0.0-rc1`). A pre-release sorts before its release.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: Boolean = false,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return when {
            preRelease == other.preRelease -> 0
            preRelease -> -1
            else -> 1
        }
    }

    /** `major.minor.patch`, without the suffix: what the app shows. */
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(-[^+]*)?(\+.*)?$""")

        /**
         * Parses a version string.
         *
         * @param raw `0.3.5`, `v0.3.5`, `0.3.5-rc1` or `0.3.5+9`.
         * @return the version, or `null` when [raw] is not a version at all — the caller must treat that as "no update".
         */
        fun parse(raw: String): AppVersion? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return AppVersion(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
                preRelease = match.groupValues[4].isNotEmpty(),
            )
        }
    }
}
