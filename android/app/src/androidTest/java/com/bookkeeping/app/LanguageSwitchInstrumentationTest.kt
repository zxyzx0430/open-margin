package com.bookkeeping.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
            val english = AppLanguage.wrap(app)
            assertEquals("Open Margin", english.getString(R.string.app_name))
            assertEquals("Expense", AppLanguage.tr("支出"))
            assertEquals("Food", AppLanguage.tr("餐饮"))

            assertTrue(AppLanguage.set(app, AppLanguage.CHINESE))
            assertEquals(AppLanguage.CHINESE, AppLanguage.read(app))
            val chinese = AppLanguage.wrap(app)
            assertEquals("清衡", chinese.getString(R.string.app_name))
            assertEquals("支出", AppLanguage.tr("支出"))
            assertEquals("餐饮", AppLanguage.tr("餐饮"))
        } finally {
            AppLanguage.set(app, original)
            AppLanguage.wrap(app)
        }
    }
}
