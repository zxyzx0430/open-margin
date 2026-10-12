package com.bookkeeping.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionEntity

// Room 数据库本体。
// entities = 这个库里有哪些"表"
// version = 数据库版本号；每次改 Entity 结构需要 +1 并写 Migration
@Database(
    entities = [TransactionEntity::class],
    version = 2,
    exportSchema = false  // 不导出 schema 文件；正式上线建议开
)
abstract class BookkeepingDatabase : RoomDatabase() {
    // 暴露 DAO 给外部使用。Room 会自动生成实现。
    abstract fun transactionDao(): TransactionDao

    companion object {
        const val DB_NAME = "bookkeeping.db"

        // Must never fall back to destructive migration: user's unsynced
        // payment notifications and manually entered expenses live here.
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
