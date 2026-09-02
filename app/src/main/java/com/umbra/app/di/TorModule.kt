package com.umbra.app.di

import com.umbra.app.data.tor.TorRuntimeManager
import com.umbra.app.domain.tor.TorRuntimeController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TorModule {

    @Singleton
    @Binds
    abstract fun bindTorRuntimeController(impl: TorRuntimeManager): TorRuntimeController
}
