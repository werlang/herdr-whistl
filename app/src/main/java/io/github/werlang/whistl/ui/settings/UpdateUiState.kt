package io.github.werlang.whistl.ui.settings

import io.github.werlang.whistl.data.Refusal
import io.github.werlang.whistl.data.UpdateProblem
import java.io.File

/**
 * What the Settings screen says about updates (phase 9, SPEC §2.7). One state at a time: the screen shows the version, a
 * button, and this.
 */
sealed interface UpdateUiState {
    /** Nothing has been asked yet. */
    data object Idle : UpdateUiState

    /** A check is in flight. */
    data object Checking : UpdateUiState

    /** @property version the newest release, when it is the one installed. */
    data class UpToDate(val version: String) : UpdateUiState

    /** @property version the newer release the user can download. */
    data class Available(val version: String) : UpdateUiState

    /**
     * @property read bytes downloaded so far.
     * @property total the size the feed reported, or `0` when it did not.
     */
    data class Downloading(val read: Long, val total: Long) : UpdateUiState {
        /** @return the percentage done, or `null` when the size is unknown. */
        val percent: Int? get() = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else null
    }

    /**
     * @property version the version ready to install.
     * @property file the verified APK.
     */
    data class Ready(val version: String, val file: File) : UpdateUiState

    /** @property refusal why the downloaded file was thrown away. */
    data class Refused(val refusal: Refusal) : UpdateUiState

    /** The user has not allowed this app to install packages. */
    data object NeedsPermission : UpdateUiState

    /** @property problem why the check or the download could not finish. */
    data class Failed(val problem: UpdateProblem) : UpdateUiState
}
