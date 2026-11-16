package com.bookkeeping.app.voice.asr

/**
 * 识别文本的轻量整理（纯 JVM，可单测）。
 *
 * CTC 模型按 token 输出，中文会在每个词之间留空格（“花 了 三 十 块”）。解析金额时
 * 这种空格只会添乱，所以只在「空格两侧都是汉字」时删掉；`iPhone 15`、`3.5 元`
 * 这类中英/数字混排保持原样。
 */
object AsrText {

    fun normalize(raw: String): String {
        if (raw.length < 3 || raw.indexOf(' ').let { it < 0 }) return raw
        val out = StringBuilder(raw.length)
        for (index in raw.indices) {
            val current = raw[index]
            if (current == ' ' && index > 0 && index < raw.lastIndex &&
                isCjk(raw[index - 1]) && isCjk(raw[index + 1])
            ) {
                continue
            }
            out.append(current)
        }
        return out.toString()
    }

    private fun isCjk(char: Char): Boolean {
        val code = char.code
        return code in 0x4E00..0x9FFF || // 基本汉字
            code in 0x3400..0x4DBF ||    // 扩展 A
            code in 0xF900..0xFAFF       // 兼容汉字
    }
}
