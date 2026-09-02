package com.umbra.app.domain.util

/** True once more than [ttlMillis] has elapsed since [timestampMillis]. Boundary: exactly-at-TTL is NOT stale. */
fun isStale(timestampMillis: Long, ttlMillis: Long): Boolean =
    System.currentTimeMillis() - timestampMillis > ttlMillis

/** Epoch millis [ttlMillis] ago — for call sites needing the threshold value itself (e.g. Room DAO query params). */
fun thresholdMillisBefore(ttlMillis: Long): Long =
    System.currentTimeMillis() - ttlMillis
