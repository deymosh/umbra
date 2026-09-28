package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildThreadFiltersUseCaseTest {
    private val build = BuildThreadFiltersUseCase()

    @Test
    fun `given anchor and root when building then both ids are requested by e and E`() {
        val filters = build(anchorId = "a", rootId = "r")
        val byTag = filters.associateBy { it.tagFilters.keys.single() }
        assertEquals(setOf("a", "r"), byTag.getValue("e").tagFilters.getValue("e"))
        assertEquals(setOf("a", "r"), byTag.getValue("E").tagFilters.getValue("E"))
        assertTrue(Event.KIND_TEXT_NOTE in byTag.getValue("e").kinds)
        assertTrue(Event.KIND_COMMENT in byTag.getValue("e").kinds)
        assertEquals(setOf(Event.KIND_COMMENT), byTag.getValue("E").kinds)
    }

    @Test
    fun `given no root when building then only the anchor is requested`() {
        val filters = build(anchorId = "a", rootId = null)
        filters.forEach { filter -> assertEquals(setOf("a"), filter.tagFilters.values.single()) }
    }
}
