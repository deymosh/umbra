package com.umbra.app.domain.repository

import com.umbra.app.domain.model.DbEventDetail
import com.umbra.app.domain.model.DbTableSummary

/**
 * Read-only access to the encrypted DB's raw tables, for the developer-only DB inspector screen.
 * Deliberately a fixed set of parameterized queries rather than arbitrary SQL passthrough — this
 * is a security-sensitive, SQLCipher-encrypted database, and every capability the inspector needs
 * (row counts, paginated event search/browse, single-event detail) is expressible this way without
 * the risk surface a raw-query escape hatch would add.
 */
interface DbInspectorRepository {
    suspend fun getTableSummaries(): List<DbTableSummary>

    suspend fun searchEvents(
        kind: Int?,
        pubkey: String?,
        contentQuery: String?,
        limit: Int,
        offset: Int
    ): List<DbEventDetail>

    suspend fun getEventDetail(id: String): DbEventDetail?
}
