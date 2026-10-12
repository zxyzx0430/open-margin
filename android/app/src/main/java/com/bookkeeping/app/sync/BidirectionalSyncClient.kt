package com.bookkeeping.app.sync

import android.content.SharedPreferences
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Bidirectional transaction replication with tombstones and conflict guards. */
internal class BidirectionalSyncClient(
    private val db:BookkeepingDatabase,
    private val prefs:SharedPreferences,
    private val base:String,
    private val token:String,
    private val installationId:String,
) {
    private val dao=db.transactionDao()
    private val ownPrefix="android:$installationId:"

    private fun request(method:String,path:String,data:JSONObject?=null):Pair<Int,JSONObject>? {
        val conn=(URL("$base/api/v2/sync/$path").openConnection() as HttpURLConnection)
        try {
            conn.instanceFollowRedirects=false
            conn.requestMethod=method
            conn.connectTimeout=12000
            conn.readTimeout=15000
            conn.setRequestProperty("X-Device-Token",token)
            if(data!=null) {
                conn.doOutput=true
                conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
                conn.outputStream.use{it.write(data.toString().toByteArray(Charsets.UTF_8))}
            }
            val status=conn.responseCode
            val body=if(status==200)conn.inputStream.bufferedReader().use{it.readText()} else ""
            return status to (if(body.isNotBlank())JSONObject(body) else JSONObject())
        }catch (_:java.io.IOException){return null}
        finally{conn.disconnect()}
    }

    private suspend fun local(eventId:String):TransactionEntity? {
        dao.getByRemoteEventId(eventId)?.let { return it }
        if(!eventId.startsWith(ownPrefix))return null
        val id=eventId.removePrefix(ownPrefix).toLongOrNull() ?: return null
        val row=dao.getById(id) ?: return null
        return if(row.remoteEventId==null || row.remoteEventId==eventId)row else null
    }

    private fun serverValue(row:TransactionEntity?,id:String,revision:Long,
                            action:String,payload:JSONObject?):TransactionEntity? {
        if(action=="delete")return row?.copy(
            remoteEventId=id,remoteRevision=revision,syncDeleted=true,
            syncDirty=false,syncMutationId=null
        )
        if(payload==null)return null
        val source=payload.optString("source","OTHER").takeIf{
            it in setOf("WECHAT","ALIPAY","BANK_SMS","MANUAL","OTHER")
        } ?: "OTHER"
        val direction=payload.optString("direction","EXPENSE").takeIf{
            it=="EXPENSE" || it=="INCOME"
        } ?: "EXPENSE"
        return TransactionEntity(
            id=row?.id ?: 0,
            amountCents=payload.getLong("amount_cents"),
            currency=payload.optString("currency", "CNY"),
            type=direction,
            category=payload.optString("category","OTHER"),
            merchant=payload.optString("merchant",""),
            note=row?.note ?: "",
            source=source,
            account=row?.account,
            timestamp=payload.getLong("timestamp_ms"),
            remoteEventId=id,
            remoteRevision=revision,
            syncDirty=false,
            syncDeleted=false,
            syncModifiedMs=row?.syncModifiedMs ?: 0,
            remoteReviewStatus=payload.optString("review_status","unverified"),
            syncMutationId=null
        )
    }

    private suspend fun applyChange(change:JSONObject) {
        val id=change.getString("event_id")
        val revision=change.getLong("revision")
        val previous=local(id)
        if(previous!=null && previous.remoteRevision>=revision)return
        if(previous?.syncDirty==true){
            // Never replace an unsent local edit. Push uses the preserved base
            // revision to detect an actual concurrent edit and resolve it.
            if(previous.remoteEventId==null)dao.update(previous.copy(remoteEventId=id))
            return
        }
        val next=serverValue(previous,id,revision,change.getString("action"),
                             change.optJSONObject("record")) ?: return
        if(previous==null)dao.insert(next) else dao.update(next)
    }

    private suspend fun pull():Boolean {
        var cursor=prefs.getLong("last_pull_revision",0L)
        repeat(1000){
            val response=request("GET","changes?since=$cursor&limit=200") ?: return false
            if(response.first!=200)return false
            val doc=response.second
            val changes=doc.getJSONArray("items")
            for(i in 0 until changes.length())applyChange(changes.getJSONObject(i))
            cursor=doc.getLong("cursor")
            // Commit cursor only after the data is safely written to Room.
            if(!prefs.edit().putLong("last_pull_revision",cursor).commit())return false
            if(!doc.getBoolean("has_more"))return true
        }
        return false
    }

    private fun payload(row:TransactionEntity)=JSONObject().apply {
        put("timestamp_ms",row.timestamp)
        put("amount_cents",row.amountCents)
        put("currency",row.currency)
        put("direction",row.type)
        put("source",row.source)
        put("category",row.category)
        put("merchant",row.merchant.take(140))
    }

    private suspend fun send(row:TransactionEntity,overrideRevision:Long?=null):JSONObject? {
        val id=row.remoteEventId ?: ownPrefix + row.id
        val mutation=if(overrideRevision!=null)UUID.randomUUID().toString()
            else row.syncMutationId ?: UUID.randomUUID().toString().also{
                dao.update(row.copy(syncMutationId=it))
            }
        val op=JSONObject().apply{
            put("mutation_id",mutation)
            put("event_id",id)
            put("base_revision",overrideRevision ?: row.remoteRevision)
            put("action",if(row.syncDeleted)"delete" else "upsert")
            if(!row.syncDeleted)put("record",payload(row))
        }
        val answer=request("POST","push",JSONObject().put("operations",JSONArray().put(op)))
            ?: return null
        if(answer.first!=200)return null
        return answer.second.getJSONArray("results").getJSONObject(0)
    }

    private suspend fun push():Pair<Boolean,Int> {
        var conflicts=0
        repeat(1000){
            val pending=dao.getPendingMutations(50)
            if(pending.isEmpty())return true to conflicts
            var progress=false
            for(row in pending){
                val result=send(row) ?: return false to conflicts
                val live=dao.getById(row.id) ?: continue
                if(live.syncModifiedMs!=row.syncModifiedMs ||
                   (row.syncMutationId!=null && live.syncMutationId!=row.syncMutationId))continue
                val id=live.remoteEventId ?: ownPrefix+live.id
                when(result.getString("status")){
                    "applied"->{
                        dao.update(live.copy(remoteEventId=id,
                            remoteRevision=result.getLong("revision"),
                            syncDirty=false,syncMutationId=null))
                        progress=true
                    }
                    "conflict"->{
                        val current=result.optJSONObject("current")
                        if(current==null){conflicts++;continue}
                        val serverMs=current.optLong("changed_at_ms",0L)
                        val phoneMs=live.syncModifiedMs
                        val serverRevision=current.getLong("revision")
                        val decision=SyncConflictPolicy.decide(
                            phoneMs,serverMs,System.currentTimeMillis(),
                            prefs.getString("conflict_resolution","auto") ?: "auto"
                        )
                        if(decision==SyncConflictPolicy.Decision.KEEP_PHONE){
                            val retry=send(live,serverRevision)
                            if(retry?.optString("status")=="applied"){
                                dao.update(live.copy(remoteEventId=id,
                                    remoteRevision=retry.getLong("revision"),
                                    syncDirty=false,syncMutationId=null))
                                progress=true
                            }else conflicts++
                        }else if(decision==SyncConflictPolicy.Decision.KEEP_SERVER){
                            val updated=serverValue(live,id,serverRevision,
                                current.getString("action"),current.optJSONObject("record"))
                            if(updated!=null){dao.update(updated);progress=true}
                            else conflicts++
                        }else conflicts++
                    }
                    "before_start"->{
                        // Server explicitly rejected this row due to the configured
                        // ledger start date. Keep the unsent mutation visible and
                        // retryable; acknowledging it would silently diverge the
                        // phone totals from the server and lose the user's edit.
                        conflicts++
                    }
                    else->conflicts++
                }
            }
            if(!progress)return true to conflicts
        }
        return false to conflicts
    }

    suspend fun run():Boolean=withContext(Dispatchers.IO){
        if(!pull())return@withContext false
        val (success,conflicts)=push()
        if(!success)return@withContext false
        if(!pull())return@withContext false
        val waiting=dao.pendingCount()
        prefs.edit().putInt("pending_sync_conflicts",waiting)
            .putString("conflict_resolution",if(waiting==0)"auto"
                       else prefs.getString("conflict_resolution","auto"))
            .putString("last_result",
            if(waiting>0 || conflicts>0)
                "已同步无冲突交易；仍有 $waiting 笔修改待核对，请不要卸载应用"
            else "双向同步成功：手机和服务器交易已更新"
        ).putLong("last_success_ms",System.currentTimeMillis()).apply()
        true
    }
}
