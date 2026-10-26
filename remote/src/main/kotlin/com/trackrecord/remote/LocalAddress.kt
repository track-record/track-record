package com.trackrecord.remote

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The host's address for the QR link (DECISIONS.md D-109): an IPv4 site-local address, preferring
 * Wi-Fi (wlan*) and then the phone's own hotspot (ap*, swlan*) over any other interface.
 */
object LocalAddress {
    /** Picks from (interface name, IPv4 address) pairs; null when none is usable. */
    fun pick(candidates: List<Pair<String, String>>): String? =
        candidates
            .filter { (_, ip) -> isSiteLocal(ip) }
            .minByOrNull { (name, _) -> rank(name) }
            ?.second

    /** The address of this device right now. */
    fun find(): String? {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { a -> a.hostAddress?.let { nif.name to it } } }
        }.getOrDefault(emptyList())
        return pick(candidates)
    }

    private fun rank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("ap") || name.startsWith("swlan") -> 1
        else -> 2
    }

    private fun isSiteLocal(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
