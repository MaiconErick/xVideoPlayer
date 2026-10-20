package com.app.xvideoplayer

import android.content.Context
import com.app.xvideoplayer.data.db.AppDatabase
import com.app.xvideoplayer.data.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.create(context)

    @Provides
    fun provideVideoDao(db: AppDatabase): VideoDao = db.videoDao()

    @Provides
    fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()

    @Provides
    fun providePlaybackHistoryDao(db: AppDatabase): PlaybackHistoryDao = db.playbackHistoryDao()

    @Provides
    fun providePrivateMetaDao(db: AppDatabase): PrivateMetaDao = db.privateMetaDao()

    @Provides
    fun provideThumbnailDao(db: AppDatabase): ThumbnailDao = db.thumbnailDao()
}
