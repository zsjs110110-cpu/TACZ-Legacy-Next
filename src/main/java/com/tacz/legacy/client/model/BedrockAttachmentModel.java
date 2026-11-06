package com.tacz.legacy.client.model;

import com.tacz.legacy.TACZLegacy;
import com.tacz.legacy.api.entity.IGunOperator;
import com.tacz.legacy.api.item.IAttachment;
import com.tacz.legacy.client.event.FirstPersonRenderGunEvent;
import com.tacz.legacy.client.model.bedrock.BedrockCube;
import com.tacz.legacy.client.model.bedrock.BedrockCubeBox;
import com.tacz.legacy.client.model.bedrock.BedrockCubePerFace;
import com.tacz.legacy.client.model.bedrock.BedrockPart;
import com.tacz.legacy.client.model.bedrock.ModelRendererWrapper;
import com.tacz.legacy.client.model.functional.BeamRenderer;
import com.tacz.legacy.client.model.functional.TextShowRender;
import com.tacz.legacy.client.util.RenderHelper;
import com.tacz.legacy.client.resource.pojo.display.gun.TextShow;
import com.tacz.legacy.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.legacy.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.nio.FloatBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/**
 * Gun-mounted attachment model runtime.
 *
 * This is a minimal Legacy port of upstream BedrockAttachmentModel focused on
 * the runtime data needed for mounted attachment rendering and scope view
 * positioning. Full stencil/optic masking can be layered on top later.
 */
public class BedrockAttachmentModel extends BedrockAnimatedModel {
    private static final String SCOPE_VIEW_NODE = "scope_view";
    private static final String SCOPE_BODY_NODE = "scope_body";
    private static final String OCULAR_RING_NODE = "ocular_ring";
    private static final String DIVISION_NODE = "division";
    private static final String OCULAR_NODE = "ocular";
    private static final String OCULAR_SCOPE_NODE = "ocular_scope";
    private static final String OCULAR_SIGHT_NODE = "ocular_sight";
    private static final Pattern LASER_BEAM_PATTERN = Pattern.compile("^laser_beam(_(\\d+))?$");
    private static final Pattern OCULAR_PATTERN = Pattern.compile("^(" + OCULAR_NODE + "|" + OCULAR_SIGHT_NODE + "|" + OCULAR_SCOPE_NODE + ")(_(\\d+))?$");
    private static final FloatBuffer MODEL_VIEW_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer DEPTH_RANGE_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final Set<String> LOGGED_FOCUSED_SMOKE_OPTIC_KEYS = new HashSet<>();
    private static final java.util.concurrent.atomic.AtomicBoolean OPTIC_PROBE_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean MASK_PROBE_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean CORNER_PROBE_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();

    protected final List<List<BedrockPart>> scopeViewPaths = new ArrayList<>();
    @Nullable
    protected List<BedrockPart> scopeBodyPath;
    @Nullable
    protected List<BedrockPart> ocularRingPath;
    protected final List<List<BedrockPart>> ocularNodePaths = new ArrayList<>();
    protected final List<Boolean> scopeOcularFlags = new ArrayList<>();
    protected final List<List<BedrockPart>> divisionNodePaths = new ArrayList<>();
    protected final List<List<BedrockPart>> laserBeamPaths = new ArrayList<>();
    @Nullable
    private ItemStack currentGunItem;
    @Nullable
    private ItemStack attachmentItem;
    private boolean isScope = false;
    private boolean isSight = false;
    @Nullable
    private net.minecraft.util.ResourceLocation textureId;

    /** cliptest 实验用的平面方程缓冲（4 个 double）。 */
    private static final java.nio.DoubleBuffer CLIP_EQUATION = org.lwjgl.BufferUtils.createDoubleBuffer(4);

    public void setTextureLocation(@Nullable net.minecraft.util.ResourceLocation textureId) {
        this.textureId = textureId;
    }

