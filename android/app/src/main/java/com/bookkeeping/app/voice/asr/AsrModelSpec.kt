package com.bookkeeping.app.voice.asr

/** 单个模型文件：名字、字节数、sha256。字节数用于每次启动的快速校验，sha256 用于安装时校验。 */
data class AsrModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * 一套 ASR 模型的描述：assets 位置、安装目录名、文件清单与哈希。
 *
 * 版本号进目录名（filesDir/asr/zipformer-medium-ctc-chunk16-left64-int8），换模型时
 * 旧目录不会被误用；启动时只认带 `.ready` 且版本匹配的目录。
 */
data class AsrModelSpec(
    val id: String,
    val version: String,
    val assetDir: String,
    val files: List<AsrModelFile>,
) {
    /** filesDir/asr/ 下的目录名。 */
    val installDirName: String get() = "$id-$version"

    val totalBytes: Long get() = files.sumOf { it.bytes }

    fun modelFile(dir: java.io.File): java.io.File = java.io.File(dir, MODEL)

    fun tokensFile(dir: java.io.File): java.io.File = java.io.File(dir, TOKENS)

    companion object {
        /** 单文件流式 CTC 模型（encoder + CTC head 导出在同一个 onnx 里）。 */
        const val MODEL = "ctc-chunk-16-left-64.int8.onnx"
        const val TOKENS = "tokens.txt"

        /**
         * pkufool/zipformer-medium-streaming，icefall 流式 zipformer2 CTC，INT8。
         *
         * 来源：https://huggingface.co/pkufool/zipformer-medium-streaming
         * 元数据：model_type=zipformer2、decode_chunk_len=32、vocab=8776、opset 13；
         * 因此 sherpa-onnx 侧走 OnlineZipformer2CtcModelConfig，而不是 transducer。
         * 许可 Apache-2.0。哈希与 android/app/download-asr-model.sh 中的常量必须一致。
         *
         * 只打包 chunk-16 一个变体：首字延迟最低（实测见 docs/VOICE_ASR_ENGINE.md）；
         * chunk-32 / chunk-64 体积相同，换档只改这里与脚本里的文件名/哈希。
         */
        val ZIPFORMER_MEDIUM_CTC = AsrModelSpec(
            id = "zipformer-medium-ctc",
            version = "chunk16-left64-int8",
            assetDir = "asr/zipformer-medium-ctc",
            files = listOf(
                AsrModelFile(
                    name = MODEL,
                    bytes = 74_410_051L,
                    sha256 = "7cb84949b8d4f40d72df137e2e3529a1aadfc897e5257460f720bb73c99f10f1",
                ),
                AsrModelFile(
                    name = TOKENS,
                    bytes = 86_313L,
                    sha256 = "e2f16836a4c712a9a01a1656f4610f66093fe283159883ee66b73ebb6e80364b",
                ),
            ),
        )

        /** 兼容旧名字，避免上层引用到处改。 */
        val DEFAULT: AsrModelSpec = ZIPFORMER_MEDIUM_CTC
    }
}
