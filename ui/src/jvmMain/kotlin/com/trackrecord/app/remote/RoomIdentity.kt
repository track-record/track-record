package com.trackrecord.app.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * What an internet link names: the room, its secret and the host's key (D-260). New for each room, or kept
 * for every game with "Keep the same internet link" (feedback round 50).
 */
class RoomIdentity(val roomId: String, val secret: String, val key: KeyPair) {
    companion object {
        fun new(): RoomIdentity = RoomIdentity(
            randomId(9),
            randomId(16),
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
        )

        private fun randomId(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    }
}

/**
 * The kept [RoomIdentity], in the app's no-backup files: it holds the host's private key, so it never leaves
 * this phone, not even in a backup.
 */
class KeptRoom(folder: File) {
    private val file = File(folder, "internet_room.json")

    fun load(): RoomIdentity? = runCatching {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        fun field(name: String) = o.getValue(name).jsonPrimitive.content
        val keys = KeyFactory.getInstance("EC")
        val pair = KeyPair(
            keys.generatePublic(X509EncodedKeySpec(decode(field("pub")))),
            keys.generatePrivate(PKCS8EncodedKeySpec(decode(field("key")))),
        )
        RoomIdentity(field("room"), field("secret"), pair)
    }.getOrNull()

    fun save(identity: RoomIdentity) {
        val o = buildJsonObject {
            put("room", identity.roomId)
            put("secret", identity.secret)
            put("key", encode(identity.key.private.encoded))
            put("pub", encode(identity.key.public.encoded))
        }
        file.writeText(o.toString())
    }

    fun clear() {
        file.delete()
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
