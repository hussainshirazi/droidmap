package com.example.notes.di

import android.content.Context
import androidx.room.Room
import com.example.notes.data.AppDatabase
import com.example.notes.data.NoteDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "notes.db").build()

    @Provides
    fun noteDao(db: AppDatabase): NoteDao = db.noteDao()
}
