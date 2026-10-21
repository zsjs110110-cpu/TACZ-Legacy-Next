package com.tacz.legacy.common.resource

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.api.modifier.Modifier
import com.tacz.legacy.api.modifier.ModifierEvaluator
import com.tacz.legacy.common.config.LegacyConfigManager
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import kotlin.math.roundToInt

public data class TACZNearbyFireSoundProfile(
    val soundDistance: Int,
    val useSilenceSound: Boolean,
)

public object TACZGunSoundRouting {
    @JvmStatic
    public fun resolveNearbyFireSoundProfile(stack: ItemStack): TACZNearbyFireSoundProfile {
        val defaultDistance = LegacyConfigManager.common.defaultGunFireSoundDistance.coerceAtLeast(0)
        val iGun = stack.item as? IGun ?: return TACZNearbyFireSoundProfile(defaultDistance, false)
        val modifiers = mutableListOf<Modifier>()
        val useSilenceFlags = mutableListOf<Boolean>()
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        // C10：独占命中时整份替换配件 data，silence 也必须走同一份数据，
        // 否则会出现"配件已被独占数据替换、消音标记仍来自索引"的混合语义。
        val exclusiveMap = snapshot.guns[iGun.getGunId(stack)]?.data?.exclusiveAttachments ?: emptyMap()

        collectAttachmentIds(stack, iGun).forEach { attachmentId ->
            val rawModifier = TACZGunPropertyResolver.attachmentDataFor(snapshot, attachmentId, exclusiveMap)
                ?.modifiers
                ?.get("silence")
                ?.getValue() as? JsonElement
                ?: return@forEach
            val parsed = parseSilenceModifier(rawModifier) ?: return@forEach
            modifiers += parsed.distanceModifier
            useSilenceFlags += parsed.useSilenceSound
        }

        val distance = ModifierEvaluator.eval(modifiers, defaultDistance.toDouble()).roundToInt().coerceAtLeast(0)
        val useSilenceSound = ModifierEvaluator.eval(useSilenceFlags, false)
        return TACZNearbyFireSoundProfile(soundDistance = distance, useSilenceSound = useSilenceSound)
    }

    private fun collectAttachmentIds(stack: ItemStack, iGun: IGun): Set<ResourceLocation> {
        val result = linkedSetOf<ResourceLocation>()
        AttachmentType.values()
            .asSequence()
            .filter { type -> type != AttachmentType.NONE }
            .forEach { type ->
                val attachmentId = iGun.getAttachmentId(stack, type)
                if (attachmentId != DefaultAssets.EMPTY_ATTACHMENT_ID) {
                    result += attachmentId
                }
                val builtInAttachmentId = iGun.getBuiltInAttachmentId(stack, type)
                if (builtInAttachmentId != DefaultAssets.EMPTY_ATTACHMENT_ID) {
                    result += builtInAttachmentId
                }
            }
        return result
    }

    private fun parseSilenceModifier(element: JsonElement): TACZParsedSilenceModifier? {
        val jsonObject = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        val distanceModifier = jsonObject.parseDistanceModifier()
        val useSilenceSound = jsonObject.get("use_silence_sound")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        return TACZParsedSilenceModifier(distanceModifier = distanceModifier, useSilenceSound = useSilenceSound)
    }

    private fun JsonObject.parseDistanceModifier(): Modifier {
        get("distance")
            ?.takeIf { !it.isJsonNull }
            ?.let { return TACZJson.GSON.fromJson(it, Modifier::class.java) }
        val addend = get("distance_addend")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
        return Modifier(addend = addend)
    }
}

private data class TACZParsedSilenceModifier(
    val distanceModifier: Modifier,
    val useSilenceSound: Boolean,
)