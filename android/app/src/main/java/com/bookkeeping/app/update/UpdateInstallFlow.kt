package com.bookkeeping.app.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
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
    }

    enum class InstallOutcome {
        OPENED_INSTALLER,
        NEEDS_PERMISSION,
        NO_INSTALLER,
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

        val target = File(dir, name)
        val partFile = File(dir, "$name.part")
        if (target.exists() && !target.delete()) {
            emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
            return@flow
        }

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
            val resumeAccepted = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            if (code !in 200..299) {
                if (code == 416) runCatching { partFile.delete() }
                emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                return@flow
            }

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
                    output.fd.sync()
                }
            }

            if (!currentCoroutineContext().isActive) {
                return@flow
            }

            emit(
                DownloadEvent.Running(
                    percent = PERCENT_MAX,
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes,
                    bytesPerSecond = currentSpeedBps,
                ),
            )
            emit(DownloadEvent.Verifying)

            if (!partFile.renameTo(target)) {
                runCatching {
                    partFile.copyTo(target, overwrite = true)
                    partFile.delete()
                }.onFailure {
                    emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                    return@flow
                }
            }

            when (ApkIntegrity.verify(target, update.apkSizeBytes, update.apkSha256)) {
                ApkCheck.OK -> {
                    if (ApkSignatureVerifier.matchesInstalled(context, target)) {
                        emit(DownloadEvent.Completed(target))
                    } else {
                        runCatching { target.delete() }
                        emit(DownloadEvent.Failed(Failure.SIGNATURE_MISMATCH))
                    }
                }
                ApkCheck.SIZE_MISMATCH -> {
                    runCatching { target.delete() }
                    emit(DownloadEvent.Failed(Failure.SIZE_MISMATCH))
                }
                ApkCheck.DIGEST_MISMATCH -> {
                    runCatching { target.delete() }
                    emit(DownloadEvent.Failed(Failure.DIGEST_MISMATCH))
                }
                ApkCheck.MISSING -> emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
            }
        } catch (_: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
            }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

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
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openInstaller(context: Context, apk: File): InstallOutcome {
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
