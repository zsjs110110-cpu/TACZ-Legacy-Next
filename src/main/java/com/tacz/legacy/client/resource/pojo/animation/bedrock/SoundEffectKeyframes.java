package com.tacz.legacy.client.resource.pojo.animation.bedrock;

import it.unimi.dsi.fastutil.doubles.Double2ObjectRBTreeMap;
import net.minecraft.util.ResourceLocation;

public class SoundEffectKeyframes {
    private final Double2ObjectRBTreeMap<ResourceLocation> keyframes;

    public SoundEffectKeyframes(Double2ObjectRBTreeMap<ResourceLocation> keyframes) {
        this.keyframes = keyframes;
    }

    public Double2ObjectRBTreeMap<ResourceLocation> getKeyframes() {
        return keyframes;
    }
}
