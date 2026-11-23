package com.bookkeeping.app.sync

import android.content.SharedPreferences
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
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
    private val diagnostics=TransactionDiagnostics(db.transactionDiagnosticDao())
    private val syncAttemptIds=mutableMapOf<Long,String>()
    private val ownPrefix="android:$installationId:"

    private suspend fun recordSync(
        transaction:TransactionEntity,
        result:String,
        details:String,
    ) {
        val attemptId=syncAttemptIds.getOrPut(transaction.id) { TransactionDiagnostics.newAttemptId() }
        diagnostics.record(
            attemptId=attemptId,
            stage=TransactionDiagnostics.STAGE_SYNC,
            result=result,
            details=details,
            transactionId=transaction.id.takeIf { it > 0L },
        )
    }

    private suspend fun recordSyncAttempt(result:String,details:String) {
        diagnostics.record(
            stage=TransactionDiagnostics.STAGE_SYNC,
            result=result,
            details=details,
        )
    }

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

    private fun validateServerIdentity(document:JSONObject,cursor:Long):Boolean {
        val remoteId=document.optString("server_id","")
        val head=document.optLong("head_revision",-1L)
        val existing=prefs.getString("sync_server_id",null)
        val decision=SyncServerIdentityPolicy.evaluate(existing,cursor,remoteId,head)
        if(decision!=SyncServerIdentityPolicy.Decision.ACCEPT) {
            val message=when(decision) {
                SyncServerIdentityPolicy.Decision.DIFFERENT_SERVER ->
                    "服务器账本身份已变化：暂停同步，防止旧交易覆盖新数据库"
                SyncServerIdentityPolicy.Decision.CURSOR_AHEAD ->
                    "服务器数据可能回滚：同步游标超出日志末尾，暂停同步"
                SyncServerIdentityPolicy.Decision.UNKNOWN_OLD_CURSOR ->
                    "旧同步记录没有账本身份验证：需先核对数据库迁移"
                else -> "服务器缺少账本身份信息，暂停同步以保护交易"
            }
            prefs.edit().putString("sync_security_blocker",message)
                .putString("last_result",message).commit()
            return false
        }
        if(existing.isNullOrBlank()) {
            if(!prefs.edit().putString("sync_server_id",remoteId).commit())
                return false
        }
        prefs.edit().remove("sync_security_blocker").apply()
        return true
    }

    /** Guard every upload, including legacy v1 uploads and rounding repairs. */
    fun verifyServerIdentity():Boolean {
        val cursor=prefs.getLong("last_pull_revision",0L)
        val response=request("GET","changes?since=$cursor&limit=1") ?: run {
            prefs.edit().putString("last_result","同步服务器连接超时或不可达，将自动重试").commit()
            return false
        }
        if(response.first!=200) {
            val detail = when (response.first) {
                401, 403 -> "同步授权校验失败（HTTP ${response.first}），请检查同步口令"
                in 500..599 -> "同步服务器暂不可用（HTTP ${response.first}），将自动重试"
                else -> "同步校验异常（HTTP ${response.first}），将自动重试"
            }
            prefs.edit().putString("last_result", detail).commit()
            return false
        }
        return validateServerIdentity(response.second,cursor)
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
        val reviewStatus=payload.optString("review_status","unverified")
        val eventTimeNeedsConfirmation = EventTimeConfirmationPolicy.mergeRemote(
            if (payload.has("event_time_needs_confirmation") &&
                !payload.isNull("event_time_needs_confirmation")
            ) payload.optBoolean("event_time_needs_confirmation") else null,
            row?.eventTimeNeedsConfirmation ?: false,
        )
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
            remoteReviewStatus=reviewStatus,
            syncMutationId=null,
            // A resolution made on another device clears the local review marker.
            duplicateOfId=if (reviewStatus in setOf("confirmed","excluded","transfer","refund")) null
                          else row?.duplicateOfId,
            // Order IDs are local import evidence; keep them across server pulls.
            sourceTransactionId=row?.sourceTransactionId,
            eventTimeNeedsConfirmation=eventTimeNeedsConfirmation,
        )
    }

    private suspend fun applyChange(change:JSONObject) {
        val id=change.getString("event_id")
        val revision=change.getLong("revision")
        val previous=local(id)
        if(previous!=null && previous.remoteRevision>=revision){
            recordSync(previous,TransactionDiagnostics.RESULT_SUCCESS,"Stale server change ignored")
            return
        }
        if(previous?.syncDirty==true){
            // Never replace an unsent local edit. Push uses the preserved base
            // revision to detect an actual concurrent edit and resolve it.
            try {
                if(previous.remoteEventId==null)dao.update(previous.copy(remoteEventId=id))
            } catch(failure:Exception) {
                recordSync(previous,TransactionDiagnostics.RESULT_FAILURE,diagnosticError())
                throw failure
            }
            recordSync(previous,TransactionDiagnostics.RESULT_WARNING,"Remote change held because the local transaction has unsent edits")
            return
        }
        val next=serverValue(previous,id,revision,change.getString("action"),
                             change.optJSONObject("record"))
        if(next==null){
            recordSyncAttempt(
                TransactionDiagnostics.RESULT_WARNING,
                "Remote change could not be applied to a local transaction",
            )
            return
        }
        try {
            val saved = if(previous==null) {
                next.copy(id=dao.insert(next))
            } else {
                dao.update(next)
                next
            }
            recordSync(saved,TransactionDiagnostics.RESULT_SUCCESS,"Server transaction change applied locally")
        } catch(failure:Exception) {
            recordSync(previous ?: next,TransactionDiagnostics.RESULT_FAILURE,diagnosticError())
            throw failure
        }
    }

    private fun diagnosticError(): String = "Sync operation failed"

    private suspend fun pull():Boolean {
        var cursor=prefs.getLong("last_pull_revision",0L)
        repeat(1000){
            val response=request("GET","changes?since=$cursor&limit=200") ?: return false
            if(response.first!=200)return false
            val doc=response.second
            if(!validateServerIdentity(doc,cursor))return false
            val changes=doc.getJSONArray("items")
            for(i in 0 until changes.length())applyChange(changes.getJSONObject(i))
            cursor=doc.getLong("cursor")
            // Commit cursor only after the data is safely written to Room.
            if(!prefs.edit().putLong("last_pull_revision",cursor).commit())return false
            if(!doc.getBoolean("has_more"))return true
        }
        return false
    }

    private fun payload(row:TransactionEntity):JSONObject {
        val record=UploadPayload.v2Record(row)
        return JSONObject().apply {
            put("timestamp_ms",record.timestampMs)
            put("amount_cents",record.amountCents)
            put("currency",record.currency)
            put("direction",record.direction)
            put("source",record.source)
            put("category",record.category)
            put("merchant",record.merchant)
            record.reviewStatus?.let { put("review_status",it) }
            put("event_time_needs_confirmation",record.eventTimeNeedsConfirmation)
        }
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

    private fun clearConflictApproval(eventId:String) {
        prefs.edit()
            .remove("conflict_choice:$eventId")
            .remove("conflict_choice_revision:$eventId")
            .remove("conflict_choice_local_modified_ms:$eventId")
            .remove("conflict_choice_mutation_id:$eventId")
            .remove("conflict_server_preview:$eventId")
            .apply()
    }

    private data class PushOutcome(
        val success: Boolean,
        /** 两端都改过、需要用户逐笔决定的 event id。 */
        val conflicts: Set<String>,
        /** 服务器上已经找不到这条记录的 event id（current == null）。 */
        val missingOnServer: Set<String>,
    )

    private suspend fun push(): PushOutcome {
        val conflictIds=mutableSetOf<String>()
        val missingIds=mutableSetOf<String>()
        var skipped=0
        repeat(1000){
            val pending=dao.getPendingMutations(50,skipped)
            if(pending.isEmpty())return PushOutcome(true,conflictIds,missingIds)
            var progress=false
            for(row in pending){
                recordSync(row,TransactionDiagnostics.RESULT_STARTED,"Transaction sync push started")
                val result=send(row)
                if(result==null){
                    recordSync(row,TransactionDiagnostics.RESULT_FAILURE,"Transaction sync push failed")
                    return PushOutcome(false,conflictIds,missingIds)
                }
                val live=dao.getById(row.id)
                if(live==null){
                    recordSync(row,TransactionDiagnostics.RESULT_WARNING,"Transaction was removed while sync push was in progress")
                    continue
                }
                if(live.syncModifiedMs!=row.syncModifiedMs ||
                   (row.syncMutationId!=null && live.syncMutationId!=row.syncMutationId)){
                    recordSync(live,TransactionDiagnostics.RESULT_WARNING,"Local transaction changed during sync; stale response ignored")
                    continue
                }
                val id=live.remoteEventId ?: ownPrefix+live.id
                when(result.getString("status")){
                    "applied"->{
                        dao.update(live.copy(remoteEventId=id,
                            remoteRevision=result.getLong("revision"),
                            syncDirty=false,syncMutationId=null))
                        clearConflictApproval(id)
                        conflictIds.remove(id)
                        missingIds.remove(id)
                        recordSync(
                            live.copy(remoteEventId=id,remoteRevision=result.getLong("revision"),syncDirty=false,syncMutationId=null),
                            TransactionDiagnostics.RESULT_SUCCESS,
                            "Server applied transaction change",
                        )
                        progress=true
                    }
                    "conflict"->{
                        val current=result.optJSONObject("current")
                        if(current==null){
                            // 服务器上查不到这笔：没有可比对的服务器版本，
                            // 状态页要单独说「服务器上找不到这条」，不能笼统算冲突。
                            conflictIds.add(id)
                            missingIds.add(id)
                            recordSync(live,TransactionDiagnostics.RESULT_WARNING,"Server reports a conflict and no current record")
                            continue
                        }
                        val serverMs=current.optLong("changed_at_ms",0L)
                        val phoneMs=live.syncModifiedMs
                        val serverRevision=current.getLong("revision")
                        // Show this exact server revision in the settings UI.
                        // If either side changes after the user taps a decision,
                        // do not silently apply that old choice to new contents.
                        prefs.edit().putString("conflict_server_preview:$id",
                            current.toString()).apply()
                        val explicitChoice=prefs.getString("conflict_choice:$id",null)
                        val hasChoice=explicitChoice in setOf("keep_phone","keep_server")
                        val validChoice=SyncConflictApproval.valid(
                            explicitChoice,
                            prefs.getLong("conflict_choice_revision:$id",-1L),
                            serverRevision,
                            prefs.getLong("conflict_choice_local_modified_ms:$id",-1L),
                            phoneMs,
                            prefs.getString("conflict_choice_mutation_id:$id",null),
                            live.syncMutationId
                        )
                        val decision=if(hasChoice && !validChoice)
                            SyncConflictPolicy.Decision.HOLD
                        else SyncConflictPolicy.decide(
                            phoneMs,serverMs,System.currentTimeMillis(),
                            if(validChoice) explicitChoice ?: "auto" else "auto",
                            serverDeleted=current.optString("action")=="delete",
                            phoneDeleted=live.syncDeleted
                        )
                        if(decision==SyncConflictPolicy.Decision.KEEP_PHONE){
                            val retry=send(live,serverRevision)
                            if(retry?.optString("status")=="applied"){
                                val applied=live.copy(remoteEventId=id,
                                    remoteRevision=retry.getLong("revision"),
                                    syncDirty=false,syncMutationId=null)
                                dao.update(applied)
                                clearConflictApproval(id)
                                conflictIds.remove(id)
                                missingIds.remove(id)
                                recordSync(applied,TransactionDiagnostics.RESULT_SUCCESS,"User-approved local transaction change applied")
                                progress=true
                            }else {
                                conflictIds.add(id)
                                recordSync(live,TransactionDiagnostics.RESULT_WARNING,"User-approved local change was not applied")
                            }
                        }else if(decision==SyncConflictPolicy.Decision.KEEP_SERVER){
                            val updated=serverValue(live,id,serverRevision,
                                current.getString("action"),current.optJSONObject("record"))
                            if(updated!=null){
                                dao.update(updated)
                                clearConflictApproval(id)
                                conflictIds.remove(id)
                                missingIds.remove(id)
                                recordSync(updated,TransactionDiagnostics.RESULT_SUCCESS,"User-approved server transaction change applied")
                                progress=true
                            } else {
                                conflictIds.add(id)
                                recordSync(live,TransactionDiagnostics.RESULT_WARNING,"Server conflict resolution could not be applied")
                            }
                        }else {
                            conflictIds.add(id)
                            recordSync(live,TransactionDiagnostics.RESULT_WARNING,"Transaction sync conflict awaits resolution")
                        }
                    }
                    "before_start"->{
                        // 只有旧版服务端才会回 before_start（当前服务端不再按
                        // 账本起始日拒收任何记录）。这条修改仍留在手机里等重试，
                        // 不能被当作已同步：悄悄确认会让手机与服务器的流水
                        // 在无人察觉的情况下分叉。这是版本落差，不是安全事件，
                        // 也不阻断其他记录的同步。
                        recordSync(live,TransactionDiagnostics.RESULT_WARNING,"Older server did not accept this transaction before its start boundary")
                    }
                    else->recordSync(live,TransactionDiagnostics.RESULT_WARNING,"Unknown transaction sync result")
                }
            }
            if(progress) skipped=0 else skipped+=pending.size
        }
        return PushOutcome(false,conflictIds,missingIds)
    }

    suspend fun run():Boolean=withContext(Dispatchers.IO){
        if(!pull())return@withContext false
        val push=push()
        if(!push.success)return@withContext false
        if(!pull())return@withContext false
        // 两个数字分开写：pending 是还没推上去的本地修改（正常现象），
        // conflictIds 才是两端都改过、需要用户逐笔决定的冲突。
        val waiting=dao.pendingCount()
        prefs.edit()
            .putInt(TransactionSyncWorker.PENDING_UPLOADS,waiting)
            .putInt(TransactionSyncWorker.PENDING_CONFLICTS,push.conflicts.size)
            .putStringSet(TransactionSyncWorker.PENDING_CONFLICT_IDS,push.conflicts.toSet())
            .putStringSet(
                TransactionSyncWorker.PENDING_CONFLICT_MISSING_IDS,
                push.missingOnServer.toSet(),
            )
            .putString("last_result",
            when{
                push.conflicts.isNotEmpty()->
                    "有 ${push.conflicts.size} 笔冲突待处理；其余修改已上传"
                waiting>0->"已同步；还有 $waiting 笔修改待上传"
                else->"双向同步成功：手机和服务器交易已更新"
            }
        ).putLong(TransactionSyncWorker.LAST_RESULT_MS,System.currentTimeMillis())
            .putLong("last_success_ms",System.currentTimeMillis()).apply()
        true
    }
}
