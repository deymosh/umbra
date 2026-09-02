package com.umbra.app.domain.repository

import com.umbra.app.domain.model.ResourceUsageSnapshot

interface ResourceUsageRepository {
    suspend fun getSnapshot(): ResourceUsageSnapshot
}
