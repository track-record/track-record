package com.trackrecord.data.library

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.id3.framebody.FrameBodyUFID
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.net.URI
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads tags on the desktop with jaudiotagger (PLATFORMS.md phase 3), keyed as Media3TagReader keys them on
 * Android, so [com.trackrecord.engine.year.TagYears] reads both alike: ID3 frame ids ("TXXX:<description>",
 * "UFID:<owner>"), Vorbis comment names, MP4 atoms as the ID3 frames Media3 maps them to, and MP4 freeform atoms
 * as "----:<mean>:<name>". Opus, which jaudiotagger doesn't read, through [OpusTags].
 */
class JaudiotaggerTagReader : TagReader {
    init {
        // jaudiotagger logs every file it reads.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    override suspend fun read(uri: String): RawTags {
        val file = File(URI(uri))
        val fields = LinkedHashMap<String, String>()
        fun put(key: String, value: String?) {
            if (value.isNullOrBlank()) return
            fields.putIfAbsent(key.uppercase(), value.trim().trimEnd('\u0000'))
        }
        if (file.extension.equals("opus", ignoreCase = true)) {
            val opus = OpusTags.read(file, ::put) ?: throw IllegalArgumentException("not an Opus file: $uri")
            return opus.copy(fields = fields)
        }
        val audio = AudioFileIO.read(file)
        var hasCover = false
        when (val tag = audio.tag) {
            is AbstractID3v2Tag -> for (field in tag.fields) {
                val frame = field as? AbstractID3v2Frame ?: continue
                when (val body = frame.body) {
                    is FrameBodyTXXX -> put("TXXX:${body.description}", body.firstTextValue)
                    is FrameBodyUFID -> put("UFID:${body.owner}", String(body.uniqueIdentifier, Charsets.US_ASCII))
                    is AbstractFrameBodyTextInfo -> put(frame.identifier, body.values.joinToString("; "))
                    else -> if (frame.identifier == "APIC") hasCover = true
                }
            }
            is FlacTag -> {
                tag.vorbisCommentTag?.let { vorbis(it, ::put) }
                hasCover = tag.images.isNotEmpty()
            }
            is VorbisCommentTag -> {
                vorbis(tag, ::put)
                hasCover = tag.hasField("METADATA_BLOCK_PICTURE")
            }
            is Mp4Tag -> for (field in tag.fields) {
                when {
                    field is Mp4TagReverseDnsField -> put("----:${field.issuer}:${field.descriptor}", field.content)
                    field.id == "covr" -> hasCover = true
                    field is TagTextField -> put(MP4_AS_ID3[field.id] ?: field.id, field.content)
                }
            }
        }
        val durationMs = (audio.audioHeader.preciseTrackLength * 1000).toLong()
        return RawTags(fields, durationMs, hasCover)
    }

    private fun vorbis(tag: VorbisCommentTag, put: (String, String?) -> Unit) {
        for (field: TagField in tag.fields) if (field is TagTextField) put(field.id, field.content)
    }

    private companion object {
        /** Media3 hands MP4's metadata atoms over as these ID3 frames (MetadataUtil). */
        val MP4_AS_ID3 = mapOf("©nam" to "TIT2", "©ART" to "TPE1", "aART" to "TPE2", "©alb" to "TALB", "©day" to "TDRC")
    }
}
