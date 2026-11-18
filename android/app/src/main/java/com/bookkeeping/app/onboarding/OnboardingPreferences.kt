package com.bookkeeping.app.onboarding

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")

internal data class OnboardingState(val completed: Boolean, val step: Int, val locale: String)

/** Persistent progress is initialized once, after checking the existing ledger. */
@Singleton
internal class OnboardingPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun initialize(hasExistingBills: Boolean, systemLocale: String): OnboardingState {
        context.onboardingDataStore.edit { prefs ->
            if (prefs[LOCALE] == null) {
                prefs[LOCALE] = if (systemLocale.startsWith("en", ignoreCase = true)) "en" else "zh"
                prefs[COMPLETED] = hasExistingBills
                prefs[STEP] = 0
            }
        }
        return read()
    }

    suspend fun read(): OnboardingState {
        val prefs = context.onboardingDataStore.data.first()
        return OnboardingState(
            completed = prefs[COMPLETED] ?: false,
            step = (prefs[STEP] ?: 0).coerceIn(0, 7),
            locale = prefs[LOCALE] ?: "zh",
        )
    }

    suspend fun setStep(step: Int) {
        require(step in 0..7)
        context.onboardingDataStore.edit { it[STEP] = step }
    }

    suspend fun complete() {
        context.onboardingDataStore.edit { it[COMPLETED] = true }
    }

    suspend fun restart() {
        context.onboardingDataStore.edit {
            it[COMPLETED] = false
            it[STEP] = 0
        }
    }

    private companion object {
        val COMPLETED = booleanPreferencesKey("onboarding_completed")
        val STEP = intPreferencesKey("onboarding_step")
        val LOCALE = stringPreferencesKey("onboarding_locale")
    }
}
