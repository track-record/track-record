package com.trackrecord.app

import com.trackrecord.app.remote.InternetRoom
import com.trackrecord.remote.RemoteProtocol
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The guest page and the host speak one protocol version (REVIEW-2 B-10; DECISIONS.md D-264). */
class ProtocolVersionTest {
    @Test
    fun `the guest page's protocol is the hub's`() {
        val js = File("src/main/assets/remote/p2p-common.js").readText()
        val page = Regex("""export const PROTOCOL = (\d+);""").find(js)?.groupValues?.get(1)?.toInt()
        assertEquals(RemoteProtocol.VERSION, page, "PROTOCOL in p2p-common.js")
    }

    @Test
    fun `the host key id in the link is short and fits the page's pattern`() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val id = InternetRoom.keyId(key.public.encoded)
        assertEquals(22, id.length)
        assertTrue(Regex("[A-Za-z0-9_-]{16,64}").matches(id))
    }
}
