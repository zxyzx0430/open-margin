package com.bookkeeping.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Reads signer certificates from the installed package and from an APK file,
 * then delegates the actual comparison to [ApkSignaturePolicy].
 *
 * `GET_SIGNING_CERTIFICATES` is required on Android 9+ so key-rotation history
 * is handled by the platform; older devices fall back to the legacy
 * `GET_SIGNATURES` flag.
 */
object ApkSignatureVerifier {
    fun matchesInstalled(context: Context, apk: File): Boolean {
        if (!apk.isFile) return false
        val installed = installedSignatures(context)
        val candidate = archiveSignatures(context, apk)
        return ApkSignaturePolicy.matches(installed, candidate)
    }

    fun installedSignatures(context: Context): List<ByteArray> = runCatching {
        val manager = context.packageManager
        val info = manager.getPackageInfo(context.packageName, signatureFlags())
        signaturesOf(info)
    }.getOrDefault(emptyList())

    fun archiveSignatures(context: Context, apk: File): List<ByteArray> = runCatching {
        val manager = context.packageManager
        val info = manager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
        if (info == null) emptyList() else signaturesOf(info)
    }.getOrDefault(emptyList())

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun signaturesOf(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
}
