package com.bookkeeping.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookkeeping.app.data.local.dao.ProcessedNoticeDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.ProcessedNoticeEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity

// Room 数据库本体。
// entities = 这个库里有哪些"表"
// version = 数据库版本号；每次改 Entity 结构需要 +1 并写 Migration
@Database(
    entities = [TransactionEntity::class, ProcessedNoticeEntity::class],
    version = 7,
    exportSchema = false  // 不导出 schema 文件；正式上线建议开
)
abstract class BookkeepingDatabase : RoomDatabase() {
    // 暴露 DAO 给外部使用。Room 会自动生成实现。
    abstract fun transactionDao(): TransactionDao

    abstract fun processedNoticeDao(): ProcessedNoticeDao

    companion object {
        const val DB_NAME = "bookkeeping.db"

        /**
         * Single source of truth for the Room builder. Every caller (Hilt and
         * WorkManager) must register the same migration chain; missing one
         * migration breaks synchronization on app upgrades.
         */
        fun build(context: Context): BookkeepingDatabase =
            Room.databaseBuilder(context, BookkeepingDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()

        // v7: structured official-bill order ID. Local-only, not uploaded;
        // the index supports import deduplication without forcing a merge.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN sourceTransactionId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_sourceTransactionId " +
                        "ON transactions(sourceTransactionId)",
                )
            }
        }

        // v6: local-only merchant icon metadata. Existing rows keep null and
        // continue to render with their category icon.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN merchantIconKey TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN merchantIconPath TEXT")
            }
        }

        // v5: local-only duplicate-review marker. Never uploaded; existing
        // rows keep null and are unaffected.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN duplicateOfId INTEGER")
            }
        }

        // Must never fall back to destructive migration: user's unsynced
        // payment notifications and manually entered expenses live here.
        // Non-destructive: v1/v2 rows are local CNY; new remote rows preserve
        // their original denomination instead of silently becoming yuan.
        // v4（蓝图 D05）：新增已处理通知指纹表，只存哈希与计数，不存原文。
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `processed_notices` (`fingerprint` TEXT NOT NULL, `source` TEXT NOT NULL, `firstSeenMs` INTEGER NOT NULL, `lastSeenMs` INTEGER NOT NULL, `seenCount` INTEGER NOT NULL, `transactionId` INTEGER, PRIMARY KEY(`fingerprint`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN currency TEXT NOT NULL DEFAULT 'CNY'")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN remoteEventId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN remoteRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN syncDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN syncModifiedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN remoteReviewStatus TEXT NOT NULL DEFAULT 'unverified'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN syncMutationId TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transactions_remoteEventId ON transactions(remoteEventId)")
            }
        }
    }
}
