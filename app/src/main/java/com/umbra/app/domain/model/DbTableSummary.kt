package com.umbra.app.domain.model

/** One row of the DB inspector's table list (developer-only) — a table name and its row count. */
data class DbTableSummary(
    val name: String,
    val rowCount: Int
)
