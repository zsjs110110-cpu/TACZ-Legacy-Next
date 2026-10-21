package com.tacz.legacy.client.audio

import net.minecraft.util.ResourceLocation

internal enum class TACZAudioBackendMode(private vararg val aliases: String) {
DIRECT_OPENAL("direct-openal", "direct_openal", "openal"),
VANILLA_MINECRAFT("vanilla-minecraft", "vanilla_minecraft", "soundhandler", "sound-handler", "paulscode", "legacy-minecraft", "legacy", "minecraft"),
    NULL("null", "silent"),
    DIAGNOSTIC("diagnostic", "inspect", "probe");

    internal companion object {
        @JvmStatic
        fun fromProperty(rawValue: String?): TACZAudioBackendMode {
            val normalized = rawValue?.trim()?.lowercase().orEmpty()
            // vanilla 后端走 paulscode，会在持 THREAD_SYNC 全局锁的命令线程同步解码 OGG 导致卡死；
            // DIRECT_OPENAL 预解码并缓存 PCM，已验证不卡，因此未匹配到别名时默认用它。
            return values().firstOrNull { mode ->
                mode.aliases.any { alias -> alias == normalized }
            } ?: DIRECT_OPENAL
        }
    }
}

internal enum class TACZAudioRequestOrigin {
    GENERIC,
    ANIMATION,
    SERVER_MESSAGE,
}

internal enum class TACZAudioProbeStatus(val dedicatedCompatible: Boolean) {
    SUPPORTED_OGG_VORBIS(true),
    MISSING(false),
    INVALID_OGG_CAPTURE(false),
    INVALID_VORBIS_IDENTIFICATION(false),
    IO_ERROR(false),
    UNTRACKED(false),
}

internal enum class TACZAudioSubmissionDisposition {
    SUBMITTED_TO_BACKEND,
    RECORDED_ONLY,
    DROPPED,
}

internal data class TACZAudioReference(
    val sourceType: String,
    val ownerId: ResourceLocation? = null,
    val key: String? = null,
)

internal data class TACZAudioAssetDescriptor(
    val soundId: ResourceLocation,
    val assetLocation: ResourceLocation?,
    val sourcePack: String?,
    val exists: Boolean,
    val byteSize: Long?,
    val probeStatus: TACZAudioProbeStatus,
    val channels: Int?,
    val sampleRate: Int?,
    val references: List<TACZAudioReference>,
    val notes: String?,
)

internal data class TACZAudioManifest(
    val entries: Map<ResourceLocation, TACZAudioAssetDescriptor>,
) {
    val totalCount: Int
        get() = entries.size

    val supportedCount: Int
        get() = entries.values.count { it.probeStatus.dedicatedCompatible }

    val incompatibleCount: Int
        get() = entries.values.count { !it.probeStatus.dedicatedCompatible }

    val missingCount: Int
        get() = entries.values.count { it.probeStatus == TACZAudioProbeStatus.MISSING }
}

internal data class TACZAudioSubmissionRecord(
    val origin: TACZAudioRequestOrigin,
    val soundId: ResourceLocation,
    val backendMode: TACZAudioBackendMode,
    val probeStatus: TACZAudioProbeStatus,
    val disposition: TACZAudioSubmissionDisposition,
    val notes: String?,
)