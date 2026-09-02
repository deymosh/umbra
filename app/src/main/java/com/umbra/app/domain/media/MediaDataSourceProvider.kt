package com.umbra.app.domain.media

import androidx.media3.datasource.okhttp.OkHttpDataSource

interface MediaDataSourceProvider {
    fun getDataSourceFactory(): OkHttpDataSource.Factory
}
