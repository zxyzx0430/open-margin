package com.bookkeeping.app.voice.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资产管线一致性：Kotlin 常量、下载脚本、.gitignore、CI 缓存键必须说同一件事。
 *
 * 这些值任何一处写错，后果都不是编译错误，而是「APK 装到手机上才报模型损坏」。
 * 这里把它们钉在一起：改模型时四处一起红，逼着人同时改。
 */
class AsrModelPipelineTest {

    private val repoRoot: File = findRepoRoot()

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "android/app/download-asr-model.sh").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（android/app/download-asr-model.sh）")
    }

    private fun scriptValue(script: String, key: String): String {
        val match = Regex("^$key=\"?([^\"\\n]+)\"?$", RegexOption.MULTILINE).find(script)
            ?: error("download-asr-model.sh 里没有 $key")
        return match.groupValues[1].trim()
    }

    @Test
    fun downloadScriptAgreesWithSpec() {
        val script = File(repoRoot, "android/app/download-asr-model.sh").readText()
        val spec = AsrModelSpec.DEFAULT
        val model = spec.files.first { it.name == AsrModelSpec.MODEL }
        val tokens = spec.files.first { it.name == AsrModelSpec.TOKENS }

        assertEquals(AsrModelSpec.MODEL, scriptValue(script, "MODEL_NAME"))
        assertEquals(model.bytes.toString(), scriptValue(script, "MODEL_BYTES"))
        assertEquals(model.sha256, scriptValue(script, "MODEL_SHA256"))
        assertEquals(AsrModelSpec.TOKENS, scriptValue(script, "TOKENS_NAME"))
        assertEquals(tokens.bytes.toString(), scriptValue(script, "TOKENS_BYTES"))
        assertEquals(tokens.sha256, scriptValue(script, "TOKENS_SHA256"))
        assertTrue(
            "脚本必须下载到 spec.assetDir 指向的目录",
            script.contains("assets/asr/\${MODEL_ID}"),
        )
    }

    @Test
    fun modelAssetsAreGitIgnored() {
        val ignore = File(repoRoot, ".gitignore").readText()
        assertTrue(
            "模型 assets 目录必须进 .gitignore（71MB 不能进 git）",
            ignore.contains("/android/app/src/main/assets/asr/"),
        )
        assertTrue(
            "sherpa-onnx AAR 必须进 .gitignore（36MB 不能进 git）",
            ignore.contains("/android/app/libs/sherpa-onnx-*.aar"),
        )
    }

    @Test
    fun ciCachesAndRechecksTheSameBytes() {
        val workflow = File(repoRoot, ".github/workflows/android-build.yml").readText()
        val spec = AsrModelSpec.DEFAULT
        for (file in spec.files) {
            val hits = Regex(Regex.escape(file.sha256)).findAll(workflow).count()
            assertEquals("android-build.yml 的缓存键必须包含 ${file.name} 的 sha256", 1, hits)
        }
        assertTrue(
            "CI 必须缓存模型目录，否则每次构建都重下 71MB",
            workflow.contains("android/app/src/main/assets/asr"),
        )
        assertTrue(
            "CI 必须在构建后再用 --check 复核落盘资产",
            workflow.contains("bash app/download-asr-model.sh --check"),
        )
        assertTrue(
            "CI 必须验证 APK 里真的有模型和 sherpa 原生库",
            workflow.contains("assets/${spec.assetDir}/${AsrModelSpec.MODEL}") &&
                workflow.contains("lib/arm64-v8a/libsherpa-onnx-jni.so"),
        )
    }

    @Test
    fun emulatorSmokeAlsoPreparesTheModel() {
        // 模拟器 job 也会走 :app 的 Gradle 配置：没有模型资产它连配置阶段都过不去。
        val workflow = File(repoRoot, ".github/workflows/android-emulator-smoke.yml").readText()
        assertTrue(
            "emulator smoke 必须先跑 download-asr-model.sh",
            workflow.contains("bash app/download-asr-model.sh"),
        )
        assertTrue(
            "emulator smoke 也要缓存模型，否则 45 分钟超时不够",
            workflow.contains("android/app/src/main/assets/asr"),
        )
    }
}
