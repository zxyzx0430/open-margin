package com.bookkeeping.app.update

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Download + install hand-off for a checked GitHub release.
 *
 * The system DownloadManager owns the transfer (notification progress, network
 * retry, survives the dialog), the APK lands in the app-specific external
 * Downloads directory (no storage permission), and FileProvider exposes it to
 * the system installer. The UI only talks to this object.
 */
object UpdateInstallFlow {
    const val APK_MIME = "application/vnd.android.package-archive"

    private const val AUTHORITY_SUFFIX = ".updates"
    private const val POLL_INTERVAL_MS = 400L
    private const val PERCENT_MAX = 100

    sealed interface DownloadEvent {
        data class Running(val percent: Int?) : DownloadEvent
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
            ApkCheck.OK -> file
            ApkCheck.MISSING -> null
            ApkCheck.SIZE_MISMATCH, ApkCheck.DIGEST_MISMATCH -> {
                // Leftovers from an interrupted or tampered download must never
                // reach the installer, and must not block the next attempt.
                runCatching { file.delete() }
                null
            }
        }
    }

    fun startDownload(
        context: Context,
        update: GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable,
    ): Result<Long> {
        if (!UpdateUrlPolicy.isAllowedAssetUrl(update.apkUrl)) {
            return Result.failure(IllegalArgumentException("rejected apk url"))
        }
        if (update.apkSizeBytes <= 0L) {
            return Result.failure(IllegalStateException("missing apk size"))
        }
        val name = UpdateUrlPolicy.fileNameOf(update.apkUrl)
            ?: return Result.failure(IllegalArgumentException("rejected apk name"))
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return Result.failure(IllegalStateException("download manager unavailable"))

        // DownloadManager refuses to start when the destination already exists.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return Result.failure(IllegalStateException("external files dir unavailable"))
        val target = File(dir, name)
        if (target.exists() && !target.delete()) {
            return Result.failure(IllegalStateException("could not replace existing apk"))
        }

        val appName = runCatching { context.getString(com.bookkeeping.app.R.string.app_name) }
            .getOrDefault("Open Margin")
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("$appName ${update.apkTag ?: update.tag}")
            .setDescription(name)
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        return runCatching { manager.enqueue(request) }
    }

    /**
     * Polls DownloadManager: it only broadcasts completion, and the dialog
     * needs a percentage. Ends after the terminal state, so the caller can
     * treat the flow as a single download attempt.
     */
    fun observe(
        context: Context,
        downloadId: Long,
        update: GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable,
    ): Flow<DownloadEvent> = flow {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (manager == null) {
            emit(DownloadEvent.Failed(Failure.NO_DOWNLOAD_MANAGER))
            return@flow
        }
        while (true) {
            val status = query(manager, downloadId)
            if (status == null) {
                emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                return@flow
            }
            when (status.state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    emit(DownloadEvent.Verifying)
                    val file = apkFile(context, update)
                    when (ApkIntegrity.verify(file, update.apkSizeBytes, update.apkSha256)) {
                        ApkCheck.OK -> emit(DownloadEvent.Completed(file!!))
                        ApkCheck.SIZE_MISMATCH -> {
                            file?.let { runCatching { it.delete() } }
                            emit(DownloadEvent.Failed(Failure.SIZE_MISMATCH))
                        }
                        ApkCheck.DIGEST_MISMATCH -> {
                            file?.let { runCatching { it.delete() } }
                            emit(DownloadEvent.Failed(Failure.DIGEST_MISMATCH))
                        }
                        ApkCheck.MISSING -> emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                    }
                    return@flow
                }
                DownloadManager.STATUS_FAILED -> {
                    emit(DownloadEvent.Failed(Failure.DOWNLOAD_FAILED))
                    return@flow
                }
                else -> emit(DownloadEvent.Running(status.percent))
            }
            delay(POLL_INTERVAL_MS)
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

    private data class DownloadStatus(val state: Int, val percent: Int?)

    private fun query(manager: DownloadManager, id: Long): DownloadStatus? {
        val cursor = runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))
        }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val state = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded =
                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val percent = if (total > 0L && downloaded >= 0L) {
                ((downloaded * PERCENT_MAX) / total).toInt().coerceIn(0, PERCENT_MAX)
            } else {
                null
            }
            return DownloadStatus(state, percent)
        }
    }
}
