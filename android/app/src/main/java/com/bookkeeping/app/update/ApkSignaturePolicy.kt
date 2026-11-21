package com.bookkeeping.app.update

import java.security.MessageDigest

/**
 * Signing-certificate comparison, kept free of Android types so the rule that
 * rejects a mismatching APK is covered by plain JVM tests.
 *
 * A package may carry more than one signer (key rotation / multi-signer), so
 * the comparison is between the sets of certificate fingerprints, not bytes at
 * one index. Empty input is always a rejection: an APK whose signatures could
 * not be read must never reach the installer.
 */
object ApkSignaturePolicy {
    fun matches(installed: List<ByteArray>, candidate: List<ByteArray>): Boolean {
        if (installed.isEmpty() || candidate.isEmpty()) return false
        val installedPrints = fingerprints(installed)
        val candidatePrints = fingerprints(candidate)
        if (installedPrints.isEmpty() || candidatePrints.isEmpty()) return false
        return installedPrints == candidatePrints
    }

    fun fingerprints(certificates: List<ByteArray>): Set<String> =
        certificates.mapNotNull { certificate ->
            if (certificate.isEmpty()) null else sha256(certificate)
        }.toSet()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
