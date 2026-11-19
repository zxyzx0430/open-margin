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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watches newly added MediaStore images and imports the ones that look like
 * screenshots. It never enumerates the album, never looks at old rows, and only
 * runs after the user turns the setting on and grants photo access.
 */
object ScreenshotWatchController {

    enum class State { UNSUPPORTED, OFF, PERMISSION_MISSING, PERMISSION_PARTIAL, ACTIVE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null
    @Volatile private var startedAtMs = 0L

    fun state(context: Context): State {
        if (!ScreenshotWatchSettings.isAvailable()) return State.UNSUPPORTED
        if (!ScreenshotWatchSettings.isEnabled(context)) return State.OFF
        return when (permission(context)) {
            Permission.FULL -> State.ACTIVE
            Permission.PARTIAL -> State.PERMISSION_PARTIAL
            Permission.NONE -> State.PERMISSION_MISSING
        }
    }

    /** Idempotent: safe to call on every app start and after the user toggles it. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (state(appContext) != State.ACTIVE && state(appContext) != State.PERMISSION_PARTIAL) {
            stop(appContext)
            return
        }
        if (observer != null) return
        startedAtMs = System.currentTimeMillis()
        val handler = Handler(Looper.getMainLooper())
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (uri == null) return
                handleCandidate(appContext, uri)
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

    /** Re-evaluate after the permission prompt or the settings toggle. */
    fun refresh(context: Context) {
        stop(context)
        start(context)
    }

    private fun handleCandidate(context: Context, uri: Uri) {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
        }
        val row = runCatching {
            context.contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use {
                if (!it.moveToFirst()) return@use null
                val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIndex = it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val dateIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                Triple(
                    if (nameIndex >= 0) it.getString(nameIndex) else null,
                    if (mimeIndex >= 0) it.getString(mimeIndex) else null,
                    if (dateIndex >= 0) it.getLong(dateIndex) * 1000 else 0L,
                ) to if (pathIndex >= 0) it.getString(pathIndex) else null
            }
        }.getOrNull() ?: return

        val (fields, relativePath) = row
        val (name, mime, addedAtMs) = fields
        if (!looksLikeScreenshot(name, relativePath)) return
        // Only images that appeared after the watcher started; a stale row that
        // shows up in a delayed callback must not be imported retroactively.
        if (addedAtMs > 0 && addedAtMs < startedAtMs - GRACE_MS) return
        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        scope.launch {
            runCatching {
                ScreenshotIntakeCoordinator(context).use { coordinator ->
                    coordinator.ingest(contentUri, IntakeSource.MEDIASTORE)
                }
            }
        }
    }

    /** Folder or file name must say "screenshot"; plain camera photos are skipped. */
    fun looksLikeScreenshot(name: String?, relativePath: String?): Boolean {
        val haystack = "${name.orEmpty()} ${relativePath.orEmpty()}".lowercase()
        return SCREENSHOT_HINTS.any(haystack::contains)
    }

    private fun permission(context: Context): Permission {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val full = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES,
            ) == PackageManager.PERMISSION_GRANTED
            if (full) return Permission.FULL
            val partial = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
            return if (partial) Permission.PARTIAL else Permission.NONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_IMAGES,
                ) == PackageManager.PERMISSION_GRANTED
            ) Permission.FULL else Permission.NONE
        }
        return if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) Permission.FULL else Permission.NONE
    }

    private enum class Permission { FULL, PARTIAL, NONE }

    private val SCREENSHOT_HINTS = listOf("screenshot", "screen_shot", "screencap", "截图", "屏幕截图")
    private const val GRACE_MS = 30_000L
}

/** Small helper so callers can use the coordinator without leaking an OCR engine. */
private inline fun <T> ScreenshotIntakeCoordinator.use(block: (ScreenshotIntakeCoordinator) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
