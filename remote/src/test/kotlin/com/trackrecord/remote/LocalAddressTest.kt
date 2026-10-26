package com.trackrecord.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalAddressTest {
    @Test
    fun `wifi first, then the hotspot, then anything local`() {
        assertEquals("192.168.1.23", LocalAddress.pick(listOf("rmnet0" to "10.12.0.4", "wlan0" to "192.168.1.23")))
        assertEquals("192.168.43.1", LocalAddress.pick(listOf("rmnet0" to "10.12.0.4", "ap0" to "192.168.43.1")))
        assertEquals("10.0.2.15", LocalAddress.pick(listOf("eth0" to "10.0.2.15")))
    }

    @Test
    fun `public and link-local addresses are not offered`() {
        assertNull(LocalAddress.pick(listOf("rmnet0" to "100.64.3.2", "wlan0" to "169.254.1.1")))
        assertEquals("172.20.0.5", LocalAddress.pick(listOf("rmnet0" to "8.8.8.8", "wlan1" to "172.20.0.5")))
    }
}
