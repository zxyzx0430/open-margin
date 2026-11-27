package com.bookkeeping.app.backup.webdav

import android.content.Context
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.backup.AndroidBackupStorage
import com.bookkeeping.app.backup.BackupManifest
import com.bookkeeping.app.backup.BackupPreview
import com.bookkeeping.app.backup.BackupRestoreOptions
import com.bookkeeping.app.backup.BackupRestoreResult
import com.bookkeeping.app.backup.FullBackupManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebDavBackupCoordinator(
    private val context: Context,
    private val storage: AndroidBackupStorage,
    private val prefs: WebDavPrefs = WebDavPrefs(context),
    private val client: WebDavClient = WebDavClient(),
) {
    private val backupManager = FullBackupManager(
        storage = storage,
        appVersion = BuildConfig.VERSION_NAME,
    )

    suspend fun testConnection(config: WebDavConfig): Int =
        client.testConnection(config)

    suspend fun backupNow(configOverride: WebDavConfig? = null): BackupManifest {
        val snap = prefs.read()
        val config = configOverride ?: WebDavConfig(snap.serverUrl, snap.username, snap.password)
        if (!config.isValid() || config.username.isBlank() || config.password.isBlank()) {
            throw WebDavException(null, "请先填写完整的网盘地址、账号和应用密码")
        }
        val watermark = computeWatermark()
        val out = ByteArrayOutputStream()
        val manifest = backupManager.exportToZip(out, includeEvidence = false)
        val fileName = buildFileName(System.currentTimeMillis())
        client.uploadZipAtomic(config, fileName, out.toByteArray())
        client.pruneOldBackups(config, keepCount = MAX_REMOTE_BACKUPS)
        val now = System.currentTimeMillis()
        prefs.recordBackupOutcome(
            timeMs = now,
            status = "备份成功（${manifest.transactionCount} 笔账单）",
            watermark = watermark,
        )
        return manifest
    }

    suspend fun runAutoBackupIfNeeded(): Boolean {
        val snap = prefs.read()
        if (!snap.autoEnabled || !snap.isConfigured) return false
        val now = System.currentTimeMillis()
        if (isSameDay(snap.lastBackupMs, now)) return false
        val currentWatermark = computeWatermark()
        if (currentWatermark == snap.lastBackupWatermark && snap.lastBackupMs > 0L) {
            return false
        }
        return runCatching {
            backupNow()
            true
        }.getOrElse { err ->
            prefs.recordBackupOutcome(
                timeMs = 0L,
                status = err.message ?: "自动备份失败",
            )
            false
        }
    }

    suspend fun listRemoteBackups(): List<WebDavBackupFile> {
        val snap = prefs.read()
        val config = WebDavConfig(snap.serverUrl, snap.username, snap.password)
        return client.listBackups(config)
    }

    suspend fun downloadAndInspect(file: WebDavBackupFile): Pair<ByteArray, BackupPreview> {
        val snap = prefs.read()
        val config = WebDavConfig(snap.serverUrl, snap.username, snap.password)
        val bytes = client.downloadBackup(config, file.url)
        val preview = ByteArrayInputStream(bytes).use { backupManager.inspectZip(it) }
        return bytes to preview
    }

    suspend fun restoreFromBytes(
        zipBytes: ByteArray,
        options: BackupRestoreOptions,
    ): BackupRestoreResult {
        if (options.overwriteExistingConfigs) {
            saveLocalSafetySnapshot()
        }
        return ByteArrayInputStream(zipBytes).use { backupManager.restoreFromZip(it, options) }
    }

    private suspend fun saveLocalSafetySnapshot() {
        runCatching {
            val dir = File(context.filesDir, "safety_backups").apply { mkdirs() }
            val safetyFile = File(dir, "pre-restore-${System.currentTimeMillis()}.zip")
            safetyFile.outputStream().use { stream ->
                backupManager.exportToZip(stream, includeEvidence = false)
            }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        }
    }

    private suspend fun computeWatermark(): String {
        val txs = storage.readTransactions().filter { !it.syncDeleted }
        val subs = storage.readSubscriptions().filter { it.deletedAtMs == null }
        val maxTxMod = txs.maxOfOrNull { maxOf(it.syncModifiedMs, it.timestamp) } ?: 0L
        val maxSubMod = subs.maxOfOrNull { it.updatedAtMs } ?: 0L
        return "${txs.size}:$maxTxMod:${subs.size}:$maxSubMod"
    }

    private fun buildFileName(timeMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timeMs))
        return "open-margin-backup-$stamp.zip"
    }

    private fun isSameDay(aMs: Long, bMs: Long): Boolean {
        if (aMs <= 0L) return false
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return fmt.format(Date(aMs)) == fmt.format(Date(bMs))
    }

    companion object {
        const val MAX_REMOTE_BACKUPS = 10
    }
}
