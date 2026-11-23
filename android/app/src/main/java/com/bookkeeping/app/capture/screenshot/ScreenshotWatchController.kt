package com.bookkeeping.app.capture.screenshot

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.bookkeeping.app.data.diagnostics.ServiceHealthLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watches newly added MediaStore images and imports the ones that look like
 * screenshots. It never enumerates the album, never looks at old rows, and only
 * runs after the user turns the setting on and grants photo access.
 *
 * The content observer is the fast path, never the only path: MIUI silently
 * drops MediaStore callbacks, after which a purely passive watcher stays alive
 * but deaf forever. [catchUp] therefore re-reads the ids that arrived after the
 * last one it handled, driven by an `_ID` watermark instead of a timestamp.
 */
object ScreenshotWatchController {

    enum class State { UNSUPPORTED, OFF, PERMISSION_MISSING, PERMISSION_PARTIAL, ACTIVE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    /** Only one fallback scan at a time; the next trigger calls it again. */
    private val catchingUp = AtomicBoolean(false)
    private val catchUpAgain = AtomicBoolean(false)

    fun state(context: Context): State {
        val state = ScreenshotWatchPermissionPolicy.state(
            available = ScreenshotWatchSettings.isAvailable(),
            enabled = ScreenshotWatchSettings.isEnabled(context),
            access = permission(context),
        )
        return State.valueOf(state.name)
    }

    /** Idempotent: safe to call on every app start and after the user toggles it. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (state(appContext) != State.ACTIVE ||
            !ScreenshotWatchPermissionPolicy.mayWatch(permission(appContext))
        ) {
            stop(appContext)
            return
        }
        if (observer != null) return
        val handler = Handler(Looper.getMainLooper())
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Some ROMs only report a collection URI. A single sequential
                // catch-up avoids observer/worker races and OCRing the same id twice.
                catchUp(appContext)
            }
        }
        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver,
            )
            observer = contentObserver
        }
    }

    fun stop(context: Context) {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    /**
     * Re-evaluate after the permission prompt or the settings toggle. The
     * watermark is (re)established here as well, so the window between "user
     * turned it on" and "first MediaStore callback" is not a blind spot.
     */
    fun refresh(context: Context) {
        stop(context)
        start(context)
        catchUp(context)
    }

    /**
     * Fallback scan: import up to [MAX_CATCH_UP_IMAGES] screenshots whose
     * MediaStore `_ID` is newer than the last one handled.
     *
     * Unlike the observer path this does not look at DATE_ADDED: the watermark
     * already proves the row is new to us, and an old row with a fresh id (a
     * restored backup) must not be dropped just because its timestamp is older
     * than this process. The first scan only records the current maximum id, so
     * turning the watcher on never OCRs the album.
     */
    fun catchUp(context: Context) {
        val appContext = context.applicationContext
        if (state(appContext) != State.ACTIVE) return
        if (!catchingUp.compareAndSet(false, true)) {
            catchUpAgain.set(true)
            return
        }
        scope.launch {
            try {
                do {
                    catchUpAgain.set(false)
                    runCatching { scanMissed(appContext) }
                } while (catchUpAgain.getAndSet(false))
            } finally {
                catchingUp.set(false)
                // Catch the race between the last scan and clearing catchingUp.
                if (catchUpAgain.getAndSet(false)) catchUp(appContext)
            }
        }
    }

    private suspend fun scanMissed(context: Context) {
        val stored = ScreenshotWatchSettings.lastScannedId(context)
        val maxId = maxImageId(context) ?: return
        if (stored == null) {
            val initial = resolveBaselineId(null, maxId)
            ScreenshotWatchSettings.setLastScannedId(context, initial)
            ServiceHealthLog.record(
                context = context,
                channel = ServiceHealthLog.CHANNEL_SCREENSHOT,
                event = ServiceHealthLog.EVENT_SCAN_MISSED,
                summary = "初始化相册监听水位: baseline=$initial",
            )
            return
        }
        val lastScannedId = resolveBaselineId(stored, maxId)
        if (lastScannedId != stored) ScreenshotWatchSettings.setLastScannedId(context, lastScannedId)
        val ids = nextIdsToScan(
            lastScannedId = lastScannedId,
            availableIds = imageIdsAfter(context, lastScannedId),
            limit = MAX_CATCH_UP_IMAGES,
        )
        var scanned = 0
        var skipped = 0
        var unreadableRetries = 0
        var unreadRows = 0
        var watermark = lastScannedId
        for (id in ids) {
            // 只有真的读到这一行才推进水位；读不到（相册行还没落盘）就留着下次再试，
            // 后面的 _ID 照常前进，不会因为一张坏行把整个监听卡死。
            val result = runCatching { ingestNow(context, id) }
                .getOrElse { IngestAttemptResult(read = false, disposition = "error", detail = it.javaClass.simpleName) }
            if (result.read) {
                watermark = watermarkAfterMediaRow(watermark, id, result.read)
                ScreenshotWatchSettings.setLastScannedId(context, watermark)
                if (result.ingested) scanned++ else skipped++
                if (result.retriedUnreadable) unreadableRetries++
            } else {
                unreadRows++
                break // preserve contiguous watermark until pending row is complete
            }
        }
        ServiceHealthLog.record(
            context = context,
            channel = ServiceHealthLog.CHANNEL_SCREENSHOT,
            event = ServiceHealthLog.EVENT_SCAN_MISSED,
            summary = "相册补扫 $lastScannedId->$maxId: scanned=$scanned, skipped=$skipped, retry=$unreadableRetries",
            detail = if (unreadRows > 0) "unread=$unreadRows" else null,
        )
    }

