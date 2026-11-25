package com.bookkeeping.app.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bookkeeping.app.rom.RomSettingsLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct streaming download + install hand-off for a checked release.
 *
 * Replaces Android's system DownloadManager (which often stalls in a system queue
 * for 1–2 minutes on OEM ROMs with 0% progress). Streams directly over HTTP with
 * Range resume support into the app-specific external Downloads directory,
 * reports live speed and byte progress, verifies size/SHA-256/signature, and
 * hands off to the system installer via FileProvider.
 */
object UpdateInstallFlow {
    const val APK_MIME = "application/vnd.android.package-archive"

    private const val AUTHORITY_SUFFIX = ".updates"
    private const val PERCENT_MAX = 100
    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 350L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val INSTALL_RESERVE_BYTES = 64L * 1024L * 1024L
    private const val MAX_RESUME_ATTEMPTS = 3
    private val downloadMutex = Mutex()
    private val contentRangeRegex = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

    internal fun isValidPartialRange(
        contentRange: String?,
        expectedStart: Long,
        expectedTotal: Long,
    ): Boolean {
        val raw = contentRange?.trim() ?: return false
        val match = contentRangeRegex.matchEntire(raw) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val totalToken = match.groupValues[3]
        if (start != expectedStart || end < start) return false
        if (totalToken == "*") return expectedTotal <= 0L
        val total = totalToken.toLongOrNull() ?: return false
        return if (expectedTotal > 0L) total == expectedTotal else total > end
    }

    sealed interface DownloadEvent {
        data class Running(
            val percent: Int?,
            val downloadedBytes: Long = 0L,
            val totalBytes: Long = 0L,
            val bytesPerSecond: Long = 0L,
        ) : DownloadEvent

        data object Verifying : DownloadEvent
        data class Completed(val apk: File) : DownloadEvent
        data class Failed(val reason: Failure) : DownloadEvent
    }

    enum class Failure {
        REJECTED_URL,
        NO_DOWNLOAD_MANAGER,
        DOWNLOAD_FAILED,
        SIZE_MISMATCH,
        DIGEST_MISMATCH,
        SIGNATURE_MISMATCH,
        NO_SPACE,
    }

    class InsufficientStorageException : IllegalStateException("not enough space for APK")

    enum class InstallOutcome {
        OPENED_INSTALLER,
        NEEDS_PERMISSION,
        NO_INSTALLER,
        NO_SPACE,
    }

    private fun hasSpace(directory: File?, apkBytes: Long): Boolean {
        if (directory == null || apkBytes <= 0L) return false
        val available = runCatching { StatFs(directory.absolutePath).availableBytes }.getOrNull()
            ?: return true // Do not block downloads on a broken OEM StatFs.
        return available >= apkBytes + maxOf(apkBytes, INSTALL_RESERVE_BYTES)
    }

    /** App-private location: no WRITE_EXTERNAL_STORAGE, no public Downloads folder. */
    fun apkFile(context: Context, update: GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable): File? {
        val name = UpdateUrlPolicy.fileNameOf(update.apkUrl) ?: return null
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return File(dir, name)
    }

    /** A previously downloaded APK that still matches the release metadata. */
    fun verifiedDownload(
        context: Context,
        update: GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable,
    ): File? {
        val file = apkFile(context, update) ?: return null
        return when (ApkIntegrity.verify(file, update.apkSizeBytes, update.apkSha256)) {
            ApkCheck.OK -> {
                // Third gate: a file with the right bytes must also carry the
                // same signing certificate as the installed app.
                val apk = file ?: return null
                if (ApkSignatureVerifier.matchesInstalled(context, apk)) {
                    apk
                } else {
                    runCatching { apk.delete() }
                    null
                }
            }
            ApkCheck.MISSING -> null
            ApkCheck.SIZE_MISMATCH, ApkCheck.DIGEST_MISMATCH -> {
                // Leftovers from an interrupted or tampered download must never
                // reach the installer, and must not block the next attempt.
                runCatching { file.delete() }
                null
            }
        }
    }

