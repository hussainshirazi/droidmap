package com.example.data.di

import android.content.Context
import androidx.room.Room
import com.example.data.ItemRepository
import com.example.data.OfflineItemRepository
import com.example.db.AppDatabase
import com.example.db.ItemDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Qualifier
annotation class Dispatcher(val which: Which)

enum class Which { IO, Default }

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
            .addMigrations(*AppDatabase.ALL)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideItemDao(db: AppDatabase): ItemDao = db.itemDao()

    @Provides
    @Dispatcher(Which.IO)
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(Which.Default)
    fun default(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides
    fun otherClock(): Clock = Clock.systemUTC()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindItems(impl: OfflineItemRepository): ItemRepository
}
