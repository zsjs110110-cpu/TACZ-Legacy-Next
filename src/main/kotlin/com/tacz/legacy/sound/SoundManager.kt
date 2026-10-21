package com.tacz.legacy.sound

import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageSound
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.util.ResourceLocation

public object SoundManager {
    public const val GUN: String = "gun"
    public const val TARGET_BLOCK_HIT: String = "target_block_hit"

    public const val SHOOT_SOUND: String = "shoot"
    public const val SHOOT_3P_SOUND: String = "shoot_3p"
    public const val SILENCE_SOUND: String = "silence"
    public const val SILENCE_3P_SOUND: String = "silence_3p"
    public const val MELEE_BAYONET: String = "melee_bayonet"
    public const val MELEE_PUSH: String = "melee_push"
    public const val MELEE_STOCK: String = "melee_stock"
    public const val DRY_FIRE_SOUND: String = "dry_fire"
    public const val RELOAD_EMPTY_SOUND: String = "reload_empty"
    public const val RELOAD_TACTICAL_SOUND: String = "reload_tactical"
    public const val INSPECT_EMPTY_SOUND: String = "inspect_empty"
    public const val INSPECT_SOUND: String = "inspect"
    public const val DRAW_SOUND: String = "draw"
    public const val PUT_AWAY_SOUND: String = "put_away"
    public const val BOLT_SOUND: String = "bolt"
    public const val FIRE_SELECT: String = "fire_select"
    public const val HEAD_HIT_SOUND: String = "head_hit"
    public const val FLESH_HIT_SOUND: String = "flesh_hit"
    public const val KILL_SOUND: String = "kill"
    public const val UNINSTALL_SOUND: String = "uninstall"
    public const val INSTALL_SOUND: String = "install"

    @JvmStatic
    public fun sendSoundToNearby(
        sourceEntity: EntityLivingBase,
        distance: Int,
        gunId: ResourceLocation,
        gunDisplayId: ResourceLocation,
        soundName: String,
        volume: Float,
        pitch: Float,
    ) {
        if (distance <= 0 || sourceEntity.world.isRemote) {
            return
        }
        val distanceSq = distance.toDouble() * distance.toDouble()
        sourceEntity.world.playerEntities
            .asSequence()
            .filterIsInstance<EntityPlayerMP>()
            .filter { player ->
                player.entityId != sourceEntity.entityId &&
                    player.dimension == sourceEntity.dimension &&
                    player.getDistanceSq(sourceEntity.posX, sourceEntity.posY, sourceEntity.posZ) < distanceSq
            }
            .forEach { player ->
                TACZNetworkHandler.sendToPlayer(
                    ServerMessageSound(sourceEntity.entityId, gunId, gunDisplayId, soundName, volume, pitch, distance),
                    player,
                )
            }
    }

    @JvmStatic
    public fun knownKeys(): Set<String> = linkedSetOf(
        GUN,
        TARGET_BLOCK_HIT,
        SHOOT_SOUND,
        SHOOT_3P_SOUND,
        SILENCE_SOUND,
        SILENCE_3P_SOUND,
        MELEE_BAYONET,
        MELEE_PUSH,
        MELEE_STOCK,
        DRY_FIRE_SOUND,
        RELOAD_EMPTY_SOUND,
        RELOAD_TACTICAL_SOUND,
        INSPECT_EMPTY_SOUND,
        INSPECT_SOUND,
        DRAW_SOUND,
        PUT_AWAY_SOUND,
        BOLT_SOUND,
        FIRE_SELECT,
        HEAD_HIT_SOUND,
        FLESH_HIT_SOUND,
        KILL_SOUND,
        UNINSTALL_SOUND,
        INSTALL_SOUND,
    )
}
