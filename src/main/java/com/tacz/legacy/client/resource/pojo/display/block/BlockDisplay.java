package com.tacz.legacy.client.resource.pojo.display.block;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Block display POJO — deserialized from gun pack block display JSON.
 * Port of upstream TACZ BlockDisplay.
 */
public class BlockDisplay {
    @SerializedName("model")
    private ResourceLocation modelLocation;

    @SerializedName("texture")
    private ResourceLocation modelTexture;

    @SerializedName("transforms")
    @Nullable
    private BlockTransform transforms;

    public ResourceLocation getModelLocation() {
        return modelLocation;
    }

    public ResourceLocation getModelTexture() {
        return modelTexture;
    }

    @Nullable
    public BlockTransform getTransforms() {
        return transforms;
    }

    /**
     * Post-deserialization texture path conversion.
     */
    public void init() {
        if (modelTexture != null) {
            modelTexture = expandTexturePath(modelTexture);
        }
    }

    private static ResourceLocation expandTexturePath(ResourceLocation shortId) {
        return new ResourceLocation(shortId.getNamespace(), "textures/" + shortId.getPath() + ".png");
    }
}
