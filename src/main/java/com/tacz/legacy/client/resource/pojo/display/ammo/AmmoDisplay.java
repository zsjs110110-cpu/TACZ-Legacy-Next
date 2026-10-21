package com.tacz.legacy.client.resource.pojo.display.ammo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Ammo display POJO — deserialized from gun pack ammo display JSON.
 * Port of upstream TACZ AmmoDisplay.
 */
public class AmmoDisplay {
    @SerializedName("model")
    private ResourceLocation modelLocation;

    @SerializedName("texture")
    private ResourceLocation modelTexture;

    @Nullable
    @SerializedName("slot")
    private ResourceLocation slotTextureLocation;

    @Nullable
    @SerializedName("entity")
    private AmmoEntityDisplay ammoEntity;

    @Nullable
    @SerializedName("shell")
    private ShellDisplay shellDisplay;

    /**
     * 弹药飞行/命中粒子配置（上游 AmmoDisplay.particle）。
     * 与 {@code GunAmmo.particle} 同构；1.12.2 粒子注册名与上游不同，
     * 当前移植层没有对应消费路径，字段仅解析保留。
     */
    @Nullable
    @SerializedName("particle")
    private AmmoParticle particle;

    @Nullable
    @SerializedName("transform")
    private AmmoTransform transform;

    @SerializedName("tracer_color")
    private String tracerColor = "0xFFFFFF";

    public ResourceLocation getModelLocation() {
        return modelLocation;
    }

    public ResourceLocation getModelTexture() {
        return modelTexture;
    }

    @Nullable
    public ResourceLocation getSlotTextureLocation() {
        return slotTextureLocation;
    }

    @Nullable
    public AmmoEntityDisplay getAmmoEntity() {
        return ammoEntity;
    }

    @Nullable
    public ShellDisplay getShellDisplay() {
        return shellDisplay;
    }

    @Nullable
    public AmmoParticle getParticle() {
        return particle;
    }

    @Nullable
    public AmmoTransform getTransform() {
        return transform;
    }

    public String getTracerColor() {
        return tracerColor;
    }

    /**
     * Post-deserialization texture path conversion.
     * Converts short texture names to full resource paths.
     */
    public void init() {
        if (modelTexture != null) {
            modelTexture = expandTexturePath(modelTexture);
        }
        if (slotTextureLocation != null) {
            slotTextureLocation = expandTexturePath(slotTextureLocation);
        }
        if (ammoEntity != null && ammoEntity.modelTexture != null) {
            ammoEntity.modelTexture = expandTexturePath(ammoEntity.modelTexture);
        }
        if (shellDisplay != null && shellDisplay.modelTexture != null) {
            shellDisplay.modelTexture = expandTexturePath(shellDisplay.modelTexture);
        }
    }

    private static ResourceLocation expandTexturePath(ResourceLocation shortId) {
        return new ResourceLocation(shortId.getNamespace(), "textures/" + shortId.getPath() + ".png");
    }
}
