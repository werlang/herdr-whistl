package io.github.werlang.whistl.data

import io.github.werlang.whistl.core.log.Log
import io.github.werlang.whistl.domain.AppVersion
import io.github.werlang.whistl.platform.AppInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the app asks what the newest release is (phase 9, SPEC §2.7). */
const val UPDATE_API_URL: String = "https://api.github.com/repos/werlang/herdr-whistl/releases/latest"

/** A downloaded APK larger than this is refused unseen: the signed app is under 4 MB. */
const val MAX_APK_BYTES: Long = 32L * 1024L * 1024L

/** The published checksum asset is a line of text; anything longer is not one. */
private const val MAX_CHECKSUM_BYTES: Long = 4096L

private const val CONNECT_TIMEOUT_MS: Int = 10_000
private const val READ_TIMEOUT_MS: Int = 20_000

/**
 * A release the feed offers.
 *
 * @property version the release's version.
 * @property apkUrl the signed APK's URL.
 * @property sha256Url the URL of the checksum published next to it.
 * @property bytes the asset size the feed reports, or `null`.
 */
data class UpdateRelease(
    val version: AppVersion,
    val apkUrl: String,
    val sha256Url: String,
    val bytes: Long?,
)

/** Why a check did not produce a usable answer. */
enum class UpdateProblem {
    /** The feed could not be reached, or answered with an error status. */
    Network,

    /** The feed answered with something that is not a release this app can read. */
    Feed,

    /** The release has no signed APK, or no checksum next to it. */
    NoAsset,

    /** The system installer refused the verified file. */
    Install,
}

/** What a check found. */
sealed interface UpdateCheck {
    /** @property release a newer release, ready to download. */
    data class Available(val release: UpdateRelease) : UpdateCheck

    /** This build is the newest release. */
    data object UpToDate : UpdateCheck

    /** @property problem why the check could not answer, so the screen can say something true. */
    data class Failed(val problem: UpdateProblem) : UpdateCheck
}

/** Why a downloaded APK was not kept. */
enum class Refusal {
    /** The file was larger than the app will ever be. */
    Oversized,

    /** The bytes did not hash to the published checksum. */
    Checksum,

    /** The APK is not signed by the same key as the installed app. */
    Signature,
}

/** What a download produced. */
sealed interface DownloadOutcome {
    /** @property file the APK, verified and ready to hand to the installer. */
    data class Ready(val file: File) : DownloadOutcome

    /** @property refusal why the download was not kept. The file is gone. */
    data class Rejected(val refusal: Refusal) : DownloadOutcome

    /** @property problem the download could not complete. Nothing was kept. */
    data class Failed(val problem: UpdateProblem) : DownloadOutcome
}

/** Reads the newest release and fetches its APK (phase 9, SPEC §2.7). */
interface UpdateRepository {
    /** The version this build carries, for comparison with the feed. */
    val currentVersion: AppVersion

    /**
     * Asks the feed what the newest release is.
     *
     * @return the newer release, that this build is current, or why the check failed.
     */
    suspend fun check(): UpdateCheck

    /**
     * Downloads [release] and verifies it before keeping it.
     *
     * @param release the release to download.
     * @param onProgress called with bytes read and the expected total, when the feed reported one.
     * @return the verified file, or why it was refused.
     */
    suspend fun download(release: UpdateRelease, onProgress: (Long, Long) -> Unit): DownloadOutcome
}

/**
 * Reads a release from the feed's JSON.
 *
 * The asset names are the app's contract with its own release page: `herdr-whistl-<version>.apk` and the same name with
 * `.sha256`. A release without both is not offered — there is no way to verify it, so it is not an update.
 */
