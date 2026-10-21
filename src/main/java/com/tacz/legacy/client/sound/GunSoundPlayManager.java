package com.tacz.legacy.client.sound;

import com.tacz.legacy.TACZLegacy;
import com.tacz.legacy.client.audio.TACZAudioRequestOrigin;
import com.tacz.legacy.client.audio.TACZAudioRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import javax.annotation.Nullable;

/**
 * Client-side sound playback for gun animation sounds.
 * Port of upstream TACZ SoundPlayManager, adapted for 1.12.2.
 */
@SideOnly(Side.CLIENT)
public class GunSoundPlayManager {
    private static final Marker MARKER = MarkerManager.getMarker("GunSound");
    private static final SoundPlaybackBackend MINECRAFT_BACKEND = GunSoundPlayManager::playWithMinecraft;
    @Nullable
    private static volatile SoundPlaybackBackend testingPlaybackBackend;

    @Nullable
    public static TACZClientSoundHandle playClientSound(@Nullable Entity entity, @Nullable ResourceLocation soundName, float volume, float pitch, int distance) {
        return playClientSound(entity, soundName, volume, pitch, distance, TACZAudioRequestOrigin.GENERIC);
    }

    @Nullable
    public static TACZClientSoundHandle playAnimationSound(@Nullable Entity entity, @Nullable ResourceLocation soundName, float volume, float pitch, int distance) {
        return playClientSound(entity, soundName, volume, pitch, distance, TACZAudioRequestOrigin.ANIMATION);
    }

    @Nullable
    public static TACZClientSoundHandle playNetworkSound(@Nullable Entity entity, @Nullable ResourceLocation soundName, float volume, float pitch, int distance) {
        return playClientSound(entity, soundName, volume, pitch, distance, TACZAudioRequestOrigin.SERVER_MESSAGE);
    }

    @Nullable
    private static TACZClientSoundHandle playClientSound(@Nullable Entity entity, @Nullable ResourceLocation soundName, float volume, float pitch, int distance, TACZAudioRequestOrigin origin) {
        if (soundName == null) {
            return null;
        }
        SoundPlaybackBackend backend = testingPlaybackBackend;
        if (backend != null) {
            return backend.play(entity, soundName, volume, pitch, distance, origin);
        }
        return TACZAudioRuntime.play(entity, soundName, volume, pitch, distance, origin, MINECRAFT_BACKEND);
    }

    /**
     * Public test seam for interval/sound-channel tests.
     */
    public static void setPlaybackBackendForTesting(SoundPlaybackBackend backend) {
        testingPlaybackBackend = backend;
    }

    public static void resetPlaybackBackendForTesting() {
        testingPlaybackBackend = null;
    }

    public static float applyClientDistanceMix(@Nullable Entity entity, float volume, int distance) {
        if (entity == null || distance <= 0) {
            return volume;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            EntityPlayerSP player = minecraft == null ? null : minecraft.player;
            if (player == null) {
                return volume;
            }
            float mixVolume = volume * (1.0F - Math.min(1.0F, (float) Math.sqrt(player.getDistanceSq(entity.posX, entity.posY, entity.posZ)) / distance));
            return mixVolume * mixVolume;
        } catch (NoClassDefFoundError ignored) {
            return volume;
        }
    }

    @Nullable
    private static TACZClientSoundHandle playWithMinecraft(@Nullable Entity entity, ResourceLocation soundName, float volume, float pitch, int distance, TACZAudioRequestOrigin origin) {
        if (entity == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.getSoundHandler() == null) {
            return null;
        }
        GunSoundInstance instance = new GunSoundInstance(entity, distance, soundName, volume, pitch);
        TACZLegacy.logger.debug(MARKER, "Playing gun pack sound {} via {} (vol={}, pitch={}, dist={})", soundName, origin, volume, pitch, distance);
        minecraft.getSoundHandler().playSound(instance);
        return instance;
    }

    @FunctionalInterface
    public interface SoundPlaybackBackend {
        @Nullable
        TACZClientSoundHandle play(@Nullable Entity entity, ResourceLocation soundName, float volume, float pitch, int distance, TACZAudioRequestOrigin origin);
    }
}
