package com.bookkeeping.app.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型描述必须和下载脚本、native 加载路径对齐：文件名错一个字，运行期就是
 * “模型损坏”。这里把关键常量钉住，改模型时测试会先红。
 */
class AsrModelSpecTest {

    private val spec = AsrModelSpec.DEFAULT

    @Test
    fun defaultIsTheZipformerMediumCtcModel() {
        assertEquals("zipformer-medium-ctc", spec.id)
        assertEquals("chunk16-left64-int8", spec.version)
        assertEquals("asr/zipformer-medium-ctc", spec.assetDir)
        assertEquals("zipformer-medium-ctc-chunk16-left64-int8", spec.installDirName)
    }

    @Test
    fun shipsExactlyOneModelFileAndTokens() {
        assertEquals(
            listOf("ctc-chunk-16-left-64.int8.onnx", "tokens.txt"),
            spec.files.map { it.name },
        )
    }

    @Test
    fun sizesMatchTheVerifiedDownload() {
        assertEquals(74_410_051L, spec.files.first { it.name == AsrModelSpec.MODEL }.bytes)
        assertEquals(86_313L, spec.files.first { it.name == AsrModelSpec.TOKENS }.bytes)
        assertEquals(74_496_364L, spec.totalBytes)
    }

    @Test
    fun everyFileCarriesAFullSha256() {
        for (file in spec.files) {
            assertEquals("${file.name} 的 sha256 必须是 64 位十六进制", 64, file.sha256.length)
            assertTrue("${file.name} 的 sha256 必须是十六进制", file.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue("${file.name} 的字节数必须为正", file.bytes > 0)
        }
    }

    @Test
    fun fileNamesAreUnique() {
        assertEquals(spec.files.size, spec.files.map { it.name }.toSet().size)
    }
}
