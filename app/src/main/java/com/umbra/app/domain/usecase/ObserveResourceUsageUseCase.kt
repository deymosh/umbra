package com.umbra.app.domain.usecase

import com.umbra.app.domain.model.ResourceUsageSnapshot
import com.umbra.app.domain.repository.ResourceUsageRepository
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Polls [ResourceUsageRepository] on an interval so the Settings > App Resource Usage screen can
 * show live memory/cache stats. Runs on [Dispatchers.IO] since some of the underlying reads
 * (DiskLruCache size, DB file stat) can block.
 */
class ObserveResourceUsageUseCase(
    private val repository: ResourceUsageRepository
) {
    operator fun invoke(intervalMs: Long = 2_000L): Flow<ResourceUsageSnapshot> = flow {
        while (coroutineContext.isActive) {
            emit(repository.getSnapshot())
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}
