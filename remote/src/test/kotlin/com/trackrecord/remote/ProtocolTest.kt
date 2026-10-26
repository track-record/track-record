package com.trackrecord.remote

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementNames
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * REVIEW-2 B-10 (DECISIONS.md D-264): the shape of every message between page and host, recorded per
 * [RemoteProtocol.VERSION]. A change to a request, reply, view or action type fails here until the
 * version is bumped (also in p2p-common.js) and the new shape recorded below.
 */
class ProtocolTest {
    @Test
    fun `the messages match the recorded shape of this protocol version`() {
        assertEquals(
            RECORDED[RemoteProtocol.VERSION],
            fingerprint(),
            "The messages between page and host changed: bump RemoteProtocol.VERSION and PROTOCOL in p2p-common.js, " +
                "then record ${RemoteProtocol.VERSION + 1} to \"${fingerprint()}\" here.",
        )
    }

    private fun fingerprint(): String {
        val messages = listOf(
            TunnelRequest.serializer(), TunnelReply.serializer(), TunnelEvent.serializer(), ClaimRequest.serializer(),
            ClientRequest.serializer(), ActionRequest.serializer(), ActionReply.serializer(), ClientView.serializer(),
        )
        val text = messages.joinToString("\n") { shape(it.descriptor, mutableSetOf()) } + "\n" + RemoteProtocol.ACTIONS.sorted().joinToString(",")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    /** Field names, optionality and kinds, recursively; class names don't reach the wire, so they don't count. */
    private fun shape(d: SerialDescriptor, seen: MutableSet<String>): String {
        val nullable = if (d.isNullable) "?" else ""
        return when {
            d.kind is PrimitiveKind -> "${d.kind}$nullable"
            d.kind == SerialKind.ENUM -> "enum(${d.elementNames.joinToString("|")})$nullable"
            !seen.add(d.serialName.removeSuffix("?")) -> "@$nullable"
            else -> (0 until d.elementsCount).joinToString(",", "${d.kind}{", "}$nullable") { i ->
                d.getElementName(i) + (if (d.isElementOptional(i)) "=" else ":") + shape(d.getElementDescriptor(i), seen)
            }
        }
    }

    private companion object {
        val RECORDED = mapOf(
            2 to "0bbffb1a9c74a737",
            // Design review: palettes, device kinds, reveal details, playback notes (D-277 to D-290).
            3 to "c720610c84897be7",
            // Feedback round 43: a co-host plays the whole song and removes players.
            4 to "4ac3d8d660f7fe3d",
            // Feedback round 44: a co-host's seek bar.
            5 to "eb46b29edf37f162",
        )
    }
}
