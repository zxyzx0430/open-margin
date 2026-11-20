package com.bookkeeping.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDiagnosticDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: TransactionDiagnosticEventEntity)

    @Query("DELETE FROM transaction_diagnostic_events WHERE occurredAtMs < :cutoffMs")
    suspend fun pruneBefore(cutoffMs: Long): Int

    @Query("""
        SELECT * FROM transaction_diagnostic_events
        WHERE occurredAtMs >= :cutoffMs
          AND (
              :transactionId IS NULL
              OR transactionId = :transactionId
              OR attemptId IN (
                  SELECT linked.attemptId FROM transaction_diagnostic_events AS linked
                  WHERE linked.transactionId = :transactionId
                    AND linked.occurredAtMs >= :cutoffMs
              )
          )
          AND (
              :query = ''
              OR (
                  :query NOT GLOB '*[^0-9]*'
                  AND :query != ''
                  AND (
                      transactionId = CAST(:query AS INTEGER)
                      OR attemptId IN (
                          SELECT linked.attemptId FROM transaction_diagnostic_events AS linked
                          WHERE linked.transactionId = CAST(:query AS INTEGER)
                            AND linked.occurredAtMs >= :cutoffMs
                      )
                  )
              )
              OR (
                  :query GLOB '*[^0-9]*'
                  AND (attemptId LIKE '%' || :query || '%'
                       OR stage LIKE '%' || :query || '%'
                       OR result LIKE '%' || :query || '%'
                       OR details LIKE '%' || :query || '%'
                       OR callSite LIKE '%' || :query || '%'
                       OR inputSummary LIKE '%' || :query || '%'
                       OR outputSummary LIKE '%' || :query || '%')
              )
          )
        ORDER BY occurredAtMs DESC, rowid DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeEvents(
        cutoffMs: Long,
        transactionId: Long?,
        query: String,
        limit: Int,
        offset: Int,
    ): Flow<List<TransactionDiagnosticEventEntity>>

    @Query("""
        SELECT transactionId FROM transaction_diagnostic_events
        WHERE transactionId IS NOT NULL AND occurredAtMs >= :cutoffMs
        GROUP BY transactionId
        ORDER BY MAX(occurredAtMs) DESC, transactionId DESC
        LIMIT :limit
    """)
    fun observeRecentTransactionIds(cutoffMs: Long, limit: Int): Flow<List<Long>>
}
