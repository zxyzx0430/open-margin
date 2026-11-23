package com.bookkeeping.app.capture.screenshot

/**
 * 截图批量导入的纯逻辑边界：单次最多处理 10 张，超出部分截断并标记提示。
 */
object ScreenshotBatchPolicy {
    const val MAX_BATCH_IMAGES = 10

    data class Selection<T>(
        val accepted: List<T>,
        val droppedCount: Int,
    ) {
        val truncated: Boolean get() = droppedCount > 0
    }

    fun <T> capSelection(items: List<T>, maxCount: Int = MAX_BATCH_IMAGES): Selection<T> {
        val safeMax = maxCount.coerceAtLeast(0)
        if (items.size <= safeMax) {
            return Selection(accepted = items, droppedCount = 0)
        }
        return Selection(
            accepted = items.take(safeMax),
            droppedCount = items.size - safeMax,
        )
    }
}
