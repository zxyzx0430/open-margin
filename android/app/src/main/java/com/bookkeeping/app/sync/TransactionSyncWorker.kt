package com.bookkeeping.app.sync

import android.content.Context
import androidx.room.Room
import androidx.work.*
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.MoneyAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** New transactions are uploaded promptly; 15-minute periodic work remains fallback. */
class TransactionSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = mutex.withLock {
        withContext(Dispatchers.IO) { upload() }
    }

    private suspend fun upload(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return Result.success()
        val base = prefs.getString("url", "")?.trimEnd('/') ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (!base.startsWith("https://") || token.length < 16) {
            prefs.edit().putString("last_result", "未配置有效 HTTPS 地址或密钥").apply()
            return Result.failure()
        }
        val installation = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_id", it).commit()
        }
        val db = Room.databaseBuilder(applicationContext, BookkeepingDatabase::class.java, BookkeepingDatabase.DB_NAME)
            .addMigrations(BookkeepingDatabase.MIGRATION_1_2).build()
        try {
            if (!auditLegacyAmounts(db, prefs, base, token, installation)) return Result.retry()
            var cursor = prefs.getLong("last_synced_id", 0L)
            repeat(30) {
                val batch = db.transactionDao().getUnsynced(cursor, 100)
                if (batch.isEmpty()) {
                    val ok=BidirectionalSyncClient(db,prefs,base,token,installation).run()
                    if (!ok) {
                        prefs.edit().putString("last_result", "双向同步暂不可用，将自动重试").apply()
                        return Result.retry()
                    }
                    return Result.success()
                }
                val events = JSONArray()
                for (t in batch) {
                    val source = t.source.uppercase()
                    if (source !in SOURCES || t.type !in setOf("EXPENSE", "INCOME") || t.amountCents <= 0) {
                        prefs.edit().putString("last_result", "本地交易 ${t.id} 数据异常，无法同步").apply()
                        return Result.failure()
                    }
                    events.put(JSONObject().apply {
                        put("event_id", "android:$installation:${t.id}")
                        put("timestamp_ms", t.timestamp)
                        put("amount_cents", t.amountCents)
                        put("currency", "CNY")
                        put("direction", t.type)
                        put("source", source)
                        put("category", t.category)
                        put("merchant", t.merchant.take(140))
                    })
                }
                val response = postBatch("$base/api/v1/transactions/batch", token, events)
                if (response == null) {
                    prefs.edit().putString("last_result", "网络或服务器异常，将自动重试").apply()
                    return Result.retry()
                }
                if (response.first == 401 || response.first == 403) {
                    prefs.edit().putString("last_result", "同步授权失败，请核对设备密钥").apply()
                    return Result.failure()
                }
                if (response.first != 200) {
                    prefs.edit().putString("last_result", "服务器返回 ${response.first}，稍后重试").apply()
                    return if (response.first in 400..499) Result.failure() else Result.retry()
                }
                if (JSONObject(response.second).optInt("accepted", -1) != events.length()) {
                    prefs.edit().putString("last_result", "服务器确认条数不一致，将重试").apply()
                    return Result.retry()
                }
                cursor = batch.last().id
                if (!prefs.edit().putLong("last_synced_id", cursor)
                    .putLong("last_success_ms", System.currentTimeMillis())
                    .putString("last_result", "已上传，正在拉取最新变更").commit()) return Result.retry()
            }
            return Result.retry()
        } catch (_: Exception) {
            prefs.edit().putString("last_result", "同步异常，将自动重试").apply()
            return Result.retry()
        } finally { db.close() }
    }

    /**
     * Repair ONLY provable one-cent truncation errors retained in the local
     * notification excerpt. For a previously synced event, the server performs
     * a guarded idempotent correction before we update the local copy.
     */
    private suspend fun auditLegacyAmounts(
        db: BookkeepingDatabase,
        prefs: android.content.SharedPreferences,
        base: String,
        token: String,
        installation: String,
    ): Boolean {
        if (prefs.getBoolean("rounding_audit_v2_complete", false)) return true
        val candidates = db.transactionDao().getAutoCapturedForRoundingAudit()
        var cursor = prefs.getLong("last_synced_id", 0L)
        val symbol = Regex("""[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)""")
        val yuan = Regex("""(\\d+(?:\\.\\d{1,2})?)\\s*元""")
        for (entry in candidates) {
            val prefix = when(entry.source) {
                "BANK_SMS" -> "[银行短信] "
                "WECHAT", "ALIPAY" -> "[自动抓取] "
                else -> continue
            }
            if (!entry.note.startsWith(prefix)) continue
            val excerpt=entry.note.removePrefix(prefix)
            val decimal = symbol.find(excerpt)?.groupValues?.get(1)
                ?: yuan.find(excerpt)?.groupValues?.get(1)
                ?: continue
            val exact = MoneyAmount.cents(decimal) ?: continue
            if (exact != entry.amountCents + 1L) continue
            if (entry.id <= cursor) {
                val body = JSONObject().apply {
                    put("event_id", "android:$installation:${entry.id}")
                    put("old_cents", entry.amountCents)
                    put("corrected_cents", exact)
                }
                val answer=postJson("$base/api/v1/transactions/correct-rounding",token,body) ?: return false
                if(answer.first != 200) {
                    prefs.edit().putString("last_result", "旧交易 ${entry.id} 金额待核对；未擅自修改").apply()
                    if (answer.first >= 500) return false
                    continue
                }
                val result=JSONObject(answer.second)
                if(!result.optBoolean("ok",false)) return false
                if(result.optBoolean("not_found",false)) {
                    cursor=minOf(cursor,entry.id-1)
                    if(!prefs.edit().putLong("last_synced_id",cursor).commit()) return false
                }
            }
            db.transactionDao().update(entry.copy(amountCents=exact))
        }
        prefs.edit().putBoolean("rounding_audit_v2_complete", true).apply()
        return true
    }

    private fun postJson(url:String, token:String, payload:JSONObject):Pair<Int,String>? {
        val conn=(URL(url).openConnection() as HttpURLConnection)
        try{
            conn.instanceFollowRedirects=false
            conn.requestMethod="POST"
            conn.connectTimeout=12000
            conn.readTimeout=12000
            conn.doOutput=true
            conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
            conn.setRequestProperty("X-Device-Token",token)
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code=conn.responseCode
            val body=if(code==200)conn.inputStream.bufferedReader().use{it.readText()} else ""
            return code to body
        }catch (_:java.io.IOException){return null}
        finally{conn.disconnect()}
    }

    private fun postBatch(url: String, token: String, events: JSONArray): Pair<Int, String>? {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Device-Token", token)
            conn.outputStream.use { it.write(JSONObject().put("events", events).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
            return code to body
        } catch (_: java.io.IOException) {
            return null
        } finally { conn.disconnect() }
    }

    companion object {
        const val PREFS = "finance_sync"
        private const val PERIODIC = "finance_periodic_sync"
        private const val IMMEDIATE = "finance_immediate_sync"
        private val mutex = Mutex()
        private val SOURCES = setOf("WECHAT", "ALIPAY", "BANK_SMS", "MANUAL", "OTHER")

        /** Called after the Room insert has completed. */
        fun requestImmediate(context: Context) {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)) return
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val job = OneTimeWorkRequestBuilder<TransactionSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.APPEND_OR_REPLACE, job)
        }

        fun configure(context: Context, url: String, token: String, enabled: Boolean) {
            val normalized = url.trim().trimEnd('/')
            require(!enabled || (normalized.startsWith("https://") && token.length >= 16)) {
                "需要有效 HTTPS 地址及至少16位的设备密钥"
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            require(SyncEndpointPolicy.canChangeServer(
                prefs.getString("url", "") ?: "", normalized,
                prefs.getLong("last_synced_id", 0L),
                prefs.getLong("last_pull_revision", 0L),
                prefs.getLong("last_success_ms", 0L)
            )) {
                "已同步账本不能直接更换服务器；请先迁移数据库，避免跨服务器丢失或重复交易"
            }
            prefs.edit().putString("url", normalized).putString("token", token)
                .putBoolean("enabled", enabled).apply()
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(IMMEDIATE)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val periodic = PeriodicWorkRequestBuilder<TransactionSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            requestImmediate(context)
        }
    }
}
