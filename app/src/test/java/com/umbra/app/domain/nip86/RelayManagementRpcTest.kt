package com.umbra.app.domain.nip86

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayManagementRpcTest {

    @Test
    fun `given request when encoded then method and params wire up`() {
        val body = RelayManagementRpc.encodeRequest(
            RelayManagementMethod.BAN_PUBKEY,
            listOf("a".repeat(64), "spam")
        )
        assertTrue(body.contains("\"method\":\"banpubkey\""))
        assertTrue(body.contains("\"params\":[\"" + "a".repeat(64) + "\",\"spam\"]"))
    }

    @Test
    fun `given entries array when decoded then entries with reasons parse`() {
        val result = RelayManagementRpc.decodeResponse(
            """{"result":[{"pubkey":"abc","reason":"spam"},{"pubkey":"def"}],"error":""}"""
        )
        val ok = result as RelayManagementResult.Ok
        assertEquals(
            listOf(
                RelayManagementEntry("abc", "spam"),
                RelayManagementEntry("def", null)
            ),
            ok.data.entries
        )
        assertTrue(ok.data.isEmpty.not())
    }

    @Test
    fun `given id-keyed and ip-keyed arrays when decoded then identifier field is picked per shape`() {
        val byId = RelayManagementRpc.decodeResponse("""{"result":[{"id":"e1"}]}""")
        assertEquals(listOf(RelayManagementEntry("e1", null)), (byId as RelayManagementResult.Ok).data.entries)

        val byIp = RelayManagementRpc.decodeResponse("""{"result":[{"ip":"192.0.2.1","reason":"abuse"}]}""")
        assertEquals(listOf(RelayManagementEntry("192.0.2.1", "abuse")), (byIp as RelayManagementResult.Ok).data.entries)
    }

    @Test
    fun `given kind array when decoded then kindNumbers parse`() {
        val result = RelayManagementRpc.decodeResponse("""{"result":[1,10002,30023]}""")
        assertEquals(listOf(1, 10002, 30023), (result as RelayManagementResult.Ok).data.kindNumbers)
    }

    @Test
    fun `given empty array when decoded then data is empty`() {
        val result = RelayManagementRpc.decodeResponse("""{"result":[]}""")
        assertTrue((result as RelayManagementResult.Ok).data.isEmpty)
    }

    @Test
    fun `given null result when decoded then data is empty`() {
        val result = RelayManagementRpc.decodeResponse("""{"result":null}""")
        assertTrue((result as RelayManagementResult.Ok).data.isEmpty)
    }

    @Test
    fun `given error field when decoded then RelayError surfaces the message`() {
        val result = RelayManagementRpc.decodeResponse("""{"result":null,"error":"bad params"}""")
        assertEquals(RelayManagementResult.RelayError("bad params"), result)
    }

    @Test
    fun `given non-json body when decoded then Transport result`() {
        val result = RelayManagementRpc.decodeResponse("<html>gateway</html>")
        assertTrue(result is RelayManagementResult.Transport)
    }

    @Test
    fun `given supported method names when parsed then only known methods survive`() {
        val parsed = parseSupportedMethods(
            listOf("banpubkey", "listbannedpubkeys", "futuremethod", "allowkind")
        )
        assertEquals(
            setOf(RelayManagementMethod.BAN_PUBKEY, RelayManagementMethod.LIST_BANNED_PUBKEYS, RelayManagementMethod.ALLOW_KIND),
            parsed
        )
    }

    @Test
    fun `given relay info without nip 86 when checked then supportsNip86 is false`() {
        assertEquals(false, supportsNip86(null))
        assertEquals(false, supportsNip86(com.umbra.app.domain.nip11.RelayInfo(supportedNips = listOf(1, 11))))
        assertEquals(true, supportsNip86(com.umbra.app.domain.nip11.RelayInfo(supportedNips = listOf(1, 86))))
    }
}
