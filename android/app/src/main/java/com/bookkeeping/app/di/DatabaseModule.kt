package com.bookkeeping.app.di

import android.content.Context
import androidx.room.Room
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.TransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Hilt Module：告诉 Hilt "如何创建" Database 和 DAO 实例。
// @InstallIn(SingletonComponent::class) = 全 app 单例。
// 嵌入式类比：在系统启动时初始化外设句柄，整个 app 共享同一份。
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideBookkeepingDatabase(
        @ApplicationContext context: Context
    ): BookkeepingDatabase {
        return Room.databaseBuilder(
            context,
            BookkeepingDatabase::class.java,
            BookkeepingDatabase.DB_NAME
        )
            .addMigrations(BookkeepingDatabase.MIGRATION_1_2, BookkeepingDatabase.MIGRATION_2_3)
            .build()
    }

    @Provides
    fun provideTransactionDao(db: BookkeepingDatabase): TransactionDao = db.transactionDao()
}
