package com.umbra.app.data.repository

import android.content.Context
import com.umbra.app.data.network.TrafficMeter
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.model.NetworkUsageSnapshot
import com.umbra.app.domain.repository.NetworkUsageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Samples [TrafficMeter] for the Network usage screen. Only one number outlives the process: the
 * lifetime byte total (no relay URLs, no timestamps), carried forward from the previous run.
 */
@Singleton
class NetworkUsageRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val meter: TrafficMeter
) : NetworkUsageRepository {

    private val prefs by lazy { SecurePreferences(context, "network_usage") }

    // Lifetime total carried in from earlier processes; this run's traffic is added on top.
    @Volatile
    private var carriedBytes: Long = -1

    private fun carried(): Long {
        if (carriedBytes < 0) carriedBytes = prefs.getString(KEY_LIFETIME)?.toLongOrNull() ?: 0L
        return carriedBytes
    }

    override fun observe(): Flow<NetworkUsageSnapshot> = flow {
        var ticks = 0
        while (true) {
            val lifetime = carried() + meter.totalBytes()
            emit(
                NetworkUsageSnapshot(
                    sessionStartedAtMillis = meter.sessionStartedAtMillis,
                    relays = meter.relaySnapshot(),
                    mediaBytesReceived = meter.mediaBytes(),
                    otherHttpBytesReceived = meter.otherHttpBytes(),
                    httpBytesSent = meter.httpSentBytes(),
                    lifetimeBytes = lifetime
                )
            )
            if (ticks++ % PERSIST_EVERY_TICKS == 0) prefs.putString(KEY_LIFETIME, lifetime.toString())
            delay(TICK_MS)
        }
    }.flowOn(Dispatchers.Default)

    override fun reset() {
        meter.reset()
        carriedBytes = 0
        prefs.putString(KEY_LIFETIME, "0")
    }

    private companion object {
        const val KEY_LIFETIME = "lifetime_bytes"
        const val TICK_MS = 1_000L
        const val PERSIST_EVERY_TICKS = 10
    }
}
