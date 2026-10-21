package com.tacz.legacy.client.sound;

import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

public interface TACZClientSoundHandle {
    @Nullable
    ResourceLocation getSoundId();

    void stop();
}