    private fun handleCandidate(context: Context, uri: Uri) {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return
        val scanned = ScreenshotWatchSettings.lastScannedId(context)
        if (scanned != null && id <= scanned) return
        if (!inFlight.add(id)) return
        scope.launch {
            try {
                val result = runCatching { ingestNow(context, id, enforceFreshness = true) }
                    .getOrElse { IngestAttemptResult(read = false, disposition = "error", detail = it.javaClass.simpleName) }
                if (result.read) {
                    val current = ScreenshotWatchSettings.lastScannedId(context)
                    if (current == null || id > current) {
                        ScreenshotWatchSettings.setLastScannedId(context, id)
                    }
                }
                if (result.read || result.disposition != "row_missing") {
                    ServiceHealthLog.record(
                        context = context,
                        channel = ServiceHealthLog.CHANNEL_SCREENSHOT,
                        event = ServiceHealthLog.EVENT_CANDIDATE_INTAKE,
                        summary = "相册新图 id=$id: ${result.disposition}",
                        detail = result.detail,
                    )
                }
            } finally {
                inFlight.remove(id)
            }
        }
    }
    private data class IngestAttemptResult(
        val read: Boolean,
        val ingested: Boolean = false,
        val retriedUnreadable: Boolean = false,
        val disposition: String,
        val detail: String? = null,
    )

    /**
     * One MediaStore row → one intake. Shared by the observer and the fallback
     * scan so both paths skip the same things: plain photos, this app's own
     * screenshots, and (observer only) rows older than this process.
     *
     * Returns [IngestAttemptResult.read] = true when the row was read, i.e. when
     * "did not import" was a decision and not a missing row; the fallback scan
     * only advances its watermark on a read row.
     */
    private suspend fun ingestNow(context: Context, id: Long): IngestAttemptResult {
        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val row = readRow(context, contentUri)
            ?: return IngestAttemptResult(read = false, disposition = "row_missing")
        if (!isMediaRowReady(row.isPending)) {
            return IngestAttemptResult(read = false, disposition = "pending")
        }
        if (!looksLikeScreenshot(row.name, row.relativePath)) {
            return IngestAttemptResult(read = true, disposition = "skipped_non_screenshot")
        }
        // 本 app 自己的界面（详情页角标之类）不是交易：连 OCR 都不跑，
        // 否则「未同步」会被当成商户名生成一张假候选。
        if (isOwnAppScreenshot(row.name, context.packageName)) {
            return IngestAttemptResult(read = true, disposition = "skipped_own_app")
        }
        var retried = false
        val finalOutcome = ScreenshotIntakeCoordinator(context).use { coordinator ->
            val outcome = coordinator.ingest(contentUri, IntakeSource.MEDIASTORE)
            if (outcome.failure == FailureReason.UNREADABLE) {
                retried = true
                kotlinx.coroutines.delay(450L)
                coordinator.ingest(contentUri, IntakeSource.MEDIASTORE)
            } else {
                outcome
            }
        }
        val summary = buildString {
            append("booked=${finalOutcome.booked}, pending=${finalOutcome.pending}, ignored=${finalOutcome.ignored}")
            if (finalOutcome.repeatImage) append(", repeat=true")
            finalOutcome.failure?.let { append(", failure=${it.name}") }
            if (retried) append(", retried=UNREADABLE")
        }
        return IngestAttemptResult(
            read = true,
            ingested = true,
            retriedUnreadable = retried,
            disposition = if (finalOutcome.failure != null) "failed" else "ingested",
            detail = summary,
        )
    }

    private data class ImageRow(
        val name: String?,
        val relativePath: String?,
        val isPending: Int,
    )

