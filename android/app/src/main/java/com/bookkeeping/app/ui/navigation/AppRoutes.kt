package com.bookkeeping.app.ui.navigation

/**
 * 非底部 tab 的路由集中放这里。
 *
 * 以前路由字符串直接写在 [RootNavigation] 的 composable 里，入口一多就出现
 * 「设置页点导入账单 → 顺手跳账单 tab」这种副作用跳转。把路由收成常量后，
 * 谁能跳、跳到哪，一眼能查；[BottomNavItem.ALL] 之外的路由一律不进底部导航。
 */
object AppRoutes {
    /** 账单页保留独立导入页面；设置页使用导入 / 导出面板。 */
    const val BILL_IMPORT = "bill-import"

    /** 商户分类记忆是全屏子页：从设置进入，返回回设置。 */
    const val MERCHANT_RULES = "merchant-rules"
    const val MERCHANT_RULES_CLEANUP = "merchant-rules?cleanup=true"
    const val MERCHANT_RULES_ROUTE = "merchant-rules?cleanup={cleanup}"
    const val MERCHANT_RULES_CLEANUP_ARG = "cleanup"

    /** 同步状态与配置使用同一独立页面，返回回到原设置页。 */
    const val SYNC_SETTINGS = "sync-settings"

    /** 待处理冲突独立页：只列冲突，可逐笔处理。 */
    const val PENDING_CONFLICTS = "pending-conflicts"

    /** Per-bill diagnostic history remains device-private and is not a bottom tab. */
    const val TRANSACTION_DIAGNOSTICS = "transaction-diagnostics"

    const val TRANSACTION_DETAIL = "transaction/{id}"
    const val TRANSACTION_ID = "id"

    /** 全部订阅管理独立子页：可从日历、统计、预算进入，返回回原页。 */
    const val SUBSCRIPTIONS_ROUTE = "subscriptions?pendingCancel={pendingCancel}"
    const val SUBSCRIPTIONS_PENDING_CANCEL_ARG = "pendingCancel"

    fun subscriptions(filterPendingCancel: Boolean = false): String =
        "subscriptions?pendingCancel=$filterPendingCancel"

    fun transactionDetail(id: Long): String = "transaction/$id"
}
