package com.bookkeeping.app.voice.asr

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型目录校验的行为约定：缺文件、字节数不符、哈希不符、`.ready` 缺失或版本过期
 * 都必须被拦住；否则 native 会拿到半个模型直接崩在底层。
 */
class AsrModelVerifierTest {

    private val root: File = Files.createTempDirectory("asr-model-verifier").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(files: Map<String, ByteArray>): AsrModelSpec {
        val entries = files.map { (name, bytes) ->
            File(root, name).writeBytes(bytes)
            AsrModelFile(name, bytes.size.toLong(), AsrModelVerifier.sha256(File(root, name)))
        }
        return AsrModelSpec(
            id = "test-model",
            version = "v1",
            assetDir = "asr/test",
            files = entries,
        )
    }

    private fun defaultSpec(): AsrModelSpec = write(
        mapOf(
            "model.int8.onnx" to ByteArray(4096) { (it % 251).toByte() },
            "tokens.txt" to "a 0\nb 1\n".toByteArray(),
        ),
    )

    private fun markReady(spec: AsrModelSpec) {
        File(root, AsrModelVerifier.READY_MARKER).writeText(AsrModelVerifier.markerContent(spec))
    }

    @Test
    fun acceptsCompleteDirectory() {
        val spec = defaultSpec()
        markReady(spec)
        assertTrue(AsrModelVerifier.verify(root, spec).ok)
    }

    @Test
    fun rejectsMissingModelFile() {
        val spec = defaultSpec()
        markReady(spec)
        File(root, "model.int8.onnx").delete()
        val result = AsrModelVerifier.verify(root, spec)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("model.int8.onnx"))
    }

    @Test
    fun rejectsSizeMismatch() {
        val spec = defaultSpec()
        markReady(spec)
        File(root, "tokens.txt").appendText("\n")
        val result = AsrModelVerifier.verify(root, spec)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("字节数不符"))
    }

    @Test
    fun rejectsHashMismatch() {
        val spec = defaultSpec()
        markReady(spec)
        val tokens = File(root, "tokens.txt")
        val bytes = tokens.readBytes()
        bytes[0] = (bytes[0] + 1).toByte() // 长度不变，内容变了
        tokens.writeBytes(bytes)
        val result = AsrModelVerifier.verify(root, spec, deep = true)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("sha256"))
    }

    @Test
    fun shallowCheckOnlyComparesSizeAndMarker() {
        val spec = defaultSpec()
        markReady(spec)
        val tokens = File(root, "tokens.txt")
        val bytes = tokens.readBytes()
        bytes[0] = (bytes[0] + 1).toByte()
        tokens.writeBytes(bytes)
        // 每次启动只做浅校验（省 71MB 的哈希时间），坏位留给 native 自己报错；
        // 安装时才是深校验。这里把这个取舍钉死，避免以后被误改成“永远深校验”。
        assertTrue(AsrModelVerifier.verify(root, spec, deep = false).ok)
        assertFalse(AsrModelVerifier.verify(root, spec, deep = true).ok)
    }

    @Test
    fun rejectsMissingReadyMarker() {
        val spec = defaultSpec()
        val result = AsrModelVerifier.verify(root, spec)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains(AsrModelVerifier.READY_MARKER))
    }

    @Test
    fun rejectsStaleVersionMarker() {
        val spec = defaultSpec()
        File(root, AsrModelVerifier.READY_MARKER).writeText("version=old-model\n")
        val result = AsrModelVerifier.verify(root, spec)
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("版本"))
    }

    @Test
    fun rejectsMissingDirectory() {
        val spec = defaultSpec()
        val result = AsrModelVerifier.verify(File(root, "not-installed"), spec)
        assertFalse(result.ok)
    }

    @Test
    fun markerContentCarriesVersionAndHashes() {
        val spec = defaultSpec()
        val marker = AsrModelVerifier.markerContent(spec)
        assertTrue(marker.lines().any { it == "version=v1" })
        for (file in spec.files) {
            assertTrue(marker.contains("${file.name}=${file.sha256}"))
        }
        assertEquals(3, marker.trim().lines().size)
    }
}