    private fun readRow(context: Context, uri: Uri): ImageRow? {
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
            projection += MediaStore.Images.Media.IS_PENDING
        }
        return runCatching {
            context.contentResolver.query(includePending(uri), projection.toTypedArray(), null, null, null)?.use {
                if (!it.moveToFirst()) return@use null
                val pendingIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                } else {
                    -1
                }
                // File still being flushed by the OEM screenshot service: wait for pending=0 callback.
                if (pendingIndex >= 0 && it.getInt(pendingIndex) != 0) return@use null
                val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                ImageRow(
                    name = if (nameIndex >= 0) it.getString(nameIndex) else null,
                    relativePath = if (pathIndex >= 0) it.getString(pathIndex) else null,
                    isPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        it.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                            .takeIf { index -> index >= 0 }?.let { index -> it.getInt(index) } ?: 0
                    } else 0,
                )
            }
        }.getOrNull()
    }

    /** Highest image `_ID` currently in MediaStore, or null when the query fails. */
    @Suppress("DEPRECATION")
    private fun includePending(uri: Uri): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setIncludePending(uri) else uri

    private fun maxImageId(context: Context): Long? = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media._ID} DESC",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }.getOrNull()

    /** Ids above the watermark, oldest first; capped so one scan stays bounded. */
    private fun imageIdsAfter(context: Context, lastScannedId: Long): List<Long> = runCatching {
        context.contentResolver.query(
            includePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media._ID} > ?",
            arrayOf(lastScannedId.toString()),
            "${MediaStore.Images.Media._ID} ASC",
        )?.use { cursor ->
            buildList {
                while (size < MAX_QUERY_ROWS && cursor.moveToNext()) add(cursor.getLong(0))
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** Folder or file name must say "screenshot"; plain camera photos are skipped. */
    fun looksLikeScreenshot(name: String?, relativePath: String?): Boolean {
        val haystack = "${name.orEmpty()} ${relativePath.orEmpty()}".lowercase()
        return SCREENSHOT_HINTS.any(haystack::contains) ||
            name.orEmpty().startsWith("SVID_", ignoreCase = true)
    }

    private fun permission(context: Context): ScreenshotWatchPermissionPolicy.Access {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val full = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES,
            ) == PackageManager.PERMISSION_GRANTED
            if (full) return ScreenshotWatchPermissionPolicy.Access.FULL
            val partial = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
            return if (partial) ScreenshotWatchPermissionPolicy.Access.PARTIAL else ScreenshotWatchPermissionPolicy.Access.NONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_IMAGES,
                ) == PackageManager.PERMISSION_GRANTED
            ) ScreenshotWatchPermissionPolicy.Access.FULL else ScreenshotWatchPermissionPolicy.Access.NONE
        }
        return if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) ScreenshotWatchPermissionPolicy.Access.FULL else ScreenshotWatchPermissionPolicy.Access.NONE
    }

    private val SCREENSHOT_HINTS = listOf("screenshot", "screen_shot", "screencap", "截图", "屏幕截图")
    /** Reading more than this from a burst is pointless: one scan imports five. */
    private const val MAX_QUERY_ROWS = 64
}

/**
 * How many screenshots one fallback scan may import. The first scan only takes a
 * baseline watermark, so this cap is about bursts, not about the album.
 */
internal const val MAX_CATCH_UP_IMAGES = 5

/** Pending images are not readable and must never advance the watermark. */
internal fun isMediaRowReady(isPending: Int): Boolean = isPending != 1

internal fun watermarkAfterMediaRow(previous: Long, rowId: Long, read: Boolean): Long =
    if (read && rowId > previous) rowId else previous

/** The applicationId prefix shared by the Play, GitHub and `.sync2` builds. */
private const val OWN_PACKAGE_PREFIX = "com.bookkeeping.app"

/**
 * True for a screenshot of this app's own UI, e.g.
 * `Screenshot_2026-11-21-17-18-00-590_com.bookkeeping.app.sync2.jpg`.
 *
 * The file name carries the package that owned the foreground window, so both
 * the fixed prefix and [packageName] are checked: debug / experimental builds
 * append a suffix to the applicationId, and a hard-coded prefix alone would miss
 * a future flavour. Such a screenshot is never a payment page.
 */
internal fun isOwnAppScreenshot(name: String?, packageName: String): Boolean {
    val haystack = name.orEmpty().lowercase()
    if (haystack.contains(OWN_PACKAGE_PREFIX)) return true
    val own = packageName.trim().lowercase()
    return own.isNotEmpty() && haystack.contains(own)
}

/**
 * Ids the fallback scan may handle now: strictly newer than [lastScannedId],
 * oldest first, deduplicated, at most [limit]. Pure so the ordering, the cap and
 * the idempotence are testable without a MediaStore.
 */
internal fun nextIdsToScan(
    lastScannedId: Long,
    availableIds: List<Long>,
    limit: Int = MAX_CATCH_UP_IMAGES,
): List<Long> = availableIds
    .filter { it > lastScannedId }
    .distinct()
    .sorted()
    .take(limit.coerceAtLeast(0))

/**
 * Watermark a scan should start after.
 *
 * Never scanned (null) or a watermark above the current maximum (MediaStore was
 * reset) both resolve to the current maximum: the watcher starts from now and
 * never imports older rows.
 */
internal fun resolveBaselineId(stored: Long?, maxAvailableId: Long): Long {
    val max = maxAvailableId.coerceAtLeast(0L)
    return if (stored == null || stored < 0 || stored > max) max else stored
}

/** Small helper so callers can use the coordinator without leaking an OCR engine. */
private inline fun <T> ScreenshotIntakeCoordinator.use(block: (ScreenshotIntakeCoordinator) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
