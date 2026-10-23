package app.morsel.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Where new versions come from: the latest non-draft, non-prerelease GitHub release of this repo. */
const val UPDATE_REPO = "Joji228/ScaleBit"

/** A published version: its APK and the checksum file published next to it. */
data class Release(
    val version: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** The latest release has no ScaleBit-<version>.apk with its checksum yet. */
    data object NoRelease : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [fraction] runs from 0 to 1 while the APK arrives. */
    data class Downloading(val release: Release, val fraction: Float) : UpdateState
    /** Downloaded and checked; waiting for Android's install prompt. */
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

sealed interface InstallStart {
    /** Android's install prompt is on screen. */
    data object Started : InstallStart
    /** The user must allow installs from Scale Bit first; open [AppUpdater.permissionIntent]. */
    data object NeedsPermission : InstallStart
    data class Refused(val message: String) : InstallStart
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
)

/** Reads GitHub's release JSON and picks out the APK; separate so it can be tested without the network. */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val trustedHosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val sha256 = Regex("[0-9a-f]{64}")

    /** The release as an installable version, or null when it has no ScaleBit-<version>.apk and checksum. */
    fun parse(body: String): Release? {
        val release = json.decodeFromString<GitHubRelease>(body)
        val version = release.tagName.removePrefix("v").trim()
        if (version.isEmpty()) return null
        val apkName = "ScaleBit-$version.apk"
        val apk = release.assets.firstOrNull { it.name == apkName } ?: return null
        val checksum = release.assets.firstOrNull { it.name == "$apkName.sha256" } ?: return null
        if (!trusted(apk.downloadUrl) || !trusted(checksum.downloadUrl)) return null
        return Release(version, apkName, apk.downloadUrl, checksum.downloadUrl)
    }

    /** The hex digest from a `sha256sum` line ("<digest>  ScaleBit-1.5.0.apk"), lower-cased, or null. */
    fun checksum(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { sha256.matches(it) }

    private fun trusted(url: String): Boolean =
        url.toHttpUrlOrNull()?.let { it.scheme == "https" && it.host in trustedHosts } == true

    /** True when [latest] is a higher version than [current], comparing dotted numbers ("1.10.0" > "1.9.0"). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}

/** Looks for a newer Scale Bit on GitHub, downloads it, and hands it to Android's installer. */
class AppUpdater(private val context: Context, client: OkHttpClient) {
    private val api = client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val downloader = client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installedVersion: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    private val userAgent get() = "ScaleBit/$installedVersion (https://github.com/$UPDATE_REPO)"

    /** On app open: check at most once a day, and stay quiet when offline. */
    suspend fun checkIfDue() {
        val last = prefs.getLong(LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last >= ONE_DAY_MS) check(quiet = true)
    }

    /** Asks GitHub for the latest release. [quiet] hides a failure, for the automatic check. */
    suspend fun check(quiet: Boolean = false) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return
        _state.value = UpdateState.Checking
        try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
            _state.value = when {
                release == null -> UpdateState.NoRelease
                ReleaseParser.isNewer(release.version, installedVersion) -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = if (quiet) UpdateState.Idle else UpdateState.Failed("Couldn't reach GitHub: ${e.message.orEmpty()}")
        }
    }

    /** Downloads the version offered by [check]; the file is checked against its published SHA-256. */
    suspend fun download() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        _state.value = UpdateState.Downloading(release, 0f)
        try {
            val file = withContext(Dispatchers.IO) { fetchApk(release) }
            _state.value = UpdateState.Ready(release, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("The download didn't finish: ${e.message.orEmpty()}")
        }
    }

    /**
     * Opens Android's install prompt for a downloaded [file]. Android still asks the user to confirm,
     * and it refuses any APK signed with a different key, which is why the key must never change.
     */
    fun install(file: File): InstallStart {
        val archive = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return InstallStart.Refused("The download can't be read. Try again.")
        if (archive.packageName != context.packageName) return InstallStart.Refused("That file is for another app.")
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (!sameSigner(archive, installed)) {
            return InstallStart.Refused("That version is signed with a different key, so it can't update this app.")
        }
        if (archive.longVersionCode <= installed.longVersionCode) {
            return InstallStart.Refused("That file isn't newer than the version on this phone.")
        }
        if (!context.packageManager.canRequestPackageInstalls()) return InstallStart.NeedsPermission
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return InstallStart.Started
    }

    /** The Settings page where the user allows installs from Scale Bit. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Null when GitHub has no published version with an APK and checksum yet. */
    private fun fetchLatest(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
            .build()
        api.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            return ReleaseParser.parse(response.body.string())
        }
    }

    private fun fetchApk(release: Release): File {
        val expected = fetchChecksum(release)
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.apkName)
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", userAgent).build()
        downloader.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val total = response.body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (actual != expected) {
                target.delete()
                throw IOException("the file doesn't match its published checksum")
            }
        }
        return target
    }

    private fun fetchChecksum(release: Release): String {
        val request = Request.Builder().url(release.checksumUrl).header("User-Agent", userAgent).build()
        api.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            return ReleaseParser.checksum(response.body.string())
                ?: throw IOException("the published checksum is unreadable")
        }
    }

    private fun sameSigner(a: PackageInfo, b: PackageInfo): Boolean {
        val x = a.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        val y = b.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        return x.isNotEmpty() && x == y
    }

    private companion object {
        const val LAST_CHECK = "last_check"
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    }
}
