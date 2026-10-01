package com.umbra.app.di

import com.umbra.app.data.amber.AmberSignerGatewayImpl
import com.umbra.app.data.crypto.ThrowawayAuthSignerImpl
import com.umbra.app.domain.nip42.ThrowawayAuthSigner
import com.umbra.app.domain.nip44.Nip44Gateway
import com.umbra.app.domain.nip55.AmberSignerGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds
    @Singleton
    abstract fun bindAmberSignerGateway(impl: AmberSignerGatewayImpl): AmberSignerGateway

    @Binds
    @Singleton
    abstract fun bindNip44Gateway(impl: AmberSignerGatewayImpl): Nip44Gateway

    // Narrow AUDIT.md 1.2 exception: in-app Schnorr signing for kind-22242 relay AUTH only,
    // with per-connection throwaway keys held in memory behind the RelayAuthMode user setting.
    @Binds
    @Singleton
    abstract fun bindThrowawayAuthSigner(impl: ThrowawayAuthSignerImpl): ThrowawayAuthSigner
}