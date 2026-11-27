package com.bookkeeping.app.data.subscription

import android.content.Context
import android.content.SharedPreferences
import com.bookkeeping.app.sync.TransactionSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

object SubscriptionFxRatesCalculator {
    const val PREFS_KEY_FX_RATES = "subscription_fx_rates_json"

    val DEFAULT_RATES: Map<String, Double> = mapOf(
        "CNY" to 1.0,
        "USD" to 7.25,
        "HKD" to 0.93,
        "EUR" to 7.85,
        "JPY" to 0.048,
        "GBP" to 9.15,
    )

    fun estimateCnyCents(
        amountCents: Long,
        currency: String,
        rates: Map<String, Double> = DEFAULT_RATES,
    ): Long? {
        if (amountCents < 0L) return null
        val code = currency.trim().uppercase()
        if (code == "CNY") return amountCents
        val rate = rates[code] ?: DEFAULT_RATES[code] ?: return null
        if (!rate.isFinite() || rate <= 0.0) return null
        return BigDecimal.valueOf(amountCents)
            .multiply(BigDecimal.valueOf(rate))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    fun parseRatesJson(rawJson: String?): Map<String, Double> {
        if (rawJson.isNullOrBlank()) return DEFAULT_RATES
        val parsed = runCatching { JSONObject(rawJson) }.getOrNull() ?: return DEFAULT_RATES
        return mergeRatesJson(parsed)
    }

    fun mergeRatesJson(json: JSONObject?): Map<String, Double> {
        if (json == null) return DEFAULT_RATES
        val merged = DEFAULT_RATES.toMutableMap()
        val keys = json.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val code = rawKey.trim().uppercase()
            if (code.length != 3) continue
            val value = json.optDouble(rawKey, Double.NaN)
            if (value.isFinite() && value > 0.0) {
                merged[code] = value
            }
        }
        merged["CNY"] = 1.0
        return merged
    }

    fun encodeRatesJson(rates: Map<String, Double>): String {
        val obj = JSONObject()
        rates.forEach { (code, value) ->
            if (code.isNotBlank() && value.isFinite() && value > 0.0) {
                obj.put(code.trim().uppercase(), value)
            }
        }
        return obj.toString()
    }
}

@Singleton
class SubscriptionFxRates @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)

    private val _ratesFlow = MutableStateFlow(currentRates())
    val ratesFlow: StateFlow<Map<String, Double>> = _ratesFlow

    fun currentRates(): Map<String, Double> =
        SubscriptionFxRatesCalculator.parseRatesJson(
            prefs.getString(SubscriptionFxRatesCalculator.PREFS_KEY_FX_RATES, null),
        )

    fun updateRates(rates: Map<String, Double>) {
        prefs.edit()
            .putString(
                SubscriptionFxRatesCalculator.PREFS_KEY_FX_RATES,
                SubscriptionFxRatesCalculator.encodeRatesJson(rates),
            )
            .apply()
        _ratesFlow.value = SubscriptionFxRatesCalculator.parseRatesJson(
            SubscriptionFxRatesCalculator.encodeRatesJson(rates),
        )
    }

    suspend fun refreshIfStale(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val lastUpdated = prefs.getLong(PREFS_KEY_FX_UPDATED_MS, 0L)
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdated < FX_CACHE_TTL_MS) return@withContext false

        runCatching {
            val conn = (URL("https://open.er-api.com/v6/latest/CNY").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext false
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val ratesObj = JSONObject(body).optJSONObject("rates") ?: return@withContext false
                val merged = currentRates().toMutableMap()
                for (code in listOf("USD", "HKD", "EUR", "JPY", "GBP")) {
                    val perCny = ratesObj.optDouble(code, Double.NaN)
                    if (perCny.isFinite() && perCny > 0.0) {
                        merged[code] = 1.0 / perCny
                    }
                }
                merged["CNY"] = 1.0
                updateRates(merged)
                prefs.edit().putLong(PREFS_KEY_FX_UPDATED_MS, now).apply()
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val PREFS_KEY_FX_UPDATED_MS = "subscription_fx_rates_updated_ms"
        private const val FX_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    }

    fun updateRatesFromJson(json: JSONObject?) {
        if (json == null) return
        val merged = SubscriptionFxRatesCalculator.mergeRatesJson(json)
        updateRates(merged)
    }

    fun estimateCnyCents(amountCents: Long, currency: String): Long? =
        SubscriptionFxRatesCalculator.estimateCnyCents(amountCents, currency, currentRates())
}
