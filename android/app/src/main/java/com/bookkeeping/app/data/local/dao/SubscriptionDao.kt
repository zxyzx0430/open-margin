package com.bookkeeping.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    @Query(
        """
        SELECT * FROM subscriptions
        WHERE deletedAtMs IS NULL AND active = 1
        ORDER BY nextDueMs ASC, id ASC
        """,
    )
    fun observeActive(): Flow<List<SubscriptionEntity>>

    @Query(
        """
        SELECT * FROM subscriptions
        WHERE deletedAtMs IS NULL
        ORDER BY active DESC, nextDueMs ASC, id ASC
        """,
    )
    fun observeAllNonDeleted(): Flow<List<SubscriptionEntity>>

    @Query(
        """
        SELECT * FROM subscriptions
        WHERE deletedAtMs IS NULL
        ORDER BY active DESC, nextDueMs ASC, id ASC
        """,
    )
    suspend fun getAllNonDeleted(): List<SubscriptionEntity>

    @Query(
        """
        SELECT * FROM subscriptions
        WHERE deletedAtMs IS NULL AND active = 1
        ORDER BY nextDueMs ASC, id ASC
        """,
    )
    suspend fun getActiveNonDeleted(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE dirty = 1 ORDER BY updatedAtMs ASC, id ASC")
    suspend fun getDirty(): List<SubscriptionEntity>

    @Query("SELECT COUNT(*) FROM subscriptions WHERE dirty = 1")
    suspend fun dirtyCount(): Int

    @Query("SELECT COUNT(*) FROM subscriptions WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE syncUuid = :syncUuid LIMIT 1")
    suspend fun getBySyncUuid(syncUuid: String): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE wallosId = :wallosId LIMIT 1")
    suspend fun getByWallosId(wallosId: Long): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(subscription: SubscriptionEntity): Long

    @Update
    suspend fun update(subscription: SubscriptionEntity)

    @Transaction
    suspend fun upsert(subscription: SubscriptionEntity): Long {
        val existing = when {
            subscription.id > 0L -> getById(subscription.id) ?: getBySyncUuid(subscription.syncUuid)
            else -> getBySyncUuid(subscription.syncUuid)
        }
        return if (existing == null) {
            insert(subscription.copy(id = 0L))
        } else {
            update(subscription.copy(id = existing.id))
            existing.id
        }
    }

    @Query(
        """
        UPDATE subscriptions
        SET deletedAtMs = :deletedAtMs, updatedAtMs = :updatedAtMs, dirty = 1
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: Long, deletedAtMs: Long, updatedAtMs: Long = deletedAtMs)

    @Query(
        """
        UPDATE subscriptions
        SET deletedAtMs = :deletedAtMs, updatedAtMs = :deletedAtMs, dirty = 0
        WHERE syncUuid = :syncUuid
        """,
    )
    suspend fun applyRemoteTombstone(syncUuid: String, deletedAtMs: Long)

    @Query(
        """
        UPDATE subscriptions
        SET wallosId = COALESCE(:wallosId, wallosId), dirty = 0
        WHERE syncUuid = :syncUuid
        """,
    )
    suspend fun clearDirty(syncUuid: String, wallosId: Long? = null)
}