    public BedrockAttachmentModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
        cacheScopeViewPaths();
        cacheOpticNodePaths();
        cacheLaserBeamPaths();
        hideIndexedNodes(SCOPE_VIEW_NODE);
        hideIndexedNodes(DIVISION_NODE);
        hideIndexedNodes(OCULAR_NODE);
        hideIndexedNodes(OCULAR_SCOPE_NODE);
        hideIndexedNodes(OCULAR_SIGHT_NODE);
    }

    private void cacheScopeViewPaths() {
        List<BedrockPart> path = getPath(modelMap.get(SCOPE_VIEW_NODE));
        int index = 2;
        while (path != null) {
            scopeViewPaths.add(path);
            path = getPath(modelMap.get(SCOPE_VIEW_NODE + '_' + index++));
        }
    }

    private void cacheOpticNodePaths() {
        TreeMap<Integer, OcularWrapper> orderedOculars = new TreeMap<>();
        for (Map.Entry<String, ModelRendererWrapper> entry : modelMap.entrySet()) {
            Matcher ocularMatcher = OCULAR_PATTERN.matcher(entry.getKey());
            if (ocularMatcher.matches()) {
                int index = 1;
                String suffix = ocularMatcher.group(3);
                if (suffix != null) {
                    index = Integer.parseInt(suffix);
                }
                boolean scopeOcular = OCULAR_SCOPE_NODE.equals(ocularMatcher.group(1));
                orderedOculars.put(index, new OcularWrapper(entry.getValue(), scopeOcular));
            }
        }
        for (OcularWrapper wrapper : orderedOculars.values()) {
            List<BedrockPart> path = getPath(wrapper.renderer);
            if (path != null) {
                ocularNodePaths.add(path);
                scopeOcularFlags.add(wrapper.scopeOcular);
            }
        }

        ModelRendererWrapper divisionWrapper = modelMap.get(DIVISION_NODE);
        int divisionIndex = 2;
        while (divisionWrapper != null) {
            List<BedrockPart> path = getPath(divisionWrapper);
            if (path != null) {
                divisionNodePaths.add(path);
            }
            divisionWrapper = modelMap.get(DIVISION_NODE + '_' + divisionIndex++);
        }

        scopeBodyPath = getPath(modelMap.get(SCOPE_BODY_NODE));
        ocularRingPath = getPath(modelMap.get(OCULAR_RING_NODE));
    }

    private void hideIndexedNodes(String baseNodeName) {
        ModelRendererWrapper wrapper = modelMap.get(baseNodeName);
        int index = 2;
        while (wrapper != null) {
            wrapper.setHidden(true);
            wrapper = modelMap.get(baseNodeName + '_' + index++);
        }
    }

    private void cacheLaserBeamPaths() {
        TreeMap<Integer, List<BedrockPart>> orderedPaths = new TreeMap<>();
        for (Map.Entry<String, ModelRendererWrapper> entry : modelMap.entrySet()) {
            Matcher matcher = LASER_BEAM_PATTERN.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            int index = 1;
            String suffix = matcher.group(2);
            if (suffix != null) {
                index = Integer.parseInt(suffix);
            }
            List<BedrockPart> path = getPath(entry.getValue());
            if (path != null) {
                orderedPaths.put(index, path);
            }
        }
        laserBeamPaths.addAll(orderedPaths.values());
    }

    @Nullable
    public List<BedrockPart> getScopeViewPath(int viewSwitchCount) {
        if (scopeViewPaths.isEmpty()) {
            return null;
        }
        if (viewSwitchCount < 0 || viewSwitchCount >= scopeViewPaths.size()) {
            return scopeViewPaths.get(0);
        }
        return scopeViewPaths.get(viewSwitchCount);
    }

    public void setIsScope(boolean scope) {
        isScope = scope;
    }

    public void setIsSight(boolean sight) {
        isSight = sight;
    }

    public boolean isScope() {
        return isScope;
    }

    public boolean isSight() {
        return isSight;
    }

    public void setTextShowList(Map<String, TextShow> textShowList) {
        textShowList.forEach((name, textShow) -> setFunctionalRenderer(name,
            bedrockPart -> new TextShowRender(this, textShow, currentGunItem)));
    }

    public void render(@Nullable ItemStack attachmentItem, @Nullable ItemStack currentGunItem) {
        this.attachmentItem = attachmentItem;
        this.currentGunItem = currentGunItem;

        BeamRenderer.RenderContext renderContext = BeamRenderer.getRenderContext();
        boolean firstPerson = renderContext.isFirstPerson();
        if (!isScope && !isSight) {
            renderLaserBeams();
        }

        if (firstPerson) {
            if (isScope && isSight) {
                renderBoth();
            } else if (isScope) {
                renderScope();
            } else if (isSight) {
                renderSight();
            } else {
                super.render();
            }
        } else {
            if (scopeBodyPath != null) {
                renderTempPart(scopeBodyPath);
            }
            if (ocularRingPath != null) {
                renderTempPart(ocularRingPath);
            }
            super.render();
        }

        if (isScope || isSight) {
            renderLaserBeams();
        }
    }

    public void renderBloom(@Nullable ItemStack attachmentItem, @Nullable ItemStack currentGunItem) {
        this.attachmentItem = attachmentItem;
        this.currentGunItem = currentGunItem;
        super.renderBloom();
    }

    private void renderLaserBeams() {
        if (attachmentItem == null || attachmentItem.isEmpty() || laserBeamPaths.isEmpty()) {
            return;
        }
        for (List<BedrockPart> path : laserBeamPaths) {
            BeamRenderer.renderLaserBeam(attachmentItem, path);
        }
    }

    @Nullable
    public ItemStack getCurrentGunItem() {
        return currentGunItem;
    }

    @Nullable
    public ItemStack getAttachmentItem() {
        return attachmentItem;
    }

    private void renderBoth() {
        if (!RenderHelper.enableItemEntityStencilTest()) {
            renderScopeNoStencil();
            return;
        }
        logFocusedSmokeOpticRender("both");
        try {
            RenderHelper.clearStencilSafely();
            if (ocularRingPath != null) {
                GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                renderTempPart(ocularRingPath);
            }
            renderOcularStencil(true);
            if (scopeBodyPath != null) {
                GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
                renderTempPart(scopeBodyPath);
            }
            renderOcularStencil(false);
            renderOcularAndDivision(true);
        } finally {
            resetStencilState();
            RenderHelper.disableItemEntityStencilTest();
        }
        super.render();
    }

    private void renderSight() {
        if (!RenderHelper.enableItemEntityStencilTest()) {
            if (scopeBodyPath != null) {
                renderTempPart(scopeBodyPath);
            }
            super.render();
            return;
        }
        logFocusedSmokeOpticRender("sight");
        try {
            RenderHelper.clearStencilSafely();
            renderOcularStencil(false);
            renderDivisionOnly();
        } finally {
            resetStencilState();
            RenderHelper.disableItemEntityStencilTest();
        }
        if (scopeBodyPath != null) {
            renderTempPart(scopeBodyPath);
        }
        super.render();
    }

    private void renderScope() {
        if (!RenderHelper.enableItemEntityStencilTest()) {
            renderScopeNoStencil();
            return;
        }
        logRenderPath("scope-stencil");
        logFocusedSmokeOpticRender("scope");
        try {
            RenderHelper.clearStencilSafely();
            if (ocularRingPath != null) {
                GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                renderTempPart(ocularRingPath);
            }
            renderOcularStencil(false);
            if (scopeBodyPath != null) {
                GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
                renderTempPart(scopeBodyPath);
            }
            renderOcularAndDivision(false);
        } finally {
            resetStencilState();
            RenderHelper.disableItemEntityStencilTest();
        }
        super.render();
    }

    /**
     * 光影（OptiFine gbuffer 帧缓冲）下没有模板缓冲时的降级渲染。
     * <p>
     * 无模板缓冲时模板测试恒通过、模板写入被忽略，原路径的镜筒遮罩会整体失效：
     * 内壁糊满屏幕、镜片被挡住。这里跳过镜筒内壁，直接绘制镜片与分划，
     * 最后叠加屏幕对齐的黑色遮罩环，尽量保留「只能透过镜片看到世界」的观感。
     */
    private void renderScopeNoStencil() {
        logRenderPath("scope-fallback");
        // 只读探针：打印当前 FBO 的 depth/stencil 附件，判断能否就地补 stencil（TACZ_PROBE=stencilprobe）
        if (probeEnabled("stencilprobe")) {
            com.tacz.legacy.client.util.StencilProbe.probeOnce();
        }
        // 内壁与目镜环在无模板时会被 super.render 直接画出来挡住镜片，这里一并隐藏
        BedrockPart scopeBody = getLastPart(scopeBodyPath);
        BedrockPart ocularRing = getLastPart(ocularRingPath);
        BedrockPart scopeBack = getNode("back");
        if (OPTIC_PROBE_LOGGED.compareAndSet(false, true)) {
            StringBuilder roots = new StringBuilder();
            for (BedrockPart part : getShouldRender()) {
                roots.append(part.name).append(',');
            }
            System.out.println("[OpticProbe] noStencil path, bodyPath=" + (scopeBodyPath == null ? "null" : scopeBodyPath.size())
                + " body=" + (scopeBody == null ? "null" : scopeBody.name + ":visible=" + scopeBody.visible)
                + " ring=" + (ocularRing == null ? "null" : ocularRing.name + ":visible=" + ocularRing.visible)
                + " roots=" + getShouldRender().size() + " [" + roots + "]"
                + " ocularPaths=" + ocularNodePaths.size() + " divisionPaths=" + divisionNodePaths.size());
            if (scopeBody != null) {
                System.out.println("[OpticProbe] scopeBody cubes=" + scopeBody.cubes.size() + " children=" + scopeBody.children.size()
                    + " visible=" + scopeBody.visible);
                for (BedrockPart child : scopeBody.children) {
                    System.out.println("[OpticProbe] child " + child.name + " visible=" + child.visible
                        + " cubes=" + child.cubes.size() + " children=" + child.children.size());
                }
            }
        }
        boolean previousBodyVisible = scopeBody == null || scopeBody.visible;
        boolean previousRingVisible = ocularRing == null || ocularRing.visible;
        boolean previousBackVisible = scopeBack == null || scopeBack.visible;
        // renderTempPart 结束会把节点 visible 复位成 false（避免模板路径重复绘制），
        // 所以这里不能只「不隐藏」，必须显式强制可见性。
        // 保留镜筒本体与目镜环：二分实验确认挡视野的是 ocular 镜片和 division 填充板，
        // 不是镜筒内壁。
        if (scopeBody != null) {
            scopeBody.visible = true;
        }
        if (ocularRing != null) {
            ocularRing.visible = true;
        }
        if (scopeBack != null && ocularRing != null && resolveCurrentAimingProgress() > 0.01F) {
            scopeBack.visible = false;
        }
        try {
            // 只画分划（贴图自带 alpha），镜孔靠后面的深度戳记透世界。
            // 没画出分划时 GUI 准心必须留着，否则开镜后没有瞄准点。
            if (!renderDivisionNoStencil()) {
                RenderHelper.markScopeFallbackThisFrame();
            }
            // 深度戳记：把镜孔圆盘区域内写成最近深度，随后渲染的镜筒内壁会被深度测试挡掉，
            // 从而让「透明孔」恢复成模板路径下那个圆形大小，而不是镜筒几何留下的八边形。
            if (!probeSkip("stamp")) {
                float apertureRadius = resolveApertureRadius();
                if (apertureRadius > 0.01f) {
                    if (System.currentTimeMillis() - apertureProbeLast > 1000L) {
                        apertureProbeLast = System.currentTimeMillis();
                        System.out.println("[OpticProbe] aperture radius=" + apertureRadius
                            + " fov=" + resolveCurrentProjectionFov()
                            + " progress=" + resolveCurrentAimingProgress());
                    }
                    // 圆盘中心用固定的 (0, 2.0)：实测按 ocular 节点中心取值会让整块圆盘偏移，
                    // 目镜环反而从更多方向压进镜孔（缺口从 1 处变成 4~5 处）。
                    // 开镜时枪模型贴脸，镜孔在根空间里本来就落在原点附近。
                    float apertureCenterX = 0.0f;
                    float apertureCenterY = 2.0f;
                    stampLensDepth(apertureCenterX, apertureCenterY, -90.0f, apertureRadius, 0.0f, probeEnabled("stampdebug"));
                    try {
                        super.render();
                    } finally {
                        stampLensDepth(apertureCenterX, apertureCenterY, -90.0f, apertureRadius, 1.0f, false);
                    }
                } else {
                    super.render();
                }
            } else {
                super.render();
            }
            // 方形主板四角的圆环修复：默认开启（原来是 maskon 探针门控，等于没生效），
            // TACZ_PROBE=maskoff 可以关掉做 A/B。
            renderDivisionCornerMask();
            if (probeEnabled("ocularon")) {
                renderOcularNoStencil();
            }
        } finally {
            if (scopeBody != null) {
                scopeBody.visible = previousBodyVisible;
            }
            if (ocularRing != null) {
                ocularRing.visible = previousRingVisible;
            }
            if (scopeBack != null) {
                scopeBack.visible = previousBackVisible;
            }
        }
    }

    /** 投影矩阵读数缓冲（算眼盒投影尺寸用）。 */
    private static final java.nio.FloatBuffer PROJECTION_BUFFER = BufferUtils.createFloatBuffer(16);

    /**
     * 眼盒圆盘半径（圆盘自己那套坐标，约等于 480 高画布上的像素）。
     *
     * <p>不再写死 80：把目镜节点的立方体 8 个角投到屏幕，取离投影中心最远的角作为眼盒半径，
     * 再按当前视口高度折算回 480 画布的像素口径。这样遮罩跟着每个镜型真正的镜片开口走，
     * 也自动跟着 fov 与渲染分辨率变；80 是当初只在 ACOG 上试出来的常数，对镜片更大的镜型
     * 就会剩下一圈镜片色。
     *
     * <p>拿不到几何、或算出来的值明显离谱时退回旧常数，保证不会把某个镜型的遮罩整个丢掉。
     */
    private float resolveEyeBoxRadius(@Nullable List<BedrockPart> ocularPath) {
        float legacy = 80.0f * resolveCurrentAimingProgress();
        if (!Boolean.parseBoolean(System.getProperty("tacz.scope.geoRadius", "true"))) {
            return legacy;
        }
        float geo = projectOcularRadiusPx(ocularPath) * (480.0f / readViewportHeight());
        if (!(geo > 0.5f)) {
            // 拿不到几何（没有立方体/矩阵异常），退回常数
            if (probeEnabled("radiusdbg")) {
                logRadius(legacy, geo, "no-geometry");
            }
            return legacy;
        }
        // 开镜动画里镜片是斜着扫过画面的，投影圆会一下子比真正的镜孔大很多；
        // 用 legacy 的 4 倍封顶，让遮罩仍然随 progress 长大，避免半程突然撑满。
        float radius = Math.min(geo, legacy * 4.0f);
        if (probeEnabled("radiusdbg")) {
            logRadius(legacy, geo, radius == geo ? "use-geo" : "clamped-by-progress");
        }
        return radius;
    }

    private static volatile long radiusProbeLast = 0L;

    private void logRadius(float legacy, float geo, String decision) {
        long now = System.currentTimeMillis();
        if (now - radiusProbeLast < 1000L) {
            return;
        }
        radiusProbeLast = now;
        System.out.println("[RadiusProbe] legacy=" + legacy + " geo=" + geo
            + " vpH=" + readViewportHeight() + " fov=" + resolveCurrentProjectionFov()
            + " progress=" + resolveCurrentAimingProgress() + " -> " + decision);
    }

    private static int readViewportHeight() {
        try {
            java.nio.IntBuffer vp = BufferUtils.createIntBuffer(4);
            GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
            if (vp.get(3) > 0) {
                return vp.get(3);
            }
        } catch (Throwable ignored) {
        }
        return 480;
    }

    private static int readViewportWidth() {
        try {
            java.nio.IntBuffer vp = BufferUtils.createIntBuffer(4);
            GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
            if (vp.get(2) > 0) {
                return vp.get(2);
            }
        } catch (Throwable ignored) {
        }
        return 854;
    }

    /**
     * 把目镜立方体的角投到屏幕，返回离中心最远的角的像素距离；拿不到几何返回 -1。
     *
     * <p>矩阵读法和 {@link #getBedrockPartCenter} 保持一致：先沿路径逐层
     * {@code translateAndRotateAndScale()}（含目镜节点本身），再读 GL_MODELVIEW_MATRIX，
     * 立方体顶点用局部坐标 /16（与 {@code BedrockCubeBox.compile} 同一套）。
     */
    private float projectOcularRadiusPx(@Nullable List<BedrockPart> ocularPath) {
        if (ocularPath == null || ocularPath.isEmpty()) {
            return -1.0f;
        }
        BedrockPart ocular = ocularPath.get(ocularPath.size() - 1);
        java.util.List<com.tacz.legacy.client.model.bedrock.BedrockCube> cubes = collectCubes(ocular);
        if (cubes.isEmpty()) {
            return -1.0f;
        }
        GlStateManager.pushMatrix();
        try {
            for (BedrockPart part : ocularPath) {
                part.translateAndRotateAndScale();
            }
            MODEL_VIEW_BUFFER.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODEL_VIEW_BUFFER);
            MODEL_VIEW_BUFFER.rewind();
            PROJECTION_BUFFER.clear();
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION_BUFFER);
            PROJECTION_BUFFER.rewind();
            org.joml.Matrix4f clip = new org.joml.Matrix4f().set(PROJECTION_BUFFER)
                    .mul(new org.joml.Matrix4f().set(MODEL_VIEW_BUFFER));
            org.joml.Vector4f center = clip.transform(new org.joml.Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
            if (Math.abs(center.w) < 1.0e-4f) {
                return -1.0f;
            }
            float cx = center.x / center.w;
            float cy = center.y / center.w;
            float halfW = readViewportWidth() * 0.5f;
            float halfH = readViewportHeight() * 0.5f;
            float best = -1.0f;
            for (com.tacz.legacy.client.model.bedrock.BedrockCube cube : cubes) {
                float[] box = cubeBounds(cube);
                if (box == null) {
                    continue;
                }
                for (int i = 0; i < 8; i++) {
                    float lx = ((i & 1) == 0 ? box[0] : box[3]) / 16.0f;
                    float ly = ((i & 2) == 0 ? box[1] : box[4]) / 16.0f;
                    float lz = ((i & 4) == 0 ? box[2] : box[5]) / 16.0f;
                    org.joml.Vector4f p = clip.transform(new org.joml.Vector4f(lx, ly, lz, 1.0f));
                    if (Math.abs(p.w) < 1.0e-4f) {
                        continue;
                    }
                    float dx = (p.x / p.w - cx) * halfW;
                    float dy = (p.y / p.w - cy) * halfH;
                    best = Math.max(best, (float) Math.hypot(dx, dy));
                }
            }
            return best;
        } catch (Throwable throwable) {
            return -1.0f;
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** 目镜节点自身的立方体优先；空了就往下找子节点（有些镜型把镜片挂在子节点上）。 */
    private static java.util.List<com.tacz.legacy.client.model.bedrock.BedrockCube> collectCubes(BedrockPart part) {
        java.util.List<com.tacz.legacy.client.model.bedrock.BedrockCube> cubes =
                new java.util.ArrayList<>(part.cubes);
        if (cubes.isEmpty()) {
            for (BedrockPart child : part.children) {
                cubes.addAll(collectCubes(child));
            }
        }
        return cubes;
    }

    /** 无模板降级时的镜孔半径（根空间单位），与模板路径 renderOcularAndDivision 的圆盘同源。 */
    private float resolveApertureRadius() {
        float progress = resolveCurrentAimingProgress();
        if (progress <= 0.01f) {
            return 0.0f;
        }
        // 模板路径在 z=-90 平面上画半径 80px 的圆盘（按 854x480 画布计）。
        // 换算成该平面的模型单位需要当前垂直 fov：halfH = 90*tan(fov/2)，unitsPerPx = halfH / 240。
        // 之前这里写死 0.101（相当于把 fov 当成 30°），但实测开镜后 fov 并不是 30
        // （HK416 SD 的 zoom_model_fov=60，叠加 iron_zoom 也只是到 40 上下），
        // 于是圆盘比真正的镜孔小 2~3 倍，深度戳记只挡住中央一小块，镜孔就留成了八边形。
        // fov 不能靠猜 —— 直接从投影矩阵反算。
        float fovDegrees = resolveCurrentProjectionFov();
        float halfHeight = 90.0f * (float) Math.tan(Math.toRadians(fovDegrees * 0.5f));
        float unitsPerPixel = halfHeight / 240.0f;
        // 半径按模板路径的 80px 口径取值：这是唯一与「无光影（stencil 模板路径）」
        // 实拍镜孔尺寸对得上的值。调大到 86/92/100 实测会让镜孔变模糊甚至整片消失，
        // 边缘仅剩的一小块缺口属于镜筒几何本身，留给后续用几何或 FBO 方案解决。
        return 80.0f * progress * unitsPerPixel;
    }

    /**
     * 从当前投影矩阵反算垂直 fov（度）。
     *
     * <p>透视投影矩阵的 m[5]（列主序第 2 行第 2 列）= 1 / tan(fov/2)，所以
     * {@code fov = 2 * atan(1 / m11)}。这样拿到的就是这一帧真正在用的 fov，
     * 不需要去追 TaCZ 的 zoom 与 OptiFine 的 fov 修改叠加了几层。
     */
    private static float resolveCurrentProjectionFov() {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        buffer.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, buffer);
        buffer.rewind();
        float m11 = buffer.get(5);
        if (!(m11 > 1.0e-4f) || !Float.isFinite(m11)) {
            return 70.0f;
        }
        return (float) Math.toDegrees(2.0 * Math.atan(1.0 / m11));
    }

    private static final java.util.concurrent.atomic.AtomicBoolean STAMP_PROBE_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();
    /** 镜孔半径/当前 fov 的诊断输出节流（每秒最多一条，开镜后才打印）。 */
    private static volatile long apertureProbeLast = 0L;

    /**
     * 用深度缓冲冒充模板：在镜孔圆盘内把深度写成 depthValue（0 = 最近，1 = 最远），
     * 不写颜色。之后渲染的镜筒内壁在镜孔区域内深度测试失败，不会遮挡世界。
     */
    private void stampLensDepth(float cx, float cy, float z, float radius, float depthValue, boolean debug) {
        net.minecraft.util.ResourceLocation maskTexture = ensureScopeMaskTexture();
        if (maskTexture == null) {
            return;
        }
        FloatBuffer range = DEPTH_RANGE_BUFFER;
        range.clear();
        GL11.glGetFloat(GL11.GL_DEPTH_RANGE, range);
        range.rewind();
        float oldNear = range.get(0);
        float oldFar = range.get(1);
        int oldFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean oldDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        // 这段几何要绑一张纯黑遮罩纹理、还要改 cull/blend/depth。以前只恢复了深度那几项，
        // 纹理绑定一漏，紧随其后的 super.render() 就用这张 16x16 纯黑图把整个镜身画黑了
        // （和圆盘那两处同一个 bug 类，那两处已修，这里当时没跟上）。
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean oldCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean oldBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean oldTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);

        Minecraft.getMinecraft().getTextureManager().bindTexture(maskTexture);
        if (debug && STAMP_PROBE_LOGGED.compareAndSet(false, true)) {
            System.out.println("[OpticProbe] stamp cx=" + cx + " cy=" + cy + " z=" + z + " radius=" + radius
                + " depth=" + depthValue + " progress=" + resolveCurrentAimingProgress());
        }
        GlStateManager.enableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        // 调试开关：打开颜色写入，用黑圆盘直接看位置和大小
        GlStateManager.colorMask(debug, debug, debug, debug);
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(GL11.GL_ALWAYS);
        GL11.glDepthRange(depthValue, depthValue);
        try {
            // 用立即模式而不是 Tessellator：光影管线会按顶点格式重排 gbuffers_hand 的批次，
            // POSITION_TEX_NORMAL 的圆盘有一整块三角形被丢弃，镜孔边缘就出现直边缺口。
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glTexCoord2f(0.5F, 0.5F);
            GL11.glVertex3f(cx, cy, z);
            for (int i = 0; i <= 64; i++) {
                double angle = i * (Math.PI * 2.0 / 64.0);
                GL11.glTexCoord2f(0.5F, 0.5F);
                GL11.glVertex3f(cx + radius * (float) Math.cos(angle),
                    cy + radius * (float) Math.sin(angle), z);
            }
            GL11.glEnd();
        } finally {
            GL11.glDepthRange(oldNear, oldFar);
            GlStateManager.depthFunc(oldFunc);
            GlStateManager.depthMask(oldDepthMask);
            GlStateManager.colorMask(true, true, true, true);
            // 开关状态用 GlStateManager 恢复（改动也是它做的，缓存和硬件才对得上）
            if (oldCull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            if (oldBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (oldTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            // 纹理绑定用裸 GL 读写两端对称，不会引入新的缓存偏差
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
        }
    }

    @Nullable
    private static BedrockPart getLastPart(@Nullable List<BedrockPart> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return path.get(path.size() - 1);
    }

    private static boolean probeSkip(String name) {
        return probeContains(name);
    }

    private static boolean probeEnabled(String name) {
        return probeContains(name);
    }

    /** 按逗号精确匹配探针名，避免 "stampdebug" 误命中 "stamp"。 */
    private static boolean probeContains(String name) {
        String probe = System.getenv("TACZ_PROBE");
        if (probe == null) {
            return false;
        }
        for (String token : probe.split(",")) {
            if (token.trim().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 渲染分支探针：每秒一条，明确当前帧走的是模板路径还是无模板降级路径。 */
    private static volatile long pathProbeLast = 0L;

    private static void logRenderPath(String path) {
        if (!probeEnabled("pathprobe")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - pathProbeLast < 1000L) {
            return;
        }
        pathProbeLast = now;
        System.out.println("[PathProbe] " + path);
    }




    /** 可视模板图探针用的纯色不透明纹理（按颜色缓存），避免用顶点色在光影下取到未定义属性。 */
    private static final Map<Integer, net.minecraft.util.ResourceLocation> TINT_MASK_TEXTURES = new java.util.HashMap<>();

    private static net.minecraft.util.ResourceLocation ensureTintMaskTexture(int rgb) {
        Integer key = rgb;
        if (TINT_MASK_TEXTURES.containsKey(key)) {
            return TINT_MASK_TEXTURES.get(key);
        }
        net.minecraft.util.ResourceLocation result = null;
        try {
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int argb = 0xFF000000 | rgb;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    image.setRGB(x, y, argb);
                }
            }
            net.minecraft.client.renderer.texture.DynamicTexture texture =
                new net.minecraft.client.renderer.texture.DynamicTexture(image);
            result = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("tacz_stencil_vis", texture);
        } catch (Throwable throwable) {
            TACZLegacy.logger.warn("[StencilVis] tint texture failed", throwable);
        }
        TINT_MASK_TEXTURES.put(key, result);
        return result;
    }

    /**
     * 可视模板图：把某个模板值的像素整片涂成一种颜色，截图本身就是模板缓冲的读数。
     * 用带 texcoord 的几何 + 不透明纯色纹理，避免重蹈圆盘「属性未定义 + 包 discard」的覆辙。
     */
    private void drawStencilVisQuad(BufferBuilder buffer, Tessellator tessellator, int stencilRef, int rgb) {
        net.minecraft.util.ResourceLocation tint = ensureTintMaskTexture(rgb);
        if (tint == null) {
            return;
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(tint);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.0F);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, stencilRef, 0xFF);
        net.minecraft.client.renderer.OpenGlHelper.setLightmapTextureCoords(
            net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        // 覆盖整屏的大方块（z=-90 平面上 3000 单位 >> 半屏 90*tan(fov/2)）
        float half = 3000.0f;
        buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buffer.pos(-half, -half, -90.0D).tex(0.5D, 0.5D).normal(0.0F, 0.0F, -1.0F).endVertex();
        buffer.pos(half, -half, -90.0D).tex(0.5D, 0.5D).normal(0.0F, 0.0F, -1.0F).endVertex();
        buffer.pos(half, half, -90.0D).tex(0.5D, 0.5D).normal(0.0F, 0.0F, -1.0F).endVertex();
        buffer.pos(-half, half, -90.0D).tex(0.5D, 0.5D).normal(0.0F, 0.0F, -1.0F).endVertex();
        tessellator.draw();
        // 恢复：后面还要画别的，状态必须回到 vanilla 手部 pass 的约定
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glStencilMask(0xFF);
    }

    /** 可视模板图配色：红=ocular 遮罩 ref1，绿=ref2，黄=254（~1 反转），青=253（~2 反转），品红=0（从没被写过）。 */
    private void renderStencilVisualization(BufferBuilder buffer, Tessellator tessellator) {
        int[][] refs = {{1, 0xFF0000}, {2, 0x00FF00}, {254, 0xFFFF00}, {253, 0x00FFFF}, {0, 0xFF00FF}};
        for (int[] entry : refs) {
            drawStencilVisQuad(buffer, tessellator, entry[0], entry[1]);
        }
        System.out.println("[StencilVis] drawn refs=1,2,254,253,0 (red,green,yellow,cyan,magenta)");
    }

    /**
     * TACZ_PROBE=divtrace：同帧多点回读屏幕中央区域，直接定位「是谁把黑色画上去的」。
     * 不用投影定位采样点——光影下固定管线矩阵栈不可靠；RGB 回读在本上下文可用（STENCIL_INDEX 被拒）。
     */
    /**
     * TACZ_PROBE=divcopy：把分划原贴图**一字不改**地重传成 RGBA DynamicTexture 再绑上。
     * 与 divkey 的唯一区别是「不做抠黑」——如果 BSL 里没有 discard 而 divkey 仍然有效，
     * 那真正起作用的就不是抠黑而是这次上传保住了 alpha，本开关就是用来判这一点的。
     */







    private static volatile long oculistProbeLast = 0L;

    /** 只读探针：目镜与分划的索引配对，以及每个目镜遮罩在根空间里的位置。 */
    private void logOcularPairing(Tessellator tessellator) {
        long now = System.currentTimeMillis();
        if (now - oculistProbeLast < 1000L) {
            return;
        }
        oculistProbeLast = now;
        StringBuilder sb = new StringBuilder("[Oculist] oculars=").append(ocularNodePaths.size())
            .append(" divisions=").append(divisionNodePaths.size())
            .append(" 配对轮数=").append(Math.min(ocularNodePaths.size(), divisionNodePaths.size())).append(" |");
        for (int i = 0; i < ocularNodePaths.size(); i++) {
            List<BedrockPart> path = ocularNodePaths.get(i);
            Vector3f c = getBedrockPartCenter(path);
            sb.append(" [i=").append(i).append(" ref=").append(i + 1)
                .append(" scopeOcular=").append(scopeOcularFlags.get(i))
                .append(" node=").append(path.get(path.size() - 1).name)
                .append(" c=(").append(String.format("%.2f,%.2f,%.2f", c.x(), c.y(), c.z())).append(")]");
        }
        for (int i = 0; i < divisionNodePaths.size(); i++) {
            List<BedrockPart> path = divisionNodePaths.get(i);
            sb.append(" div").append(i).append('=').append(path.get(path.size() - 1).name);
        }
        System.out.println(sb.toString());
    }



    private void renderOcularNoStencil() {
        if (probeSkip("ocular")) {
            return;
        }
        // 镜片不写深度，避免挡住随后绘制的分划
        GlStateManager.depthMask(false);
        try {
            for (List<BedrockPart> path : ocularNodePaths) {
                renderTempPart(path);
            }
        } finally {
            GlStateManager.depthMask(true);
        }
    }

    /** @return 这一帧是否真的画出了分划（决定要不要保留 GUI 准心）。 */
    private boolean renderDivisionNoStencil() {
        // 降级路径没有模板，分划是不加遮罩整张贴上去的；腰射（没在透过镜片看）时画出来就是
        // 一坨悬在屏幕上的准心（用户实测症状）。只在真正开镜时画。
        if (probeSkip("division") || divisionNodePaths.isEmpty() || resolveCurrentAimingProgress() < 0.05F) {
            return false;
        }
        // 分划与镜片几乎共面，略微前移以避免深度冲突
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1.0f, -1.0f);
        // 分划贴图自带正确的 alpha：8x 的 64x64 里 3082 个像素是全透（背景）、890 个是不透明暗像素
        // ——那 890 个就是密位刻线本身。之前「按亮度抠黑」等于把刻线整条删掉，降级路径才只剩中心红点。
        // 这里绑回原图。过滤方式不动：那是共享纹理对象的状态，改了会影响整个镜身贴图。
        net.minecraft.util.ResourceLocation origin = textureId == null ? null
            : com.tacz.legacy.client.resource.TACZClientAssetManager.INSTANCE.getTextureLocation(textureId);
        int texBefore = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (origin != null) {
            Minecraft.getMinecraft().getTextureManager().bindTexture(origin);
        }
        try {
            // 实验（TACZ_PROBE=cliptest）：用 4 个裁剪平面把分划裁成正方形，验证
            // glClipPlane 在 OptiFine 光影管线下是否仍然生效。生效则走「几何裁剪」方案，
            // 被忽略就只能上 FBO。
            boolean clipTest = probeEnabled("cliptest");
            if (clipTest) {
                enableClipBox(0.1f);   // 实验结论：光影下 glClipPlane 被忽略，此路不通
            }
            try {
                // 背景在贴图里本来就是全透明的，所以整个 division 直接画即可，
                // 不需要按质心过滤 cube（该规则对 Elcan 这类主板不在质心的镜型会误判，导致分划丢失）。
                for (List<BedrockPart> path : divisionNodePaths) {
                    renderTempPart(path);
                }
            } finally {
                if (clipTest) {
                    disableClipBox();
                }
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texBefore);
            GlStateManager.enableTexture2D();
            GlStateManager.disablePolygonOffset();
        }
        return true;
    }

    /**
     * 实验用：用 4 个眼睛空间裁剪平面围成以相机正前方为中心的方框。
     * 目的是验证 glClipPlane 在 OptiFine 光影管线下是否仍然生效：
     * 生效则走「几何裁剪」通用方案，被忽略就只能上 FBO。
     */
    private static void enableClipBox(float halfSize) {
        double[][] planes = {
                {1.0, 0.0, 0.0, halfSize},
                {-1.0, 0.0, 0.0, halfSize},
                {0.0, 1.0, 0.0, halfSize},
                {0.0, -1.0, 0.0, halfSize},
        };
        for (int i = 0; i < planes.length; i++) {
            CLIP_EQUATION.clear();
            CLIP_EQUATION.put(planes[i]).flip();
            GL11.glEnable(GL11.GL_CLIP_PLANE0 + i);
            GL11.glClipPlane(GL11.GL_CLIP_PLANE0 + i, CLIP_EQUATION);
        }
    }

    private static void disableClipBox() {
        for (int i = 0; i < 4; i++) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0 + i);
        }
    }
    /**
     * 在分划主板的同一局部坐标系里画一个黑色圆环，盖住方形主板的四个角。
     * <p>
     * 无模板时只能看到主板的方形足迹（圆孔之外、方形边角之内是贴图里的黑色），
     * 看起来是一个方框；这里用同矩阵的圆环把方框外缘修成圆形，
     * 内径对齐主板里的透明圆孔，外径略大于主板对角线的一半。
     */
    private void renderDivisionCornerMask() {
        // 和分划同门槛：腰射时分划不画，这个黑色圆环也不能单独浮在屏幕上
        if (probeSkip("maskoff") || divisionNodePaths.isEmpty() || resolveCurrentAimingProgress() < 0.05F) {
            return;
        }
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        // OptiFine 的 gbuffers_hand 会按纹理采样，纯色 POSITION_COLOR 几何没有纹理坐标会被丢弃，
        // 这里绑定一张动态生成的不透明纯黑纹理，用 POSITION_TEX_NORMAL 绘制圆环。
        ResourceLocation maskTexture = ensureScopeMaskTexture();
        if (maskTexture == null) {
            return;
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(maskTexture);
        // 转正之前必须把借走的状态全部还回去：纹理绑定、光照开关、lightmap 坐标。
        // 之前这段只在探针下跑，泄漏到下一个渲染者身上也没人看见。
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean oldLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        float oldBrightnessX = net.minecraft.client.renderer.OpenGlHelper.lastBrightnessX;
        float oldBrightnessY = net.minecraft.client.renderer.OpenGlHelper.lastBrightnessY;
        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.disableBlend();
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        net.minecraft.client.renderer.OpenGlHelper.setLightmapTextureCoords(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        try {
            for (List<BedrockPart> path : divisionNodePaths) {
                float[] bounds = divisionLensBounds(path);
                if (bounds == null) {
                    continue;
                }
                GlStateManager.pushMatrix();
                try {
                    for (BedrockPart part : path) {
                        part.translateAndRotateAndScale();
                    }
                    float centerX = (bounds[0] + bounds[3]) * 0.5f;
                    float centerY = (bounds[1] + bounds[4]) * 0.5f;
                    float halfWidth = (bounds[3] - bounds[0]) * 0.5f;
                    float halfHeight = (bounds[4] - bounds[1]) * 0.5f;
                    float half = Math.max(halfWidth, halfHeight);
                    float inner = half * 0.82f;
                    float outer = half * 1.45f;
                    float z = bounds[2] - 0.05f;
                    if (CORNER_PROBE_LOGGED.compareAndSet(false, true)) {
                        System.out.println("[OpticProbe] cornerMask center=(" + centerX + "," + centerY + ") half=" + half
                            + " inner=" + inner + " outer=" + outer + " z=" + z + " zRange=" + bounds[2] + ".." + bounds[5]);
                    }
                    buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_TEX_NORMAL);
                    for (int i = 0; i <= 90; i++) {
                        float angle = (float) (i * Math.PI * 2.0 / 90.0);
                        float sin = MathHelper.sin(angle);
                        float cos = MathHelper.cos(angle);
                        buffer.pos(centerX + cos * inner, centerY + sin * inner, z).tex(0.5D, 0.5D).normal(0.0F, 0.0F, 1.0F).endVertex();
                        buffer.pos(centerX + cos * outer, centerY + sin * outer, z).tex(0.5D, 0.5D).normal(0.0F, 0.0F, 1.0F).endVertex();
                    }
                    tessellator.draw();
                } finally {
                    GlStateManager.popMatrix();
                }
            }
        } finally {
            GlStateManager.enableCull();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enableTexture2D();
            if (oldLighting) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
            net.minecraft.client.renderer.OpenGlHelper.setLightmapTextureCoords(
                    net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit, oldBrightnessX, oldBrightnessY);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
        }
    }

    @Nullable
    private static ResourceLocation maskTextureLocation;

    /**
     * 生成并缓存一张 16x16 不透明纯黑动态纹理，供无模板降级路径的遮罩圆环采样。
     * OptiFine 的 gbuffers_hand 会按纹理采样，纯色顶点格式在光影下会被丢弃。
     */
    @Nullable
    private static ResourceLocation ensureScopeMaskTexture() {
        if (maskTextureLocation != null) {
            return maskTextureLocation;
        }
        try {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    image.setRGB(x, y, 0xFF000000);
                }
            }
            net.minecraft.client.renderer.texture.DynamicTexture texture = new net.minecraft.client.renderer.texture.DynamicTexture(image);
            maskTextureLocation = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("tacz_scope_mask", texture);
            return maskTextureLocation;
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Nullable
    private static float[] divisionLensBounds(List<BedrockPart> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        BedrockPart part = path.get(path.size() - 1);
        if (part.cubes.size() <= 4) {
            return null;
        }
        float[][] bounds = new float[part.cubes.size()][];
        double sumX = 0;
        double sumY = 0;
        for (int i = 0; i < part.cubes.size(); i++) {
            bounds[i] = cubeBounds(part.cubes.get(i));
            if (bounds[i] == null) {
                return null;
            }
            sumX += (bounds[i][0] + bounds[i][3]) * 0.5;
            sumY += (bounds[i][1] + bounds[i][4]) * 0.5;
        }
        float centroidX = (float) (sumX / part.cubes.size());
        float centroidY = (float) (sumY / part.cubes.size());
        for (float[] b : bounds) {
            if (b[0] <= centroidX && centroidX <= b[3] && b[1] <= centroidY && centroidY <= b[4]) {
                return b;
            }
        }
        return null;
    }


    @Nullable
    private static float[] cubeBounds(BedrockCube cube) {
        if (cube instanceof BedrockCubePerFace) {
            BedrockCubePerFace face = (BedrockCubePerFace) cube;
            return new float[]{
                Math.min(face.minX, face.maxX), Math.min(face.minY, face.maxY), Math.min(face.minZ, face.maxZ),
                Math.max(face.minX, face.maxX), Math.max(face.minY, face.maxY), Math.max(face.minZ, face.maxZ)};
        }
        if (cube instanceof BedrockCubeBox) {
            BedrockCubeBox box = (BedrockCubeBox) cube;
            return new float[]{
                Math.min(box.minX, box.maxX), Math.min(box.minY, box.maxY), Math.min(box.minZ, box.maxZ),
                Math.max(box.minX, box.maxX), Math.max(box.minY, box.maxY), Math.max(box.minZ, box.maxZ)};
        }
        return null;
    }


    private void resetStencilState() {
        GL11.glStencilMask(0xFF);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
    }

    private void renderOcularStencil(boolean scopeOcular) {
        if (ocularNodePaths.isEmpty()) {
            return;
        }
        GlStateManager.colorMask(false, false, false, false);
        GlStateManager.depthMask(false);
        GL11.glStencilMask(0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        for (int i = ocularNodePaths.size() - 1; i >= 0; i--) {
            if (scopeOcular == scopeOcularFlags.get(i)) {
                GL11.glStencilFunc(GL11.GL_GREATER, i + 1, 0xFF);
                renderTempPart(ocularNodePaths.get(i));
            }
        }
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(true, true, true, true);
    }

    private void renderDivisionOnly() {
        if (divisionNodePaths.isEmpty()) {
            return;
        }
        GlStateManager.disableDepth();
        try {
            for (int i = 0; i < divisionNodePaths.size(); i++) {
                GL11.glStencilFunc(GL11.GL_EQUAL, i + 1, 0xFF);
                renderTempPart(divisionNodePaths.get(i));
            }
        } finally {
            GlStateManager.enableDepth();
        }
    }

    private void renderOcularAndDivision(boolean selective) {
        if (ocularNodePaths.isEmpty()) {
            return;
        }
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INVERT);
        GlStateManager.colorMask(false, false, false, false);
        GlStateManager.depthMask(false);
        // 眼盒圆盘必须带合法 texcoord 并绑一张不透明纹理：光影包的 gbuffers program 无条件采样，
        // 纯 POSITION_COLOR 几何取到未定义 UV 后会被按 alpha 丢弃，模板反转整片不发生
        // （实测症状：镜内全黑，或从圆心发散的放射状噪点）。
        net.minecraft.util.ResourceLocation opaqueMask = ensureScopeMaskTexture();
        // 画完必须把纹理绑定还回去，否则后面的 ocular/division 会一直采样这张 16x16 纯黑遮罩，
        // 整个镜孔被画黑（本轮「镜内全黑」的真正原因，与光影包的 discard 无关）。
        int texBeforeDisc = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (opaqueMask != null) {
            Minecraft.getMinecraft().getTextureManager().bindTexture(opaqueMask);
        }
        GlStateManager.enableTexture2D();

        for (int i = 0; i < ocularNodePaths.size(); i++) {
            if (selective && !scopeOcularFlags.get(i)) {
                continue;
            }
            GL11.glStencilFunc(GL11.GL_EQUAL, i + 1, 0xFF);
            Vector3f ocularCenter = getBedrockPartCenter(ocularNodePaths.get(i));
            float centerX = ocularCenter.x() * 16.0f * 90.0f;
            float centerY = ocularCenter.y() * 16.0f * 90.0f;
            // 半径按这个镜型自己的镜片开口推导（见 resolveEyeBoxRadius），不再用写死的 80
            float radius = resolveEyeBoxRadius(ocularNodePaths.get(i));
            if (!(radius > 0.01f)) {
                continue;
            }
            buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_TEX_NORMAL);
            buffer.pos(centerX, centerY, -90.0D).tex(0.5D, 0.5D).normal(0.0F, 0.0F, -1.0F).endVertex();
            for (int j = 0; j <= 90; j++) {
                float angle = j * ((float) Math.PI * 2.0F) / 90.0F;
                float sin = MathHelper.sin(angle);
                float cos = MathHelper.cos(angle);
                buffer.pos(centerX + cos * radius, centerY + sin * radius, -90.0D)
                    .tex(0.5D, 0.5D).normal(0.0F, 0.0F, -1.0F).endVertex();
            }
            tessellator.draw();
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texBeforeDisc);
        GlStateManager.enableTexture2D();

        GlStateManager.depthMask(true);
        GlStateManager.colorMask(true, true, true, true);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        // TACZ_PROBE=oculist：打印目镜/分划的索引配对，判「8x 丢分划是不是配对错位」
        if (probeEnabled("oculist")) {
            logOcularPairing(tessellator);
        }

        for (int i = 0; i < ocularNodePaths.size() && i < divisionNodePaths.size(); i++) {
            if (selective && !scopeOcularFlags.get(i)) {
                GL11.glStencilFunc(GL11.GL_EQUAL, i + 1, 0xFF);
                renderTempPart(divisionNodePaths.get(i));
                continue;
            }
            GL11.glStencilFunc(GL11.GL_EQUAL, i + 1, 0xFF);
            renderTempPart(ocularNodePaths.get(i));
            int invertedMask = ~(i + 1) & 0xFF;
            GL11.glStencilFunc(GL11.GL_EQUAL, invertedMask, 0xFF);
            renderTempPart(divisionNodePaths.get(i));
        }

        // TACZ_PROBE=stencilvis：把模板缓冲涂成颜色画出来，截图即读数
        if (probeEnabled("stencilvis")) {
            renderStencilVisualization(buffer, tessellator);
        }
    }

    private Vector3f getBedrockPartCenter(List<BedrockPart> path) {
        GlStateManager.pushMatrix();
        try {
            for (BedrockPart part : path) {
                part.translateAndRotateAndScale();
            }
            MODEL_VIEW_BUFFER.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODEL_VIEW_BUFFER);
            MODEL_VIEW_BUFFER.rewind();
            Matrix4f matrix = new Matrix4f().set(MODEL_VIEW_BUFFER);
            return new Vector3f(matrix.m30(), matrix.m31(), matrix.m32());
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void renderTempPart(List<BedrockPart> path) {
        if (path.isEmpty()) {
            return;
        }
        GlStateManager.pushMatrix();
        try {
            for (int i = 0; i < path.size() - 1; i++) {
                path.get(i).translateAndRotateAndScale();
            }
            BedrockPart part = path.get(path.size() - 1);
            part.visible = true;
            part.render();
            part.visible = false;
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private float resolveCurrentAimingProgress() {
        Float preparedAimingProgress = FirstPersonRenderGunEvent.getPreparedAimingProgress();
        if (preparedAimingProgress != null) {
            return MathHelper.clamp(preparedAimingProgress, 0.0f, 1.0f);
        }
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null) {
            return 0.0f;
        }
        return MathHelper.clamp(IGunOperator.fromLivingEntity(player).getSynAimingProgress(), 0.0f, 1.0f);
    }

    private void logFocusedSmokeOpticRender(String mode) {
        if (!Boolean.parseBoolean(System.getProperty("tacz.focusedSmoke", "false"))) {
            return;
        }
        String attachmentId = resolveAttachmentDebugId();
        String key = attachmentId + '|' + mode;
        if (!LOGGED_FOCUSED_SMOKE_OPTIC_KEYS.add(key)) {
            return;
        }
        TACZLegacy.logger.info(
                "[FocusedSmoke] OPTIC_STENCIL_RENDERED attachment={} mode={} oculars={} divisions={}",
                attachmentId,
                mode,
                ocularNodePaths.size(),
                divisionNodePaths.size()
        );
    }

    private String resolveAttachmentDebugId() {
        if (attachmentItem != null && !attachmentItem.isEmpty()) {
            IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachmentItem);
            if (iAttachment != null) {
                return String.valueOf(iAttachment.getAttachmentId(attachmentItem));
            }
            if (attachmentItem.getItem().getRegistryName() != null) {
                return attachmentItem.getItem().getRegistryName().toString();
            }
        }
        return "unknown";
    }

    private static final class OcularWrapper {
        private final ModelRendererWrapper renderer;
        private final boolean scopeOcular;

        private OcularWrapper(ModelRendererWrapper renderer, boolean scopeOcular) {
            this.renderer = renderer;
            this.scopeOcular = scopeOcular;
        }
    }
}
