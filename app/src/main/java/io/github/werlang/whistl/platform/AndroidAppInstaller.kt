package io.github.werlang.whistl.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.werlang.whistl.core.log.Log
import io.github.werlang.whistl.data.UpdateTrust
import java.io.File
import java.security.MessageDigest

/**
 * The system side of the update (phase 9, SPEC §2.7): whose signature an APK carries, whether this build may install
 * packages, and handing the file to the package installer.
 */
interface AppInstaller {
    /**
     * @param file an APK on disk.
     * @return the SHA-256 digests of the APK's signers, empty when it cannot be read or has none.
     */
    fun signersOf(file: File): Set<String>

    /** @return the SHA-256 digests of the installed app's signers. */
    fun installedSigners(): Set<String>

    /** @return whether the user has allowed this app to install packages. */
    fun canInstall(): Boolean

    /**
     * Opens the system screen where the user allows this app to install packages.
     *
     * @return whether the screen was opened.
     */
    fun requestInstallPermission(): Boolean

    /**
     * Hands a verified APK to the system installer.
     *
     * @param file the APK.
     * @return whether an installer accepted the file.
     */
    fun install(file: File): Boolean
}

/**
 * [AppInstaller] on Android: `PackageManager` for the signatures and `FileProvider` for the handoff.
 *
 * The certificate of an APK on disk is readable without installing it, which is what makes the check possible before the
 * user is asked to install anything.
 *
 * @param context the app context.
 */
class AndroidAppInstaller(private val context: Context) : AppInstaller {
    private val packageName: String = context.packageName

    override fun signersOf(file: File): Set<String> {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags()) ?: return emptySet()
        return digests(info)
    }

    override fun installedSigners(): Set<String> {
        val info = context.packageManager.getPackageInfo(packageName, signatureFlags())
        return digests(info)
    }

    /** `GET_SIGNING_CERTIFICATES` exists from API 28; below that the only way to read a signature is the old flag. */
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    override fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true

    override fun requestInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse { false }
    }

    override fun install(file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "$packageName.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse { e ->
        Log.w("updates", "installer refused the file: ${e.message}")
        false
    }

    @Suppress("DEPRECATION")
    private fun digests(info: android.content.pm.PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures ?: return emptySet()
        }
        return certificates.mapNotNull { certificate ->
            runCatching {
                UpdateTrust.hex(MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()))
            }.getOrNull()
        }.toSet()
    }
}
