package com.tacz.legacy.client.util;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 只读探针：开光影开镜时打印当前绑定 FBO 的 depth/stencil 附件状态。
 *
 * <p>用途：判断上游 1.20.1 那套「把 OptiFine 的 depth texture 就地重挂成
 * DEPTH24_STENCIL8」在本环境（1.12.2 + OptiFine G5）是否可行。上游的动手前提是
 * depth texture 的 GL_TEXTURE_DEPTH_TYPE 等于 GL_UNSIGNED_NORMALIZED（纯 depth、
 * 尚未带 stencil）；本探针把这条判据连同 FBO 状态、尺寸、错误码一起打出来。
 *
 * <p>**只读**：不改任何 GL 状态（绑纹理后会恢复），失败也只打日志。
 */
public final class StencilProbe {

    private static final int GL_TEXTURE_DEPTH_TYPE_ID = 0x8C16;
    private static final int GL_UNSIGNED_NORMALIZED_ID = 0x8C17;

    private static boolean logged;

    private StencilProbe() {
    }

    public static void probeOnce() {
        if (logged) {
            return;
        }
        logged = true;
        int prevTex = 0;
        try {
            int fbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            int stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS);
            int depthObj = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int depthType = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int stencilType = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);

            StringBuilder sb = new StringBuilder("[StencilProbe] ");
            sb.append("fbo=").append(fbo)
                    .append(" status=0x").append(Integer.toHexString(status))
                    .append(" stencilBits=").append(stencilBits)
                    .append(" depthObj=").append(depthObj)
                    .append(" depthAttachType=0x").append(Integer.toHexString(depthType))
                    .append(" stencilAttachType=0x").append(Integer.toHexString(stencilType));

            if (depthObj != 0) {
                prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthObj);
                int depthTexType = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL_TEXTURE_DEPTH_TYPE_ID);
                int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
                prevTex = 0;
                sb.append(" depthTexType=0x").append(Integer.toHexString(depthTexType))
                        .append(" size=").append(w).append('x').append(h)
                        .append(" unsignedNormalized=").append(depthTexType == GL_UNSIGNED_NORMALIZED_ID);
            }

            int err = GL11.glGetError();
            sb.append(" glError=0x").append(Integer.toHexString(err));
            System.out.println(sb);
        } catch (Throwable t) {
            System.out.println("[StencilProbe] failed: " + t);
        } finally {
            if (prevTex != 0 && prevTex != -1) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            }
        }
    }
}
