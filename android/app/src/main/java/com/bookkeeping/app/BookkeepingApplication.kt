package com.bookkeeping.app

import android.app.Application
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.data.settings.SettingsPreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BookkeepingApplication : Application() {
    @Inject lateinit var settings: SettingsPreferences

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            MerchantRules.replace(settings.merchantRulesBlocking())
        }
    }
}
