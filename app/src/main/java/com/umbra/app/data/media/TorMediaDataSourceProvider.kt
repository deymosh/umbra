package com.umbra.app.data.media

import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.umbra.app.domain.media.MediaDataSourceProvider
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class TorMediaDataSourceProvider @Inject constructor(
    @Named("tor") private val torOkHttpClient: OkHttpClient
) : MediaDataSourceProvider {

    private val factory: OkHttpDataSource.Factory by lazy {
        OkHttpDataSource.Factory(torOkHttpClient)
    }

    override fun getDataSourceFactory(): OkHttpDataSource.Factory = factory
}
