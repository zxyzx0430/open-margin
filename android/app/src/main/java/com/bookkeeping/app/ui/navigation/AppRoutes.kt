package com.bookkeeping.app.ui.navigation

/**
 * 非底部 tab 的路由集中放这里。
 *
 * 以前路由字符串直接写在 [RootNavigation] 的 composable 里，入口一多就出现
 * 「设置页点导入账单 → 顺手跳账单 tab」这种副作用跳转。把路由收成常量后，
 * 谁能跳、跳到哪，一眼能查；[BottomNavItem.ALL] 之外的路由一律不进底部导航。
 */
object AppRoutes {
    /** 账单导入是独立页面：从哪个 tab 进来，关掉后就回到那个 tab。 */
    const val BILL_IMPORT = "bill-import"

    /** 商户分类记忆是全屏子页：从设置进入，返回回设置。 */
    const val MERCHANT_RULES = "merchant-rules"

    /** 同步状态与配置使用同一独立页面，返回回到原设置页。 */
    const val SYNC_SETTINGS = "sync-settings"

    const val TRANSACTION_DETAIL = "transaction/{id}"
    const val TRANSACTION_ID = "id"

    fun transactionDetail(id: Long): String = "transaction/$id"
}
