package com.umbra.app.domain.nip01

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KindNamesTest {

    @Test
    fun `given every kind Umbra uses when labelling then none falls back to the bare number`() {
        // Event's KIND_* constants compile to static fields on Event.
        val kinds = Event::class.java.declaredFields
            .filter { it.name.startsWith("KIND_") && Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
        assertTrue(kinds.isNotEmpty())

        val unnamed = kinds.filter { (_, kind) -> KindNames.labelFor(kind) == "Kind $kind" }.keys

        assertEquals(emptySet<String>(), unnamed)
    }
}
