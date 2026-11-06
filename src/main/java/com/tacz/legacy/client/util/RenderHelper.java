package com.tacz.legacy.client.util;

import com.tacz.legacy.TACZLegacy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class RenderHelper {
    /** 诊断用：上次打印时的绑定帧缓冲与模板状态，仅在变化时打印。 */
    private static int lastDiagFbo = Integer.MIN_VALUE;
    private static boolean lastDiagStencil = false;

    /** GL_TEXTURE_DEPTH_TYPE（GL 1.4 / ARB_depth_texture），LWJGL2 的 GL11 里没有常量。 */
    private static final int GL_TEXTURE_DEPTH_TYPE = 0x8C16;
    /** GL_UNSIGNED_NORMALIZED：纯 depth 纹理的标记，也是上游动手改格式的前提。 */
    private static final int GL_UNSIGNED_NORMALIZED = 0x8C17;

    /** one-shot diagnostic flag for the initial stencil clear state */
    private static boolean clearLogged;

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

    /**
     * TACZ_PROBE=resizebomb：反射调一次 OptiFine 的 {@code Shaders.scheduleResize()}，
     * 让它在下一帧走 {@code resize() -> setupFrameBuffer()} 把 dfb 和深度纹理删掉重建。
     * 这是「换弹/改分辨率之后镜筒缺失」那条回归的复现开关，不需要真的改动窗口尺寸。
     */
    private static boolean resizeProbeFired;

    private static void fireResizeProbe() {
        if (resizeProbeFired) {
            return;
        }
        String probe = System.getenv("TACZ_PROBE");
        if (probe == null || !probe.contains("resizebomb")) {
            return;
        }
        resizeProbeFired = true;
        try {
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
            shaders.getMethod("scheduleResize").invoke(null);
            TACZLegacy.logger.info("[ResizeProbe] Shaders.scheduleResize() called");
        } catch (Throwable throwable) {
            TACZLegacy.logger.warn("[ResizeProbe] failed: {}", throwable.toString());
        }
    }

    public static boolean enableItemEntityStencilTest() {
        fireResizeProbe();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return false;
        }
        // 必须检查当前绑定的帧缓冲，而不是主帧缓冲：
        // OptiFine 光影下手部渲染绑定的是 gbuffer 帧缓冲，它只有深度纹理、没有模板附件，
        // 此时 GL 规范规定模板测试恒通过、模板写入被忽略，继续走模板路径会让遮罩整体失效。
        int stencilBits;
        try {
            stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS);
        } catch (Throwable throwable) {
            stencilBits = 0;
        }
        if (stencilBits <= 0) {
            // 光影（OptiFine gbuffer）下当前 FBO 只有 depth texture、没有 stencil。
            // 照上游 1.20.1 的做法把这张 depth 纹理就地重挂成 DEPTH24_STENCIL8，
            // 模板测试就能恢复，模板路径（镜孔遮罩 + 分划裁剪）原样生效。
            if (Boolean.parseBoolean(System.getProperty("tacz.scope.stencilPatch", "true"))
                    && ensureShaderFramebufferStencil()) {
                try {
                    stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS);
                } catch (Throwable throwable) {
                    stencilBits = 0;
                }
            }
        }
        if (stencilBits > 0) {
            // 现在是健康的，把失败计数清零；否则一次偶发失败会永久关掉补丁。
            patchConsecutiveFailures = 0;
        }
        if (stencilBits <= 0) {
            logStencilDiag(false);
            return false;
        }
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        logStencilDiag(true);
        return true;
    }

    /**
     * 安全地清空当前帧缓冲的模板位。
     *
     * <p>直接 glClear(GL_STENCIL_BUFFER_BIT) 有两个经典陷阱，光影下尤其容易踩到：
     * <ol>
     *   <li>受 glStencilMask 影响——写掩码为 0 时清空<b>静默不生效</b>，旧模板值留到本帧，
     *       镜孔里会随机冒出镜身的黑色像素（也就是镜内黑色噪点）；</li>
     *   <li>受 GL_SCISSOR_TEST 影响——裁剪框外的模板清不掉，同样留下脏值。</li>
     * </ol>
     * 这里先把写掩码设为全 1、临时关掉裁剪，清完再恢复。
     */
    public static void clearStencilSafely() {
        // A/B test switch: -Dtacz.scope.stencilSafeClear=false restores the old plain clear.
        if (!Boolean.parseBoolean(System.getProperty("tacz.scope.stencilSafeClear", "true"))) {
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
            return;
        }
        boolean scissor = false;
        int mask = 0xFF;
        try {
            scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (!clearLogged) {
                clearLogged = true;
                TACZLegacy.logger.info("[StencilClear] stencilMask=0x{} scissor={}",
                        Integer.toHexString(GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK)), scissor);
            }
            mask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        } catch (Throwable ignored) {
        }
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glStencilMask(0xFF);
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glStencilMask(mask);
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    /** GL_RENDERBUFFER_BINDING（写错这个值会留下 0x500 GL_INVALID_ENUM 假错误）。 */
    private static final int GL_RENDERBUFFER_BINDING = 0x8CA7;
    /** GL_STENCIL_INDEX8。 */
    private static final int GL_STENCIL_INDEX8 = 0x8D48;

    /** 两次补挂之间的最小间隔：OptiFine 每帧重建 FBO 的病态情况下不能反复重分配深度纹理。 */
    private static final long PATCH_MIN_INTERVAL_MS = 200L;
    /** 连续失败上限：超过之后只降级、不再尝试，避免刷日志和每帧 GL 开销。 */
    private static final int PATCH_MAX_FAILURES = 8;
    private static long patchLastAttemptMs;
    private static int patchConsecutiveFailures;
    /** 方案 A 挂出去的 stencil renderbuffer，下次重挂前先收回，避免泄漏。 */
    private static int patchStencilRenderbuffer;

    /**
     * 给 OptiFine 的光影 FBO 补 stencil 位。
     *
     * <p><b>不能按 FBO id 记「补过了」。</b>{@code Shaders.resize() -> setupFrameBuffer()} 会先
     * {@code glDeleteFramebuffersEXT(dfb)} 再 {@code glGenFramebuffersEXT()}，GL 通常把刚释放的 id
     * 和纹理名原样还回来（本机实测两次都是 fbo=7 / depthTex=2114），而它新建的深度纹理是
     * depth-only 的。按 id 缓存就会判定「已补」并永久跳过，模板路径从此退化成无模板降级渲染
     * （症状：开镜镜筒缺失、镜内全黑，腰射时分划直接贴在屏幕上）。
     *
     * <p>调用方已经确认 {@code GL_STENCIL_BITS == 0}，所以这里只看当前状态、直接补挂，
     * 用节流 + 连续失败上限兜住异常重建。
     */
    private static boolean ensureShaderFramebufferStencil() {
        int prevTexture = 0;
        try {
            int fbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            if (fbo == 0) {
                return false; // 主帧缓冲，由 ensureMainFramebufferStencil 负责
            }
            long now = System.currentTimeMillis();
            if (patchConsecutiveFailures >= PATCH_MAX_FAILURES || now - patchLastAttemptMs < PATCH_MIN_INTERVAL_MS) {
                return false;
            }
            patchLastAttemptMs = now;
            // 先排空错误队列：glGetError 是全局的，不清掉的话日志里会把别人上一帧的错误算到本补丁头上
            for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
                // 丢弃
            }
            int depthName = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int depthType = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if (depthType != GL11.GL_TEXTURE) {
                return false;
            }
            prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthName);
            int depthTexType = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL_TEXTURE_DEPTH_TYPE);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
            prevTexture = 0;
            if (width <= 0 || height <= 0) {
                return false;
            }
            // UNSIGNED_NORMALIZED 是包首次加载那条路径建的深度纹理；resize 之后 OptiFine 的
            // setupFrameBuffer 用 GL_DEPTH_COMPONENT + GL_FLOAT 重建。两种都是「纯 depth、没带
            // stencil」，都可以动手。
            if (depthTexType != GL_UNSIGNED_NORMALIZED && depthTexType != GL11.GL_FLOAT) {
                return false;
            }

            // 方案 A：另挂一张独立的 stencil renderbuffer，光影自己的深度纹理一个字节都不动。
            // 优先它，因为 BSL 一类包会采样 depthtex，就地换成 DEPTH24_STENCIL8 会把 float
            // 深度换成 24bit 定点，属于改变包的行为。
            String way = tryStencilRenderbuffer(width, height) ? "stencil-renderbuffer" : null;
            if (way == null) {
                // 方案 B：上游 1.20.1 的做法——把深度纹理就地重指定成 DEPTH24_STENCIL8。
                // glTexImage2D 会重新分配纹理存储、丢掉当前帧的深度内容，所以只在真缺 stencil 时做一次。
                way = tryDepthStencilTexture(depthName, width, height) ? "depth24-stencil8" : null;
            }
            if (way == null) {
                patchConsecutiveFailures++;
                return false;
            }

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            int stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS);
            int error = GL11.glGetError();
            TACZLegacy.logger.info(
                    "[StencilPatch] fbo={} depthTex={} texType=0x{} size={}x{} way={} status=0x{} stencilBits={} glError=0x{}",
                    fbo, depthName, Integer.toHexString(depthTexType), width, height, way,
                    Integer.toHexString(status), stencilBits, Integer.toHexString(error));
            // 判定只看 FBO 完整性与 stencil 位数：glGetError 是全局队列，光影自己也会往里塞东西，
            // 拿它当失败条件会把一次真正成功的补挂误判成失败。
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE || stencilBits <= 0) {
                patchConsecutiveFailures++;
                return false;
            }
            patchConsecutiveFailures = 0;
            // 新分配的 stencil 内容是未定义的，立刻清成 0；否则残留值会让模板测试随机通过，
            // 表现为画面里出现成片的黑色噪点。
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
            return true;
        } catch (Throwable throwable) {
            TACZLegacy.logger.warn("[StencilPatch] failed: {}", throwable.toString());
            patchConsecutiveFailures++;
            return false;
        } finally {
            if (prevTexture != 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
            }
        }
    }

    /** 方案 A：挂一张独立的 GL_STENCIL_INDEX8 renderbuffer；不完整就收回附件并删掉它。 */
    private static boolean tryStencilRenderbuffer(int width, int height) {
        int prevRenderbuffer = -1;
        int rb = 0;
        try {
            prevRenderbuffer = GL11.glGetInteger(GL_RENDERBUFFER_BINDING);
            rb = GL30.glGenRenderbuffers();
            if (rb == 0) {
                return false;
            }
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rb);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL_STENCIL_INDEX8, width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, rb);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE
                    && GL11.glGetInteger(GL11.GL_STENCIL_BITS) > 0) {
                if (patchStencilRenderbuffer != 0 && patchStencilRenderbuffer != rb) {
                    GL30.glDeleteRenderbuffers(patchStencilRenderbuffer);
                }
                patchStencilRenderbuffer = rb;
                return true;
            }
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, 0);
            GL30.glDeleteRenderbuffers(rb);
            return false;
        } catch (Throwable throwable) {
            if (rb != 0) {
                try {
                    GL30.glDeleteRenderbuffers(rb);
                } catch (Throwable ignored) {
                }
            }
            return false;
        } finally {
            if (prevRenderbuffer >= 0) {
                try {
                    GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRenderbuffer);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 方案 B：把深度纹理就地重指定成 DEPTH24_STENCIL8，并挂到 GL_DEPTH_STENCIL_ATTACHMENT。 */
    private static boolean tryDepthStencilTexture(int depthName, int width, int height) {
        int prevTexture = 0;
        try {
            prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthName);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, width, height, 0,
                    GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (java.nio.ByteBuffer) null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, depthName, 0);
            return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        } catch (Throwable throwable) {
            return false;
        } finally {
            if (prevTexture != 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
            }
        }
    }

    /**
     * 一次性诊断：打印当前绑定的帧缓冲、模板位数以及 depth/stencil 附件信息。
     * <p>
     * 只在绑定帧缓冲或主帧缓冲模板状态变化时打印，避免刷屏。用于定位光影（OptiFine/Shaders）
     * 下模板遮罩失效的问题。
     */
    private static void logStencilDiag(boolean mainFramebufferStencil) {
        int boundFbo;
        try {
            boundFbo = GL11.glGetInteger(0x8CA6); // GL_FRAMEBUFFER_BINDING
        } catch (Throwable throwable) {
            boundFbo = -1;
        }
        if (boundFbo == lastDiagFbo && mainFramebufferStencil == lastDiagStencil) {
            return;
        }
        lastDiagFbo = boundFbo;
        lastDiagStencil = mainFramebufferStencil;

        int stencilBits = -1;
        try {
            stencilBits = GL11.glGetInteger(0x0D57); // GL_STENCIL_BITS
        } catch (Throwable ignored) {
        }

        int depthType = -1;
        int depthName = -1;
        int stencilType = -1;
        int stencilName = -1;
        try {
            Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30");
            java.lang.reflect.Method getAttachmentParameter = gl30.getMethod(
                    "glGetFramebufferAttachmentParameteri", int.class, int.class, int.class);
            int glFramebuffer = 0x8D40;
            int glDepthAttachment = 0x8D00;
            int glStencilAttachment = 0x8D20;
            int objectType = 0x8CD0; // GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
            int objectName = 0x8CD1; // GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
            depthType = (Integer) getAttachmentParameter.invoke(null, glFramebuffer, glDepthAttachment, objectType);
            depthName = (Integer) getAttachmentParameter.invoke(null, glFramebuffer, glDepthAttachment, objectName);
            stencilType = (Integer) getAttachmentParameter.invoke(null, glFramebuffer, glStencilAttachment, objectType);
            stencilName = (Integer) getAttachmentParameter.invoke(null, glFramebuffer, glStencilAttachment, objectName);
        } catch (Throwable ignored) {
            // LWJGL2（普通 1.12.2）没有 GL30，无法读取附件信息；保留 -1 表示未知
        }

        TACZLegacy.logger.info(
                "[StencilDiag] mainFramebufferStencil={} boundFbo={} stencilBits={} depthAttachmentType={} depthAttachmentName={} stencilAttachmentType={} stencilAttachmentName={}",
                mainFramebufferStencil, boundFbo, stencilBits, depthType, depthName, stencilType, stencilName);
    }

    public static void disableItemEntityStencilTest() {
        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }

    /**
     * 光影（OptiFine gbuffer）下没有模板缓冲时，瞄准镜走降级渲染。
     * 降级路径只有在真的画出分划时才不需要 GUI 准心；没画出来（例如本镜没有分划节点）
     * 就必须把 GUI 准心留着，否则开镜后没有瞄准点。
     */
    private static boolean scopeFallbackThisFrame;

    public static void markScopeFallbackThisFrame() {
        scopeFallbackThisFrame = true;
    }

    public static boolean consumeScopeFallbackThisFrame() {
        boolean value = scopeFallbackThisFrame;
        scopeFallbackThisFrame = false;
        return value;
    }
}
