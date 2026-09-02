package com.umbra.app.domain.media

import androidx.media3.datasource.DataSource

interface VideoCacheDataSourceProvider {
    fun getCacheDataSourceFactory(): DataSource.Factory
}