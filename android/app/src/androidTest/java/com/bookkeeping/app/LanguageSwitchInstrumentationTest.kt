package com.bookkeeping.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.bookkeeping.app.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an Android emulator; verifies persistence and real resource locale. */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchInstrumentationTest {
    @Test
    fun chineseAndEnglishPersistAndLocalizeResources() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AppLanguage.read(app)
        try {
            assertTrue(AppLanguage.set(app, AppLanguage.ENGLISH))
            assertEquals(AppLanguage.ENGLISH, AppLanguage.read(app))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("Open Margin", activity.getString(R.string.app_name))
                }
            }
            assertEquals("Expense", AppLanguage.tr("支出"))
            assertEquals("Food", AppLanguage.tr("餐饮"))

            assertTrue(AppLanguage.set(app, AppLanguage.CHINESE))
            assertEquals(AppLanguage.CHINESE, AppLanguage.read(app))
            // debug 变体用 resValue 把 app_name 覆盖成「清衡测试」（和正式版并存安装），
            // 所以这里不比对完整字符串，只断言中文资源生效、用的是中文品牌名。
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val chineseName = activity.getString(R.string.app_name)
                    assertTrue("中文界面应显示中文品牌名，实际：$chineseName", chineseName.startsWith("清衡"))
                }
            }
            assertEquals("支出", AppLanguage.tr("支出"))
            assertEquals("餐饮", AppLanguage.tr("餐饮"))
        } finally {
            AppLanguage.set(app, original)
        }
    }
}
