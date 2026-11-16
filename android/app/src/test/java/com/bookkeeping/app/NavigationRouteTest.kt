package com.bookkeeping.app

import com.bookkeeping.app.ui.navigation.AppRoutes
import com.bookkeeping.app.ui.navigation.BottomNavItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航约束的回归测试。
 *
 * 之前设置页的「导入账单」先跳到账单 tab 再弹窗，关掉后用户被留在账单页；
 * 把导入拆成独立路由后，这条不变量必须固定住：非 tab 页面不能混进底部导航，
 * 详情路由的路径拼接要和注册的 pattern 一致（否则 SavedStateHandle 取不到 id）。
 */
class NavigationRouteTest {

    @Test
    fun billImportIsItsOwnPageAndNotABottomTab() {
        assertEquals("bill-import", AppRoutes.BILL_IMPORT)
        assertFalse(
            "导入账单不能是底部 tab：它必须从当前页面 push，关闭后 popBackStack 回原页面",
            BottomNavItem.ALL.any { it.route == AppRoutes.BILL_IMPORT },
        )
    }

    @Test
    fun transactionDetailRouteMatchesRegisteredPattern() {
        assertEquals("transaction/{id}", AppRoutes.TRANSACTION_DETAIL)
        assertEquals("transaction/42", AppRoutes.transactionDetail(42))
        assertEquals(
            AppRoutes.TRANSACTION_DETAIL.replace("{${AppRoutes.TRANSACTION_ID}}", "42"),
            AppRoutes.transactionDetail(42),
        )
    }

    @Test
    fun bottomTabsAreUniquePlainRoutes() {
        val routes = BottomNavItem.ALL.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        // tab 路由必须是单段、无参数的：带 {id} 的叶子页混进来，就会出现
        // 「点一下跳错页面」这类问题。
        assertTrue(
            "底部 tab 不能是带参数的叶子路由：$routes",
            routes.all { route -> !route.contains("/") && !route.contains("{") },
        )
        assertFalse(AppRoutes.TRANSACTION_DETAIL in routes)
    }
}
