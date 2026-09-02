package com.umbra.app.domain.nip67

import org.junit.Assert.assertEquals
import org.junit.Test

class EoseCompletenessHintTest {

    @Test
    fun `given absent third element when parsing then unspecified`() {
        assertEquals(EoseCompleteness.UNSPECIFIED, parseEoseCompleteness(null))
    }

    @Test
    fun `given finish when parsing then finish`() {
        assertEquals(EoseCompleteness.FINISH, parseEoseCompleteness("finish"))
    }

    @Test
    fun `given more when parsing then more`() {
        assertEquals(EoseCompleteness.MORE, parseEoseCompleteness("more"))
    }

    @Test
    fun `given an unrecognized value when parsing then unspecified`() {
        assertEquals(EoseCompleteness.UNSPECIFIED, parseEoseCompleteness("garbage"))
    }

    @Test
    fun `given empty string when parsing then unspecified`() {
        assertEquals(EoseCompleteness.UNSPECIFIED, parseEoseCompleteness(""))
    }
}
