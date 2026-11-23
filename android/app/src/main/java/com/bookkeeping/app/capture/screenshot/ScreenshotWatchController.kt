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
import java.util.concurrent.ConcurrentHashMap
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
    @Volatile private var startedAtMs = 0L

    /** Only one fallback scan at a time; the next trigger calls it again. */
    private val catchingUp = AtomicBoolean(false)

    /**
     * Ids the observer path is ingesting right now. The fallback scan still
     * advances its watermark past them (the observer owns them) but must not
     * OCR the same image twice, which would also write a "repeat image"
     * warning into the diagnostics of every screenshot.
     */
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>()

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
        startedAtMs = System.currentTimeMillis()
        val handler = Handler(Looper.getMainLooper())
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // 有的 ROM 只回调集合 URI（uri == null）：那条通知里没有 _ID，
                // 兜底扫描就是唯一能认出这张新截图的路径。
                if (uri != null) handleCandidate(appContext, uri)
                // 顺手补一遍落下的 _ID：这次回调能到，不代表上一次回调也到过。
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
        if (!catchingUp.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCatching { scanMissed(appContext) }
            } finally {
                catchingUp.set(false)
            }
        }
    }

    private suspend fun scanMissed(context: Context) {
        val lastScannedId = baselineId(context) ?: return
        val ids = nextIdsToScan(
            lastScannedId = lastScannedId,
            availableIds = imageIdsAfter(context, lastScannedId),
            limit = MAX_CATCH_UP_IMAGES,
        )
        for (id in ids) {
            // 观察者正在处理这一张：它归观察者，水位照常推进，但不重复 OCR。
            if (id in inFlight) {
                ScreenshotWatchSettings.setLastScannedId(context, id)
                continue
            }
            // 只有真的读到这一行才推进水位；读不到（相册行还没落盘）就留着下次再试，
            // 后面的 _ID 照常前进，不会因为一张坏行把整个监听卡死。
            val read = runCatching { ingestNow(context, id, enforceFreshness = false) }
                .getOrDefault(false)
            if (read) ScreenshotWatchSettings.setLastScannedId(context, id)
        }
    }

    /**
     * Watermark to scan after, or null when this run has nothing to do.
     *
     * A null [ScreenshotWatchSettings.lastScannedId] means "never scanned": the
     * current maximum `_ID` becomes the baseline and no old row is imported. A
     * stored watermark above the current maximum means MediaStore was reset
     * (or the app was restored onto another phone); re-baselining keeps the
     * watcher from going deaf forever.
     */
    private fun baselineId(context: Context): Long? {
        val stored = ScreenshotWatchSettings.lastScannedId(context)
        val maxId = maxImageId(context) ?: return null
        if (stored == null) {
            ScreenshotWatchSettings.setLastScannedId(context, resolveBaselineId(null, maxId))
            return null
        }
        val baseline = resolveBaselineId(stored, maxId)
        if (baseline != stored) ScreenshotWatchSettings.setLastScannedId(context, baseline)
        return baseline
    }

    private fun handleCandidate(context: Context, uri: Uri) {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return
        inFlight.add(id)
        scope.launch {
            try {
                val read = runCatching { ingestNow(context, id, enforceFreshness = true) }
                    .getOrDefault(false)
                if (read) {
                    val current = ScreenshotWatchSettings.lastScannedId(context)
                    if (current == null || id > current) {
                        ScreenshotWatchSettings.setLastScannedId(context, id)
                    }
                }
            } finally {
                inFlight.remove(id)
            }
        }
    }

    /**
     * One MediaStore row → one intake. Shared by the observer and the fallback
     * scan so both paths skip the same things: plain photos, this app's own
     * screenshots, and (observer only) rows older than this process.
     *
     * Returns true when the row was read, i.e. when "did not import" was a
     * decision and not a missing row; the fallback scan only advances its
     * watermark on a read row.
     */
    private suspend fun ingestNow(context: Context, id: Long, enforceFreshness: Boolean): Boolean {
        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val row = readRow(context, contentUri) ?: return false
        if (!looksLikeScreenshot(row.name, row.relativePath)) return true
        // 本 app 自己的界面（详情页角标之类）不是交易：连 OCR 都不跑，
        // 否则「未同步」会被当成商户名生成一张假候选。
        if (isOwnAppScreenshot(row.name, context.packageName)) return true
        // Only the observer path uses the time gate; a delayed callback for a
        // stale row must not be imported retroactively. The fallback scan has
        // the `_ID` watermark instead and must not be blocked by wall-clock.
        if (enforceFreshness && row.addedAtMs > 0 && row.addedAtMs < startedAtMs - GRACE_MS) return true
        ScreenshotIntakeCoordinator(context).use { coordinator ->
            val outcome = coordinator.ingest(contentUri, IntakeSource.MEDIASTORE)
            if (outcome.failure == FailureReason.UNREADABLE) {
                kotlinx.coroutines.delay(450L)
                coordinator.ingest(contentUri, IntakeSource.MEDIASTORE)
            }
        }
        return true
    }

    private data class ImageRow(
        val name: String?,
        val relativePath: String?,
        val addedAtMs: Long,
    )

    private fun readRow(context: Context, uri: Uri): ImageRow? {
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
        }
        return runCatching {
            context.contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use {
                if (!it.moveToFirst()) return@use null
                val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                ImageRow(
                    name = if (nameIndex >= 0) it.getString(nameIndex) else null,
                    relativePath = if (pathIndex >= 0) it.getString(pathIndex) else null,
                    addedAtMs = if (dateIndex >= 0) it.getLong(dateIndex) * 1000 else 0L,
                )
            }
        }.getOrNull()
    }

    /** Highest image `_ID` currently in MediaStore, or null when the query fails. */
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
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
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
        return SCREENSHOT_HINTS.any(haystack::contains)
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
    private const val GRACE_MS = 30_000L
    /** Reading more than this from a burst is pointless: one scan imports five. */
    private const val MAX_QUERY_ROWS = 64
}

/**
 * How many screenshots one fallback scan may import. The first scan only takes a
 * baseline watermark, so this cap is about bursts, not about the album.
 */
internal const val MAX_CATCH_UP_IMAGES = 5

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
