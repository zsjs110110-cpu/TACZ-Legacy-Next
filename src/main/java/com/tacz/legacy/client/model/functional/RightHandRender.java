package com.tacz.legacy.client.model.functional;

import com.tacz.legacy.client.model.BedrockAnimatedModel;
import com.tacz.legacy.client.model.IFunctionalRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Renders the player's right arm at the position of the "righthand_pos" bone.
 * Port of upstream TACZ RightHandRender, adapted for 1.12.2 GL immediate mode.
 */
public class RightHandRender implements IFunctionalRenderer {
    private final BedrockAnimatedModel bedrockGunModel;
    @Nullable
    private ResourceLocation gunTexture;

    public RightHandRender(BedrockAnimatedModel bedrockGunModel) {
        this.bedrockGunModel = bedrockGunModel;
    }

    public void setGunTexture(@Nullable ResourceLocation gunTexture) {
        this.gunTexture = gunTexture;
    }

    @Override
    public void render(int light) {
        if (!bedrockGunModel.getRenderHand()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        AbstractClientPlayer player = mc.player;
        if (player == null) return;

        // Flip Z 180° to correct orientation in bedrock model space
        GlStateManager.rotate(180f, 0f, 0f, 1f);

        // Bind player skin and render right arm
        mc.getTextureManager().bindTexture(player.getLocationSkin());
        Render<?> entityRenderer = mc.getRenderManager().getEntityRenderObject(player);
        RenderPlayer renderer = entityRenderer instanceof RenderPlayer ? (RenderPlayer) entityRenderer : null;
        if (renderer != null) {
            renderer.renderRightArm(player);
        }

        // Restore gun texture for subsequent model parts
        if (gunTexture != null) {
            mc.getTextureManager().bindTexture(gunTexture);
        }
    }
}
