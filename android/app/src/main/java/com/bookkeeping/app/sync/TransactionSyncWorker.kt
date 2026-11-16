package com.bookkeeping.app.sync

import android.content.Context
import android.content.SharedPreferences
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

internal enum class SyncResultCode(val preferenceValue: String, val label: String) {
    OK("ok", "同步正常"),
    RETRY("retry", "同步异常"),
    BLOCKED("blocked", "同步已阻断"),
    AUTH("auth", "授权失败"),
    CONFIG("config", "未配置");

    companion object {
        fun from(value: String?): SyncResultCode? =
            values().firstOrNull { it.preferenceValue == value }
    }
}

/** New transactions are uploaded promptly; 15-minute periodic work remains fallback. */
class TransactionSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = mutex.withLock {
        withContext(Dispatchers.IO) { upload() }
    }

    private suspend fun upload(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) {
            return finish(prefs, SyncResultCode.CONFIG, "同步未开启", Result.success())
        }
        val base = prefs.getString("url", "")?.trimEnd('/') ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (!base.startsWith("https://") || token.length < 16) {
            return finish(
                prefs, SyncResultCode.CONFIG, "未配置有效 HTTPS 地址或密钥", Result.failure()
            )
        }
        val installation = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_id", it).commit()
        }
        val db = try {
            BookkeepingDatabase.build(applicationContext)
        } catch (_: Exception) {
            return finish(prefs, SyncResultCode.RETRY, "同步异常，将自动重试", Result.retry())
        }
        try {
            val sync=BidirectionalSyncClient(db,prefs,base,token,installation)
            // Do not write even legacy v1 batches to a new/reset server at the
            // same URL. The old cursor belongs to the original ledger only.
            if (!sync.verifyServerIdentity()) {
                val blocker = prefs.getString("sync_security_blocker", null)
                if (blocker != null) {
                    return finish(prefs, SyncResultCode.BLOCKED, blocker, Result.failure())
                }
                return finish(
                    prefs, SyncResultCode.RETRY,
                    "无法验证服务器账本身份，将自动重试", Result.retry()
                )
            }
            val auditCode = auditLegacyAmounts(db, prefs, base, token, installation)
            if (auditCode != SyncResultCode.OK) {
                val message = prefs.getString("last_result", null)
                    ?: "同步异常，将自动重试"
                return finish(
                    prefs,
                    auditCode,
                    message,
                    if (auditCode == SyncResultCode.AUTH) Result.failure() else Result.retry(),
                )
            }
            var cursor = prefs.getLong("last_synced_id", 0L)
            repeat(30) {
                val batch = db.transactionDao().getUnsynced(cursor, 100)
                if (batch.isEmpty()) {
                    val ok=sync.run()
                    if (!ok) {
                        val blocker = prefs.getString("sync_security_blocker", null)
                        if (blocker != null) {
                            return finish(prefs, SyncResultCode.BLOCKED, blocker, Result.failure())
                        }
                        return finish(
                            prefs, SyncResultCode.RETRY,
                            "双向同步暂不可用，将自动重试", Result.retry()
                        )
                    }
                    val message = prefs.getString("last_result", "双向同步成功")
                        ?: "双向同步成功"
                    return finish(prefs, SyncResultCode.OK, message, Result.success())
                }
                val events = JSONArray()
                for (t in batch) {
                    if (!UploadPayload.isUploadable(t)) {
                        return finish(
                            prefs, SyncResultCode.BLOCKED,
                            "本地交易 ${t.id} 数据异常，无法同步", Result.failure()
                        )
                    }
                    val event = UploadPayload.v1Event(t, installation)
                    events.put(JSONObject().apply {
                        put("event_id", event.eventId)
                        put("timestamp_ms", event.timestampMs)
                        put("amount_cents", event.amountCents)
                        put("currency", event.currency)
                        put("direction", event.direction)
                        put("source", event.source)
                        put("category", event.category)
                        put("merchant", event.merchant)
                    })
                }
                val response = postBatch("$base/api/v1/transactions/batch", token, events)
                if (response == null) {
                    return finish(
                        prefs, SyncResultCode.RETRY,
                        "网络或服务器异常，将自动重试", Result.retry()
                    )
                }
                if (response.first == 401 || response.first == 403) {
                    return finish(
                        prefs, SyncResultCode.AUTH,
                        "同步授权失败，请核对设备密钥", Result.failure()
                    )
                }
                if (response.first != 200) {
                    val rejected = response.first in 400..499
                    return finish(
                        prefs,
                        if (rejected) SyncResultCode.BLOCKED else SyncResultCode.RETRY,
                        "服务器返回 ${response.first}，${if (rejected) "同步已停止" else "稍后重试"}",
                        if (rejected) Result.failure() else Result.retry(),
                    )
                }
                val confirmation = JSONObject(response.second)
                // Accepted != persisted: old API counts ignored pre-ledger-start
                // events as accepted. Never acknowledge a rejected local record.
                when (LegacyUploadAckPolicy.decide(
                    events.length(), confirmation.optInt("accepted", -1),
                    confirmation.optInt("ignored_before_start", 0)
                )) {
                    LegacyUploadAckPolicy.Decision.RETRY -> {
                        return finish(
                            prefs, SyncResultCode.RETRY,
                            "服务器确认条数不一致，将重试", Result.retry()
                        )
                    }
                    LegacyUploadAckPolicy.Decision.REJECTED_BY_LEDGER_START -> {
                        return finish(
                            prefs, SyncResultCode.BLOCKED,
                            "有交易早于服务器账本起始日，被拒绝上传；请检查日期和账本设置，记录仍保留在手机",
                            Result.failure(),
                        )
                    }
                    LegacyUploadAckPolicy.Decision.ADVANCE_CURSOR -> Unit
                }
                cursor = batch.last().id
                if (!prefs.edit().putLong("last_synced_id", cursor)
                    .putLong("last_success_ms", System.currentTimeMillis())
                    .putString("last_result", "已上传，正在拉取最新变更")
                    .putString(LAST_RESULT_CODE, SyncResultCode.RETRY.preferenceValue)
                    .commit()) {
                    return finish(
                        prefs, SyncResultCode.RETRY,
                        "同步异常，将自动重试", Result.retry()
                    )
                }
            }
            return finish(
                prefs, SyncResultCode.RETRY,
                "同步异常，将自动重试", Result.retry()
            )
        } catch (_: Exception) {
            return finish(
                prefs, SyncResultCode.RETRY,
                "同步异常，将自动重试", Result.retry()
            )
        } finally { db.close() }
    }

    private fun finish(
        prefs: SharedPreferences,
        code: SyncResultCode,
        message: String,
        result: Result,
    ): Result {
        val storedMessage = when (code) {
            SyncResultCode.BLOCKED -> if (message.contains("失败")) message
                else "同步失败，已阻断：$message"
            SyncResultCode.CONFIG -> if (message.contains("失败")) message
                else "同步配置失败：$message"
            SyncResultCode.RETRY -> if (
                message.contains("失败") || message.contains("异常") ||
                message.contains("不可用") || message.contains("401") || message.contains("403")
            ) message else "同步异常：$message"
            else -> message
        }
        prefs.edit()
            .putString("last_result", storedMessage)
            .putString(LAST_RESULT_CODE, code.preferenceValue)
            .apply()
        return result
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
    ): SyncResultCode {
        if (prefs.getBoolean("rounding_audit_v2_complete", false)) return SyncResultCode.OK
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
                val answer=postJson("$base/api/v1/transactions/correct-rounding",token,body)
                    ?: run {
                        prefs.edit().putString("last_result", "网络或服务器异常，将自动重试").apply()
                        return SyncResultCode.RETRY
                    }
                if (answer.first == 401 || answer.first == 403) {
                    prefs.edit().putString("last_result", "同步授权失败，请核对设备密钥").apply()
                    return SyncResultCode.AUTH
                }
                if(answer.first != 200) {
                    prefs.edit().putString(
                        "last_result",
                        "旧交易 ${entry.id} 金额待核对；未擅自修改${if (answer.first >= 500) "，将自动重试" else ""}"
                    ).apply()
                    if (answer.first >= 500) return SyncResultCode.RETRY
                    continue
                }
                val result=JSONObject(answer.second)
                if(!result.optBoolean("ok",false)) {
                    prefs.edit().putString("last_result", "同步异常，将自动重试").apply()
                    return SyncResultCode.RETRY
                }
                if(result.optBoolean("not_found",false)) {
                    cursor=minOf(cursor,entry.id-1)
                    if(!prefs.edit().putLong("last_synced_id",cursor).commit()) {
                        prefs.edit().putString("last_result", "同步异常，将自动重试").apply()
                        return SyncResultCode.RETRY
                    }
                }
            }
            db.transactionDao().update(entry.copy(amountCents=exact))
        }
        prefs.edit().putBoolean("rounding_audit_v2_complete", true).apply()
        return SyncResultCode.OK
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
        const val LAST_RESULT_CODE = "last_result_code"
        private const val PERIODIC = "finance_periodic_sync"
        private const val IMMEDIATE = "finance_immediate_sync"
        private val mutex = Mutex()

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
            val oldUrl=prefs.getString("url","")?.trim()?.trimEnd('/') ?: ""
            require(SyncEndpointPolicy.canChangeServer(
                prefs.getString("url", "") ?: "", normalized,
                prefs.getLong("last_synced_id", 0L),
                prefs.getLong("last_pull_revision", 0L),
                prefs.getLong("last_success_ms", 0L)
            )) {
                "已同步账本不能直接更换服务器；请先迁移数据库，避免跨服务器丢失或重复交易"
            }
            val changesServer=oldUrl.isNotBlank() && oldUrl!=normalized
            val edit=prefs.edit().putString("url", normalized).putString("token", token)
                .putBoolean("enabled", enabled)
            if(changesServer) {
                // Safe only before any successful upload/download, as enforced
                // by SyncEndpointPolicy above.
                edit.remove("sync_server_id").remove("sync_security_blocker")
            }
            edit.apply()
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
