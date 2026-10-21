package com.tacz.legacy.client.model;

import com.tacz.legacy.client.model.bedrock.BedrockCube;
import com.tacz.legacy.client.model.bedrock.BedrockRenderMode;
import com.tacz.legacy.client.model.bedrock.BedrockPart;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * visible的优先级低于FunctionalBedrockPart，当visible为false的时候，仍然会执行functionalRenderers。
 * Adapted from upstream for 1.12.2 GL immediate mode rendering.
 */
public class FunctionalBedrockPart extends BedrockPart {
    public @Nullable Function<BedrockPart, IFunctionalRenderer> functionalRenderer;

    public FunctionalBedrockPart(@Nullable Function<BedrockPart, IFunctionalRenderer> functionalRenderer, @Nonnull String name) {
        super(name);
        this.functionalRenderer = functionalRenderer;
    }

    public FunctionalBedrockPart(@Nullable Function<BedrockPart, IFunctionalRenderer> functionalRenderer, @Nonnull BedrockPart part) {
        super(part.name);
        this.cubes.addAll(part.cubes);
        this.children.addAll(part.children);
        this.x = part.x;
        this.y = part.y;
        this.z = part.z;
        this.xRot = part.xRot;
        this.yRot = part.yRot;
        this.zRot = part.zRot;
        this.offsetX = part.offsetX;
        this.offsetY = part.offsetY;
        this.offsetZ = part.offsetZ;
        this.visible = part.visible;
        this.mirror = part.mirror;
        this.setInitRotationAngle(part.getInitRotX(), part.getInitRotY(), part.getInitRotZ());
        this.xScale = part.xScale;
        this.yScale = part.yScale;
        this.zScale = part.zScale;
        this.functionalRenderer = functionalRenderer;
    }

    @Override
    public void render() {
        render(BedrockRenderMode.NORMAL, false);
    }

    @Override
    public void render(BedrockRenderMode mode, boolean inheritedIllumination) {
        float prevBX = OpenGlHelper.lastBrightnessX;
        float prevBY = OpenGlHelper.lastBrightnessY;

        int cubePackedLight = (int) prevBX | ((int) prevBY << 16);
        boolean subtreeIlluminated = inheritedIllumination || illuminated;
        if (subtreeIlluminated && !inheritedIllumination) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
            cubePackedLight = 240 | (240 << 16);
        }

        GlStateManager.pushMatrix();
        this.translateAndRotateAndScale();

        if (functionalRenderer != null) {
            @Nullable IFunctionalRenderer renderer = functionalRenderer.apply(this);
            if (renderer != null) {
                if (mode == BedrockRenderMode.BLOOM) {
                    renderer.renderBloom(cubePackedLight);
                } else {
                    renderer.render(cubePackedLight);
                }
            } else {
                renderDefaultContent(mode, subtreeIlluminated);
            }
        } else {
            renderDefaultContent(mode, subtreeIlluminated);
        }

        if (subtreeIlluminated && !inheritedIllumination) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevBX, prevBY);
        }

        GlStateManager.popMatrix();
    }

    private void renderDefaultContent(BedrockRenderMode mode, boolean inheritedIllumination) {
        if (this.visible) {
            boolean renderSelf = mode != BedrockRenderMode.BLOOM || inheritedIllumination;
            if (renderSelf && !this.cubes.isEmpty()) {
                Tessellator tessellator = Tessellator.getInstance();
                BufferBuilder buffer = tessellator.getBuffer();
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
                for (BedrockCube cube : this.cubes) {
                    cube.compile(buffer);
                }
                tessellator.draw();
            }
            for (BedrockPart part : this.children) {
                part.render(mode, inheritedIllumination);
            }
        }
    }
}
