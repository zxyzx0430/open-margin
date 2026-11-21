package com.bookkeeping.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSignaturePolicyTest {
    private val certificate = ByteArray(64) { index -> (index * 3).toByte() }
    private val otherCertificate = ByteArray(64) { index -> (index * 5).toByte() }

    @Test
    fun identicalCertificateIsAccepted() {
        assertTrue(
            ApkSignaturePolicy.matches(
                installed = listOf(certificate),
                candidate = listOf(certificate.copyOf()),
            )
        )
    }

    @Test
    fun differentCertificateIsRejected() {
        assertFalse(
            ApkSignaturePolicy.matches(
                installed = listOf(certificate),
                candidate = listOf(otherCertificate),
            )
        )
    }

    @Test
    fun emptySignatureIsRejected() {
        assertFalse(ApkSignaturePolicy.matches(emptyList(), listOf(certificate)))
        assertFalse(ApkSignaturePolicy.matches(listOf(certificate), emptyList()))
        assertFalse(ApkSignaturePolicy.matches(emptyList(), emptyList()))
        assertFalse(ApkSignaturePolicy.matches(listOf(ByteArray(0)), listOf(ByteArray(0))))
    }

    @Test
    fun multiSignerOrderDoesNotMatterButASubsetDoes() {
        assertTrue(
            ApkSignaturePolicy.matches(
                installed = listOf(certificate, otherCertificate),
                candidate = listOf(otherCertificate, certificate),
            )
        )
        assertFalse(
            ApkSignaturePolicy.matches(
                installed = listOf(certificate, otherCertificate),
                candidate = listOf(certificate),
            )
        )
        assertFalse(
            ApkSignaturePolicy.matches(
                installed = listOf(certificate),
                candidate = listOf(certificate, otherCertificate),
            )
        )
    }
}
