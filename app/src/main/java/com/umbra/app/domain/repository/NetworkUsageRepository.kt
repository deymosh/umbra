package com.umbra.app.domain.repository

import com.umbra.app.domain.model.NetworkUsageSnapshot
import kotlinx.coroutines.flow.Flow

interface NetworkUsageRepository {
    /** Live usage, refreshed about once a second while collected. */
    fun observe(): Flow<NetworkUsageSnapshot>

    /** Zeroes this session's counters and the lifetime total. */
    fun reset()
}