    /**
     * Streams the APK directly via HttpURLConnection with HTTP Range resume and
     * real-time byte/speed progress, then runs size, SHA-256 and signature checks.
     */
    fun streamDownload(
        context: Context,
        update: GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable,
    ): Flow<DownloadEvent> = flow {
        if (!UpdateUrlPolicy.isAllowedAssetUrl(update.apkUrl)) {
            emit(DownloadEvent.Failed(Failure.REJECTED_URL))
            return@flow
        }
        val expectedSize = update.apkSizeBytes
        if (expectedSize <= 0L) {
            emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
            return@flow
        }
        val name = UpdateUrlPolicy.fileNameOf(update.apkUrl)
        if (name == null) {
            emit(DownloadEvent.Failed(Failure.REJECTED_URL))
            return@flow
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
            return@flow
        }

        if (!hasSpace(dir, expectedSize)) {
            emit(DownloadEvent.Failed(Failure.NO_SPACE))
            return@flow
        }

        val target = File(dir, name)
        val partFile = File(dir, "$name.part")
        downloadMutex.withLock {
            verifiedDownload(context, update)?.let { alreadyVerified ->
                emit(DownloadEvent.Completed(alreadyVerified))
                return@flow
            }
            if (target.exists() && !target.delete()) {
                emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                return@flow
            }
            if (partFile.isFile && partFile.length() == expectedSize) {
                if (ApkIntegrity.verify(partFile, expectedSize, update.apkSha256) == ApkCheck.OK &&
                    ApkSignatureVerifier.matchesInstalled(context, partFile)
                ) {
                    if (movePartToTarget(partFile, target)) {
                        emit(DownloadEvent.Completed(target))
                        return@flow
                    }
                }
                runCatching { partFile.delete() }
            } else if (partFile.isFile && partFile.length() > expectedSize) {
                runCatching { partFile.delete() }
            }

            var lastFailure = Failure.DOWNLOAD_FAILED
            for (attempt in 1..MAX_RESUME_ATTEMPTS) {
                var existingBytes = if (partFile.isFile) partFile.length() else 0L
                if (existingBytes >= expectedSize) {
                    runCatching { partFile.delete() }
                    existingBytes = 0L
                }
                emit(
                    DownloadEvent.Running(
                        percent = ((existingBytes * PERCENT_MAX) / expectedSize).toInt().coerceIn(0, PERCENT_MAX),
                        downloadedBytes = existingBytes,
                        totalBytes = expectedSize,
                        bytesPerSecond = 0L,
                    ),
                )

                var conn: HttpURLConnection? = null
                var shouldRetry = false
                try {
                    conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        instanceFollowRedirects = true
                        setRequestProperty("Accept-Encoding", "identity")
                        if (existingBytes > 0L) {
                            setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        if (code == 416) runCatching { partFile.delete() }
                        lastFailure = Failure.DOWNLOAD_FAILED
                        shouldRetry = attempt < MAX_RESUME_ATTEMPTS && code in setOf(408, 416, 429, 500, 502, 503, 504)
                        if (!shouldRetry) {
                            emit(DownloadEvent.Failed(lastFailure))
                            return@flow
                        }
                    } else {
                        val resumeAccepted = code == HttpURLConnection.HTTP_PARTIAL &&
                            existingBytes > 0L &&
                            isValidPartialRange(conn.getHeaderField("Content-Range"), existingBytes, expectedSize)
                        if (!resumeAccepted) {
                            existingBytes = 0L
                        }
                        val contentLength = conn.contentLengthLong
                        val totalBytes = when {
                            resumeAccepted && contentLength > 0L -> existingBytes + contentLength
                            !resumeAccepted && contentLength > 0L -> contentLength
                            else -> expectedSize
                        }

                        var downloaded = existingBytes
                        var lastEmitMs = System.currentTimeMillis()
                        var windowStartMs = lastEmitMs
                        var windowBytes = 0L
                        var currentSpeedBps = 0L
                        val buffer = ByteArray(BUFFER_BYTES)

                        conn.inputStream.use { input ->
                            FileOutputStream(partFile, resumeAccepted).use { output ->
                                while (currentCoroutineContext().isActive) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    windowBytes += read

                                    val now = System.currentTimeMillis()
                                    val elapsed = now - lastEmitMs
                                    if (elapsed >= PROGRESS_INTERVAL_MS) {
                                        val windowElapsedMs = (now - windowStartMs).coerceAtLeast(1L)
                                        currentSpeedBps = (windowBytes * 1000L) / windowElapsedMs
                                        if (windowElapsedMs >= 1500L) {
                                            windowStartMs = now
                                            windowBytes = 0L
                                        }
                                        val pct = if (totalBytes > 0L) {
                                            ((downloaded * PERCENT_MAX) / totalBytes).toInt().coerceIn(0, PERCENT_MAX)
                                        } else {
                                            null
                                        }
                                        emit(
                                            DownloadEvent.Running(
                                                percent = pct,
                                                downloadedBytes = downloaded,
                                                totalBytes = totalBytes,
                                                bytesPerSecond = currentSpeedBps,
                                            ),
                                        )
                                        lastEmitMs = now
                                    }
                                }
                                output.flush()
                                output.fd.sync()
                            }
                        }

                        if (!currentCoroutineContext().isActive) {
                            return@flow
                        }

                        val actualPartLength = if (partFile.isFile) partFile.length() else 0L
                        if (actualPartLength < expectedSize) {
                            lastFailure = Failure.SIZE_MISMATCH
                            shouldRetry = attempt < MAX_RESUME_ATTEMPTS
                        } else if (actualPartLength > expectedSize) {
                            runCatching { partFile.delete() }
                            lastFailure = Failure.SIZE_MISMATCH
                            shouldRetry = attempt < MAX_RESUME_ATTEMPTS
                        } else {
                            emit(
                                DownloadEvent.Running(
                                    percent = PERCENT_MAX,
                                    downloadedBytes = actualPartLength,
                                    totalBytes = expectedSize,
                                    bytesPerSecond = currentSpeedBps,
                                ),
                            )
                            emit(DownloadEvent.Verifying)

                            if (!movePartToTarget(partFile, target)) {
                                emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                                return@flow
                            }

                            when (ApkIntegrity.verify(target, update.apkSizeBytes, update.apkSha256)) {
                                ApkCheck.OK -> {
                                    if (ApkSignatureVerifier.matchesInstalled(context, target)) {
                                        emit(DownloadEvent.Completed(target))
                                    } else {
                                        runCatching { target.delete() }
                                        emit(DownloadEvent.Failed(Failure.SIGNATURE_MISMATCH))
                                    }
                                    return@flow
                                }
                                ApkCheck.SIZE_MISMATCH -> {
                                    runCatching { target.delete() }
                                    emit(DownloadEvent.Failed(Failure.SIZE_MISMATCH))
                                    return@flow
                                }
                                ApkCheck.DIGEST_MISMATCH -> {
                                    runCatching { target.delete() }
                                    emit(DownloadEvent.Failed(Failure.DIGEST_MISMATCH))
                                    return@flow
                                }
                                ApkCheck.MISSING -> {
                                    emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                                    return@flow
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    if (!currentCoroutineContext().isActive) return@flow
                    lastFailure = Failure.DOWNLOAD_FAILED
                    shouldRetry = attempt < MAX_RESUME_ATTEMPTS
                } catch (_: Exception) {
                    if (currentCoroutineContext().isActive) {
                        emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                    }
                    return@flow
                } finally {
                    runCatching { conn?.disconnect() }
                }

                if (!shouldRetry) {
                    emit(DownloadEvent.Failed(lastFailure))
                    return@flow
                }
                delay(1000L * attempt)
            }
            emit(DownloadEvent.Failed(lastFailure))
        }
    }.flowOn(Dispatchers.IO)

    private fun movePartToTarget(partFile: File, target: File): Boolean {
        if (partFile.renameTo(target)) return true
        return runCatching {
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
            true
        }.getOrDefault(false)
    }

    fun hasInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Android 8+: per-app "install unknown apps" switch, deep-linked to this app. */
    fun openInstallPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        )
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess ||
            RomSettingsLauncher.openAppDetails(context)
    }

    fun openInstaller(context: Context, apk: File): InstallOutcome {
        if (!apk.isFile) return InstallOutcome.NO_INSTALLER
        if (!hasSpace(apk.parentFile, apk.length())) return InstallOutcome.NO_SPACE
        if (!hasInstallPermission(context)) return InstallOutcome.NEEDS_PERMISSION
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, apk)
        }.getOrNull() ?: return InstallOutcome.NO_INSTALLER
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            InstallOutcome.OPENED_INSTALLER
        } catch (_: ActivityNotFoundException) {
            InstallOutcome.NO_INSTALLER
        } catch (_: SecurityException) {
            InstallOutcome.NO_INSTALLER
        }
    }
}
