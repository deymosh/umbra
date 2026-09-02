package com.umbra.app.data.repository

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import com.umbra.app.data.db.EncryptedUmbraDatabase
import com.umbra.app.domain.model.ResourceUsageSnapshot
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.ResourceUsageRepository
import com.umbra.app.domain.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ResourceUsageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val contactListRepository: ContactListRepository,
    private val muteListRepository: MuteListRepository,
    private val pinListRepository: PinListRepository
) : ResourceUsageRepository {

    @OptIn(ExperimentalCoilApi::class)
    override suspend fun getSnapshot(): ResourceUsageSnapshot = withContext(Dispatchers.IO) {
        val runtime = Runtime.getRuntime()
        val jvmUsed = runtime.totalMemory() - runtime.freeMemory()
        val jvmMax = runtime.maxMemory()

        val nativeAllocated = Debug.getNativeHeapAllocatedSize()

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLargeHeap = (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
        val memoryClassMb = activityManager?.let { if (isLargeHeap) it.largeMemoryClass else it.memoryClass } ?: 0

        val memoryCache = imageLoader.memoryCache
        val diskCache = imageLoader.diskCache

        val eventCacheStats = eventRepository.getInMemoryCacheStats()

        ResourceUsageSnapshot(
            jvmHeapUsedBytes = jvmUsed,
            jvmHeapMaxBytes = jvmMax,
            nativeHeapAllocatedBytes = nativeAllocated,
            deviceMemoryClassMb = memoryClassMb,
            isLargeHeap = isLargeHeap,
            imageMemoryCacheUsedBytes = memoryCache?.size,
            imageMemoryCacheMaxBytes = memoryCache?.maxSize,
            imageDiskCacheUsedBytes = diskCache?.size,
            imageDiskCacheMaxBytes = diskCache?.maxSize,
            eventCacheSize = eventCacheStats.size,
            eventCacheMaxSize = eventCacheStats.maxSize,
            databaseFileBytes = databaseFileSizeBytes(),
            profileCacheEntries = userRepository.cachedProfileCount(),
            relayListCacheEntries = userRepository.cachedRelayListCount(),
            ownerListCacheEntries = contactListRepository.cachedOwnerCount() +
                muteListRepository.cachedOwnerCount() +
                pinListRepository.cachedOwnerCount()
        )
    }

    private fun databaseFileSizeBytes(): Long {
        val base = context.getDatabasePath(EncryptedUmbraDatabase.DATABASE_NAME)
        return listOf(base, File(base.path + "-wal"), File(base.path + "-shm"))
            .sumOf { if (it.exists()) it.length() else 0L }
    }
}
