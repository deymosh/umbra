package com.umbra.app.di

import com.umbra.app.data.media.TorMediaDataSourceProvider
import com.umbra.app.domain.media.MediaDataSourceProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

import com.umbra.app.data.media.TorCacheDataSourceProvider
import com.umbra.app.domain.media.VideoCacheDataSourceProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    @Singleton
    abstract fun bindMediaDataSourceProvider(
        impl: TorMediaDataSourceProvider
    ): MediaDataSourceProvider

    @Binds
    @Singleton
    abstract fun bindVideoCacheDataSourceProvider(
        impl: TorCacheDataSourceProvider
    ): VideoCacheDataSourceProvider
}
