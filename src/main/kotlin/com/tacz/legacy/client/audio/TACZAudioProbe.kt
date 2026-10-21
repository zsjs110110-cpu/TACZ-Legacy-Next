package com.tacz.legacy.client.audio

import com.tacz.legacy.client.sound.GunPackAssetLocator
import net.minecraft.util.ResourceLocation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipFile

internal object TACZAudioProbe {
    private data class LocatedAudioAsset(
        val packFile: File,
        val assetLocation: ResourceLocation,
        val byteSize: Long?,
    )

    private data class ProbeResult(
        val status: TACZAudioProbeStatus,
        val channels: Int? = null,
        val sampleRate: Int? = null,
        val notes: String? = null,
    )

    @JvmStatic
    fun buildManifest(
        packSources: List<File>,
        audioReferences: Map<ResourceLocation, Set<TACZAudioReference>>,
    ): TACZAudioManifest {
        val entries = LinkedHashMap<ResourceLocation, TACZAudioAssetDescriptor>()
        for ((soundId, references) in audioReferences) {
            entries[soundId] = describeSound(packSources, soundId, references)
        }
        return TACZAudioManifest(entries)
    }

    private fun describeSound(
        packSources: List<File>,
        soundId: ResourceLocation,
        references: Set<TACZAudioReference>,
    ): TACZAudioAssetDescriptor {
        val candidates = candidateAssetLocations(soundId)
        val located = candidates.asSequence()
            .mapNotNull { candidate -> locateAsset(packSources, candidate) }
            .firstOrNull()

        if (located == null) {
            return TACZAudioAssetDescriptor(
                soundId = soundId,
                assetLocation = null,
                sourcePack = null,
                exists = false,
                byteSize = null,
                probeStatus = TACZAudioProbeStatus.MISSING,
                channels = null,
                sampleRate = null,
                references = references.toList(),
                notes = "No pack asset found for ${candidates.joinToString()}",
            )
        }

        val probe = probeAsset(located)
        return TACZAudioAssetDescriptor(
            soundId = soundId,
            assetLocation = located.assetLocation,
            sourcePack = located.packFile.name,
            exists = true,
            byteSize = located.byteSize,
            probeStatus = probe.status,
            channels = probe.channels,
            sampleRate = probe.sampleRate,
            references = references.toList(),
            notes = probe.notes,
        )
    }

    private fun candidateAssetLocations(soundId: ResourceLocation): List<ResourceLocation> {
        val candidates = LinkedHashSet<ResourceLocation>()
        val path = soundId.path.trimStart('/')

        fun add(pathCandidate: String) {
            candidates.add(ResourceLocation(soundId.namespace, pathCandidate.trimStart('/')))
        }

        when {
            path.lowercase(Locale.ROOT).endsWith(".ogg") -> add(path)
            path.startsWith("tacz_sounds/") || path.startsWith("sounds/") -> add("$path.ogg")
            else -> {
                add("tacz_sounds/$path.ogg")
                add("sounds/$path.ogg")
            }
        }
        return candidates.toList()
    }

    private fun locateAsset(packSources: List<File>, assetLocation: ResourceLocation): LocatedAudioAsset? {
        val assetPath = "assets/${assetLocation.namespace}/${assetLocation.path}"
        for (packSource in packSources) {
            if (!packSource.exists()) {
                continue
            }
            if (packSource.isDirectory) {
                val path = packSource.toPath().resolve(assetPath)
                if (Files.isRegularFile(path)) {
                    return LocatedAudioAsset(packSource, assetLocation, runCatching { Files.size(path) }.getOrNull())
                }
                continue
            }
            if (!packSource.isFile || !packSource.name.lowercase(Locale.ROOT).endsWith(".zip")) {
                continue
            }
            runCatching {
                ZipFile(packSource).use { zipFile ->
                    val entry = zipFile.getEntry(assetPath) ?: return@use null
                    LocatedAudioAsset(packSource, assetLocation, entry.size.takeIf { it >= 0L })
                }
            }.getOrNull()?.let { located ->
                return located
            }
        }
        return null
    }

    private fun probeAsset(located: LocatedAudioAsset): ProbeResult {
        return try {
            val stream = GunPackAssetLocator.openResource(listOf(located.packFile), located.assetLocation)
                ?: return ProbeResult(
                    status = TACZAudioProbeStatus.MISSING,
                    notes = "Located metadata but stream could not be opened",
                )
            stream.use(::probeVorbis)
        } catch (error: Exception) {
            ProbeResult(
                status = TACZAudioProbeStatus.IO_ERROR,
                notes = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun probeVorbis(stream: InputStream): ProbeResult {
        val packet = readFirstOggPacket(stream)
            ?: return ProbeResult(
                status = TACZAudioProbeStatus.INVALID_OGG_CAPTURE,
                notes = "Could not read initial OGG packet",
            )

        if (packet.size < 30) {
            return ProbeResult(
                status = TACZAudioProbeStatus.INVALID_VORBIS_IDENTIFICATION,
                notes = "Vorbis identification packet is too short (${packet.size} bytes)",
            )
        }

        val signature = packet.copyOfRange(1, 7).toString(StandardCharsets.US_ASCII)
        if (packet[0].toInt() != 1 || signature != "vorbis") {
            return ProbeResult(
                status = TACZAudioProbeStatus.INVALID_VORBIS_IDENTIFICATION,
                notes = "Missing Vorbis identification header",
            )
        }

        val channels = packet[11].toInt() and 0xFF
        val sampleRate = readLittleEndianInt(packet, 12)
        if (channels <= 0 || sampleRate <= 0) {
            return ProbeResult(
                status = TACZAudioProbeStatus.INVALID_VORBIS_IDENTIFICATION,
                notes = "Invalid channel/sample-rate header",
            )
        }

        return ProbeResult(
            status = TACZAudioProbeStatus.SUPPORTED_OGG_VORBIS,
            channels = channels,
            sampleRate = sampleRate,
        )
    }

    private fun readFirstOggPacket(stream: InputStream): ByteArray? {
        val packet = ByteArrayOutputStream()
        while (true) {
            val header = readExactly(stream, 27) ?: return null
            if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() || header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()) {
                return null
            }

            val pageSegments = header[26].toInt() and 0xFF
            val segmentTable = readExactly(stream, pageSegments) ?: return null
            val bodyLength = segmentTable.sumOf { it.toInt() and 0xFF }
            val body = readExactly(stream, bodyLength) ?: return null

            var offset = 0
            for (segmentSizeByte in segmentTable) {
                val segmentSize = segmentSizeByte.toInt() and 0xFF
                packet.write(body, offset, segmentSize)
                offset += segmentSize
                if (segmentSize < 0xFF) {
                    return packet.toByteArray()
                }
            }
        }
    }

    private fun readExactly(stream: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = stream.read(buffer, offset, length - offset)
            if (read < 0) {
                return null
            }
            offset += read
        }
        return buffer
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}