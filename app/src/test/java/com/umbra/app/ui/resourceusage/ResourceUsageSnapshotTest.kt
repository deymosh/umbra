package com.umbra.app.ui.resourceusage

import android.app.Application
import com.umbra.app.domain.model.ResourceUsageSnapshot
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val MB = 1024L * 1024L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class ResourceUsageSnapshotTest {

    private val sample = ResourceUsageSnapshot(
        jvmHeapUsedBytes = 142 * MB,
        jvmHeapMaxBytes = 256 * MB,
        nativeHeapAllocatedBytes = 88 * MB,
        deviceMemoryClassMb = 256,
        isLargeHeap = false,
        imageMemoryCacheUsedBytes = 41 * MB,
        imageMemoryCacheMaxBytes = 64 * MB,
        imageDiskCacheUsedBytes = 212 * MB,
        imageDiskCacheMaxBytes = 250 * MB,
        eventCacheSize = 1840,
        eventCacheMaxSize = 5000,
        databaseFileBytes = 6 * MB,
        profileCacheEntries = 412,
        relayListCacheEntries = 96,
        ownerListCacheEntries = 1
    )

    @Test
    fun loaded() = snapshot("ResourceUsage") {
        AppResourceUsageContent(
            state = AppResourceUsageState(snapshot = sample, isTrimmingCaches = true),
            onNavigateBack = {},
            onClearEventCache = {},
            onTrimAllCaches = {}
        )
    }

    @Test
    fun loading() = snapshot("ResourceUsage_loading") {
        AppResourceUsageContent(AppResourceUsageState(), {}, {}, {})
    }
}
