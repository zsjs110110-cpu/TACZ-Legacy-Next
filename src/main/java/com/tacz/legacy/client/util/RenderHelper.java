package com.tacz.legacy.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;

public final class RenderHelper {
    private RenderHelper() {
    }

    /**
     * 在客户端初始化阶段为主帧缓冲启用模板缓冲。
     * <p>
     * {@link Framebuffer#enableStencil()} 会重新创建帧缓冲，若在渲染一帧的中途（例如第一次开镜时）调用，
     * 会清空当前帧内容并导致画面闪烁/错乱，因此必须提前调用。
     */
    public static void ensureMainFramebufferStencil() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        Framebuffer framebuffer = minecraft.getFramebuffer();
        if (framebuffer != null && !framebuffer.isStencilEnabled()) {
            framebuffer.enableStencil();
        }
    }

    public static boolean enableItemEntityStencilTest() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return false;
        }
        Framebuffer framebuffer = minecraft.getFramebuffer();
        // 帧缓冲不可用或未带模板缓冲时不要在渲染中途重建帧缓冲，直接放弃模板遮罩
        if (framebuffer == null || !framebuffer.isStencilEnabled()) {
            return false;
        }
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        return true;
    }

    public static void disableItemEntityStencilTest() {
        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }
}