object ReleaseFeed {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param body the feed's response body.
     * @return the release, or `null` when the body is not a release with a signed APK and its checksum.
     */
    fun parse(body: String): UpdateRelease? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val version = (root["tag_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.let(AppVersion::parse) ?: return null
        val assets = (root["assets"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonObject } ?: return null
        val apkName = "herdr-whistl-$version.apk"
        val apk = assets.firstOrNull { (it["name"]?.jsonPrimitive?.content) == apkName } ?: return null
        val sha = assets.firstOrNull { (it["name"]?.jsonPrimitive?.content) == "$apkName.sha256" } ?: return null
        val apkUrl = apk["browser_download_url"]?.jsonPrimitive?.content ?: return null
        val shaUrl = sha["browser_download_url"]?.jsonPrimitive?.content ?: return null
        if (!apkUrl.isHttps() || !shaUrl.isHttps()) return null
        return UpdateRelease(version, apkUrl, shaUrl, (apk["size"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull)
    }

    private fun String.isHttps(): Boolean = startsWith("https://")
}

/** Checks a published checksum and the APK's signature against what is installed. */
object UpdateTrust {
    private val HEX = Regex("^[0-9a-f]{64}$")

    /**
     * Reads the checksum out of the published asset: a bare hash, or `sum  name` as `sha256sum` writes it.
     *
     * @param text the asset's content.
     * @return the lowercase hash, or `null` when the asset does not hold one.
     */
    fun parseChecksum(text: String): String? {
        val token = text.trim().lineSequence().firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: return null
        val lower = token.lowercase()
        return if (HEX.matches(lower)) lower else null
    }

    /**
     * @param digest the SHA-256 of the bytes.
     * @return the hash as lowercase hex.
     */
    fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }

    /**
     * Whether a downloaded APK may be installed: it must carry the same signers as the app that downloaded it. A hash
     * proves the bytes arrived intact; comparing the certificate proves they came from the same key — and that is the
     * check that cannot be satisfied by a tampered release page, because the installed app already knows its own signer.
     *
     * @param downloaded the SHA-256 digests of the APK's signers.
     * @param installed the digests of the installed app's signers.
     * @return `true` when both sets are non-empty and identical.
     */
    fun signersMatch(downloaded: Set<String>, installed: Set<String>): Boolean =
        downloaded.isNotEmpty() && downloaded == installed
}

/**
 * The real feed: GitHub releases over HTTPS.
 *
 * @param installer supplies the installed app's signers and the downloaded APK's signers.
 * @param cacheDir where the verified APK is kept until it is installed.
 * @param apiUrl the feed's URL, for tests.
 */
class HttpUpdateRepository(
    override val currentVersion: AppVersion,
    private val installer: AppInstaller,
    private val cacheDir: File,
    private val apiUrl: String = UPDATE_API_URL,
) : UpdateRepository {
    override suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val body = try {
            readText(apiUrl, MAX_CHECKSUM_BYTES * 64)
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            return@withContext UpdateCheck.Failed(UpdateProblem.Network)
        } ?: return@withContext UpdateCheck.Failed(UpdateProblem.Network)
        val release = ReleaseFeed.parse(body) ?: return@withContext UpdateCheck.Failed(
            if (body.contains("\"assets\"")) UpdateProblem.NoAsset else UpdateProblem.Feed,
        )
        if (release.version > currentVersion) UpdateCheck.Available(release) else UpdateCheck.UpToDate
    }

    override suspend fun download(release: UpdateRelease, onProgress: (Long, Long) -> Unit): DownloadOutcome =
        withContext(Dispatchers.IO) {
            val expected = try {
                readText(release.sha256Url, MAX_CHECKSUM_BYTES)?.let(UpdateTrust::parseChecksum)
            } catch (e: Exception) {
                Log.w(TAG, "checksum fetch failed: ${e.message}")
                null
            } ?: return@withContext DownloadOutcome.Failed(UpdateProblem.NoAsset)

            val dir = File(cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "herdr-whistl-${release.version}.apk")
            val partial = File(dir, target.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val connection = open(release.apkUrl)
                val total = release.bytes ?: connection.contentLengthLong
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > MAX_APK_BYTES) {
                            partial.delete()
                            return@withContext DownloadOutcome.Rejected(Refusal.Oversized)
                        }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        onProgress(read, total)
                    }
                } }
            } catch (e: Exception) {
                partial.delete()
                Log.w(TAG, "download failed: ${e.message}")
                return@withContext DownloadOutcome.Failed(UpdateProblem.Network)
            }

            val actual = UpdateTrust.hex(digest.digest())
            if (actual != expected) {
                partial.delete()
                return@withContext DownloadOutcome.Rejected(Refusal.Checksum)
            }
            val downloadedSigners = runCatching { installer.signersOf(partial) }
                .getOrElse { emptySet() }
            if (!UpdateTrust.signersMatch(downloadedSigners, installer.installedSigners())) {
                partial.delete()
                return@withContext DownloadOutcome.Rejected(Refusal.Signature)
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext DownloadOutcome.Failed(UpdateProblem.Network)
            }
            DownloadOutcome.Ready(target)
        }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "updates are only fetched over HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "herdr-whistl")
        connection.connect()
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("HTTP $status")
        }
        return connection
    }

    private fun readText(url: String, limit: Long): String? {
        val connection = open(url)
        return connection.inputStream.use { input: InputStream ->
            val text = input.readNBytes(limit.toInt()).toString(Charsets.UTF_8)
            if (text.isBlank()) null else text
        }
    }

    private companion object {
        const val TAG = "updates"
    }
}
