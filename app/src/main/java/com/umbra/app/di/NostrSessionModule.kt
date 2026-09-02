package com.umbra.app.di

import com.umbra.app.data.nostr.NostrSessionManager
import com.umbra.app.domain.nostr.NostrSessionController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NostrSessionModule {

    @Singleton
    @Binds
    abstract fun bindNostrSessionController(impl: NostrSessionManager): NostrSessionController
}
