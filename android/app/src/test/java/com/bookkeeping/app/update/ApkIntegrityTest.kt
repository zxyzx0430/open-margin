package com.bookkeeping.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ApkIntegrityTest {
    private fun tempApk(content: ByteArray): File {
        val file = Files.createTempFile("open-margin-test", ".apk").toFile()
        file.writeBytes(content)
        file.deleteOnExit()
        return file
    }

    @Test fun sizeAndDigestMustMatchTheReleaseAsset() {
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val file = tempApk(bytes)
        val digest = ApkIntegrity.sha256(file)!!

        assertEquals(ApkCheck.OK, ApkIntegrity.verify(file, bytes.size.toLong(), null))
        assertEquals(ApkCheck.OK, ApkIntegrity.verify(file, bytes.size.toLong(), digest))
        assertEquals(ApkCheck.SIZE_MISMATCH, ApkIntegrity.verify(file, bytes.size + 1L, digest))
        assertEquals(ApkCheck.SIZE_MISMATCH, ApkIntegrity.verify(file, 0L, digest))
        assertEquals(
            ApkCheck.DIGEST_MISMATCH,
            ApkIntegrity.verify(file, bytes.size.toLong(), "0".repeat(64)),
        )
        assertEquals(ApkCheck.MISSING, ApkIntegrity.verify(File("/nonexistent/update.apk"), 1L, null))
        assertEquals(ApkCheck.MISSING, ApkIntegrity.verify(null, 1L, null))
    }

    @Test fun onlyWellFormedSha256DigestsAreUsed() {
        assertEquals("a".repeat(64), ApkIntegrity.normalizeSha256("sha256:" + "A".repeat(64)))
        assertNull(ApkIntegrity.normalizeSha256(null))
        assertNull(ApkIntegrity.normalizeSha256(""))
        assertNull(ApkIntegrity.normalizeSha256("md5:" + "a".repeat(32)))
        assertNull(ApkIntegrity.normalizeSha256("sha256:abc"))
        assertNull(ApkIntegrity.normalizeSha256("sha256:" + "z".repeat(64)))
    }
}
