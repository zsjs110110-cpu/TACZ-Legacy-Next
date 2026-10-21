package com.tacz.legacy.client.model.bedrock;

import com.tacz.legacy.client.model.IFunctionalRenderer;
import com.tacz.legacy.client.resource.pojo.model.*;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Core bedrock model class.
 * Loads geometry from Bedrock-format POJO and renders using 1.12.2 GL immediate mode.
 * Port of upstream TACZ BedrockModel.
 */
public class BedrockModel {
    public static final BedrockModel DUMMY = new BedrockModel();

    /**
     * 兼容层：几何缺字段时的告警出口。
     *
     * 坏的第三方模型如果静默变成"隐形方块"，比直接报错更难排查 ——
     * 这里至少留下模型/骨骼/cube 的标识。
     */
    private static final Logger LOGGER = LogManager.getLogger("tacz");

    /**
     * Renderers delegated to run after the main model render pass completes.
     * Used by TextShowRender to avoid corrupting GL state during bone traversal.
     */
    protected List<IFunctionalRenderer> delegateRenderers = new ArrayList<>();

    protected final HashMap<String, ModelRendererWrapper> modelMap = new HashMap<>();
    protected final HashMap<String, BonesItem> indexBones = new HashMap<>();
    protected final List<BedrockPart> shouldRender = new LinkedList<>();
    protected @Nullable Vector3f offset = null;
    protected @Nullable Vector2f size = null;

    public BedrockModel(BedrockModelPOJO pojo, BedrockVersion version) {
        if (version == BedrockVersion.LEGACY) {
            loadLegacyModel(pojo);
        }
        if (version == BedrockVersion.NEW) {
            loadNewModel(pojo);
        }
        // Apply illumination to bones ending with _illuminated
        for (ModelRendererWrapper rendererWrapper : modelMap.values()) {
            if (rendererWrapper.getModelRenderer().name != null
                    && rendererWrapper.getModelRenderer().name.endsWith("_illuminated")) {
                rendererWrapper.getModelRenderer().illuminated = true;
            }
        }
    }

    /** Dummy constructor for sentinel instance. */
    protected BedrockModel() {
    }

    private void setRotationAngle(BedrockPart modelRenderer, float x, float y, float z) {
        modelRenderer.xRot = x;
        modelRenderer.yRot = y;
        modelRenderer.zRot = z;
        modelRenderer.setInitRotationAngle(x, y, z);
    }

    protected void loadNewModel(BedrockModelPOJO pojo) {
        assert pojo.getGeometryModelNew() != null;
        pojo.getGeometryModelNew().deco();
        if (pojo.getGeometryModelNew().getBones() == null) {
            return;
        }
        Description description = pojo.getGeometryModelNew().getDescription();
        int texWidth = description.getTextureWidth();
        int texHeight = description.getTextureHeight();

        // 第三方模型可能省略 visible_bounds_offset；缺失时按原点处理，避免 NPE 让整个模型加载失败
        List<Float> visBoundsOffset = description.getVisibleBoundsOffset();
        float offsetX = visBoundsOffset != null && visBoundsOffset.size() > 0 ? visBoundsOffset.get(0) : 0.0f;
        float offsetY = visBoundsOffset != null && visBoundsOffset.size() > 1 ? visBoundsOffset.get(1) : 0.0f;
        float offsetZ = visBoundsOffset != null && visBoundsOffset.size() > 2 ? visBoundsOffset.get(2) : 0.0f;
        this.offset = new Vector3f(offsetX, offsetY, offsetZ);
        float width = description.getVisibleBoundsWidth() / 2.0f;
        float height = description.getVisibleBoundsHeight() / 2.0f;
        this.size = new Vector2f(width, height);

        for (BonesItem bones : pojo.getGeometryModelNew().getBones()) {
            indexBones.putIfAbsent(bones.getName(), bones);
            modelMap.putIfAbsent(bones.getName(), new ModelRendererWrapper(new BedrockPart(bones.getName())));
        }

        for (BonesItem bones : pojo.getGeometryModelNew().getBones()) {
            String name = bones.getName();
            @Nullable List<Float> rotation = bones.getRotation();
            @Nullable String parent = bones.getParent();
            ModelRendererWrapper boneWrapper = modelMap.get(name);
            if (boneWrapper == null) {
                LOGGER.warn("Bone '{}' has no registered part; bone skipped.", name);
                continue;
            }
            BedrockPart model = boneWrapper.getModelRenderer();

            model.mirror = bones.isMirror();
            model.setPos(convertPivot(bones, 0), convertPivot(bones, 1), convertPivot(bones, 2));

            if (rotation != null) {
                setRotationAngle(model, convertRotation(rotation.get(0)), convertRotation(rotation.get(1)), convertRotation(rotation.get(2)));
            }

            if (parent != null) {
                // 兼容层：parent 指向不存在的 bone 时按根骨骼处理，而不是 NPE。
                // 上半段 convertPivot 已经是这个宽容度，两处口径必须一致。
                ModelRendererWrapper parentWrapper = modelMap.get(parent);
                if (parentWrapper == null) {
                    LOGGER.warn("Bone '{}' references missing parent '{}'; attached to root.", name, parent);
                    shouldRender.add(model);
                    model.parent = null;
                } else {
                    BedrockPart parentPart = parentWrapper.getModelRenderer();
                    parentPart.addChild(model);
                    model.parent = parentPart;
                }
            } else {
                shouldRender.add(model);
                model.parent = null;
            }

            if (bones.getCubes() == null) {
                continue;
            }

            for (CubesItem cube : bones.getCubes()) {
                List<Float> uv = cube.getUv();
                @Nullable FaceUVsItem faceUv = cube.getFaceUv();
                List<Float> cubeSize = cube.getSize();
                @Nullable List<Float> cubeRotation = cube.getRotation();
                boolean mirror = cube.isMirror();
                float inflate = cube.getInflate();

                // 兼容层：缺 size / 缺 UV 时跳过该 cube 并告警。
                // 上游在这里直接 deref（NPE），但 NPE 会被上层吞成"整个模型没了"，
                // 比丢一个 cube 更难看；而"隐形方块"又完全不可归因。
                if (cubeSize == null || cubeSize.size() < 3) {
                    LOGGER.warn("Bone '{}': cube without a valid size ({}); cube skipped.", name, cubeSize);
                    continue;
                }
                if (faceUv == null && (uv == null || uv.size() < 2)) {
                    LOGGER.warn("Bone '{}': cube has neither 'uv' array nor per-face 'uv'; cube skipped.", name);
                    continue;
                }

                if (cubeRotation == null) {
                    if (faceUv == null) {
                        model.cubes.add(new BedrockCubeBox(uv.get(0), uv.get(1),
                                convertOrigin(bones, cube, 0), convertOrigin(bones, cube, 1), convertOrigin(bones, cube, 2),
                                cubeSize.get(0), cubeSize.get(1), cubeSize.get(2), inflate, mirror,
                                texWidth, texHeight));
                    } else {
                        model.cubes.add(new BedrockCubePerFace(
                                convertOrigin(bones, cube, 0), convertOrigin(bones, cube, 1), convertOrigin(bones, cube, 2),
                                cubeSize.get(0), cubeSize.get(1), cubeSize.get(2), inflate,
                                texWidth, texHeight, faceUv));
                    }
                } else {
                    BedrockPart cubeRenderer = new BedrockPart(null);
                    cubeRenderer.setPos(convertPivot(bones, cube, 0), convertPivot(bones, cube, 1), convertPivot(bones, cube, 2));
                    setRotationAngle(cubeRenderer, convertRotation(cubeRotation.get(0)), convertRotation(cubeRotation.get(1)), convertRotation(cubeRotation.get(2)));
                    if (faceUv == null) {
                        cubeRenderer.cubes.add(new BedrockCubeBox(uv.get(0), uv.get(1),
                                convertOrigin(cube, 0), convertOrigin(cube, 1), convertOrigin(cube, 2),
                                cubeSize.get(0), cubeSize.get(1), cubeSize.get(2), inflate, mirror,
                                texWidth, texHeight));
                    } else {
                        cubeRenderer.cubes.add(new BedrockCubePerFace(
                                convertOrigin(cube, 0), convertOrigin(cube, 1), convertOrigin(cube, 2),
                                cubeSize.get(0), cubeSize.get(1), cubeSize.get(2), inflate,
                                texWidth, texHeight, faceUv));
                    }
                    model.addChild(cubeRenderer);
                }
            }
        }
    }

    protected void loadLegacyModel(BedrockModelPOJO pojo) {
        assert pojo.getGeometryModelLegacy() != null;
        pojo.getGeometryModelLegacy().deco();
        if (pojo.getGeometryModelLegacy().getBones() == null) {
            return;
        }

        int texWidth = pojo.getGeometryModelLegacy().getTextureWidth();
        int texHeight = pojo.getGeometryModelLegacy().getTextureHeight();

        List<Float> visBoundsOffset = pojo.getGeometryModelLegacy().getVisibleBoundsOffset();
        float offsetX = visBoundsOffset != null && visBoundsOffset.size() > 0 ? visBoundsOffset.get(0) : 0.0f;
        float offsetY = visBoundsOffset != null && visBoundsOffset.size() > 1 ? visBoundsOffset.get(1) : 0.0f;
        float offsetZ = visBoundsOffset != null && visBoundsOffset.size() > 2 ? visBoundsOffset.get(2) : 0.0f;
        this.offset = new Vector3f(offsetX, offsetY, offsetZ);
        float width = pojo.getGeometryModelLegacy().getVisibleBoundsWidth() / 2.0f;
        float height = pojo.getGeometryModelLegacy().getVisibleBoundsHeight() / 2.0f;
        this.size = new Vector2f(width, height);

        for (BonesItem bones : pojo.getGeometryModelLegacy().getBones()) {
            indexBones.putIfAbsent(bones.getName(), bones);
            modelMap.putIfAbsent(bones.getName(), new ModelRendererWrapper(new BedrockPart(bones.getName())));
        }

        for (BonesItem bones : pojo.getGeometryModelLegacy().getBones()) {
            String name = bones.getName();
            @Nullable List<Float> rotation = bones.getRotation();
            @Nullable String parent = bones.getParent();
            ModelRendererWrapper boneWrapper = modelMap.get(name);
            if (boneWrapper == null) {
                LOGGER.warn("Legacy bone '{}' has no registered part; bone skipped.", name);
                continue;
            }
            BedrockPart model = boneWrapper.getModelRenderer();

            model.mirror = bones.isMirror();
            model.setPos(convertPivot(bones, 0), convertPivot(bones, 1), convertPivot(bones, 2));

            if (rotation != null) {
                setRotationAngle(model, convertRotation(rotation.get(0)), convertRotation(rotation.get(1)), convertRotation(rotation.get(2)));
            }

            if (parent != null) {
                // 兼容层：与 loadNewModel 同样宽容 —— parent 不存在时挂到根骨骼
                ModelRendererWrapper parentWrapper = modelMap.get(parent);
                if (parentWrapper == null) {
                    LOGGER.warn("Legacy bone '{}' references missing parent '{}'; attached to root.", name, parent);
                    shouldRender.add(model);
                } else {
                    parentWrapper.getModelRenderer().addChild(model);
                }
            } else {
                shouldRender.add(model);
            }

            if (bones.getCubes() == null) {
                continue;
            }

            for (CubesItem cube : bones.getCubes()) {
                List<Float> uv = cube.getUv();
                List<Float> cubeSize = cube.getSize();
                boolean mirror = cube.isMirror();
                float inflate = cube.getInflate();

                if (cubeSize == null || cubeSize.size() < 3 || uv == null || uv.size() < 2) {
                    LOGGER.warn("Legacy bone '{}': cube without a valid uv/size; cube skipped.", name);
                    continue;
                }

                model.cubes.add(new BedrockCubeBox(uv.get(0), uv.get(1),
                        convertOrigin(bones, cube, 0), convertOrigin(bones, cube, 1), convertOrigin(bones, cube, 2),
                        cubeSize.get(0), cubeSize.get(1), cubeSize.get(2), inflate, mirror,
                        texWidth, texHeight));
            }
        }
    }

    protected float convertPivot(BonesItem bones, int index) {
        List<Float> pivot = bones.getPivot();
        if (pivot == null || pivot.size() <= index) {
            // 第三方模型可能省略 pivot：按原点处理，y 轴仍套用 24 的基准
            return index == 1 ? 24.0f : 0.0f;
        }
        if (bones.getParent() != null) {
            BonesItem parent = indexBones.get(bones.getParent());
            List<Float> parentPivot = parent == null ? null : parent.getPivot();
            if (parentPivot == null || parentPivot.size() <= index) {
                // parent 指向不存在的 bone，或 parent 缺 pivot：退化为"无 parent"
                return index == 1 ? 24 - pivot.get(index) : pivot.get(index);
            }
            if (index == 1) {
                return parentPivot.get(index) - pivot.get(index);
            } else {
                return pivot.get(index) - parentPivot.get(index);
            }
        } else {
            if (index == 1) {
                return 24 - pivot.get(index);
            } else {
                return pivot.get(index);
            }
        }
    }

    protected float convertPivot(BonesItem parent, CubesItem cube, int index) {
        List<Float> cubePivot = cube.getPivot();
        List<Float> parentPivot = parent.getPivot();
        if (cubePivot == null || parentPivot == null
                || cubePivot.size() <= index || parentPivot.size() <= index) {
            return 0.0f;
        }
        if (index == 1) {
            return parentPivot.get(index) - cubePivot.get(index);
        } else {
            return cubePivot.get(index) - parentPivot.get(index);
        }
    }

    protected float convertOrigin(BonesItem bone, CubesItem cube, int index) {
        List<Float> pivot = bone.getPivot();
        List<Float> origin = cube.getOrigin();
        List<Float> size = cube.getSize();
        if (pivot == null || origin == null || size == null
                || pivot.size() <= index || origin.size() <= index || size.size() <= index) {
            return 0.0f;
        }
        if (index == 1) {
            return pivot.get(index) - origin.get(index) - size.get(index);
        } else {
            return origin.get(index) - pivot.get(index);
        }
    }

    protected float convertOrigin(CubesItem cube, int index) {
        List<Float> pivot = cube.getPivot();
        List<Float> origin = cube.getOrigin();
        List<Float> size = cube.getSize();
        if (pivot == null || origin == null || size == null
                || pivot.size() <= index || origin.size() <= index || size.size() <= index) {
            return 0.0f;
        }
        if (index == 1) {
            return pivot.get(index) - origin.get(index) - size.get(index);
        } else {
            return origin.get(index) - pivot.get(index);
        }
    }

    protected float convertRotation(float degree) {
        return (float) (degree * Math.PI / 180);
    }

    @Nullable
    public BedrockPart getNode(String nodeName) {
        ModelRendererWrapper rendererWrapper = modelMap.get(nodeName);
        return rendererWrapper != null ? rendererWrapper.getModelRenderer() : null;
    }

    @Nullable
    public BonesItem getBone(String name) {
        return indexBones.get(name);
    }

    /**
     * Register a renderer to be invoked after the main model render pass.
     */
    public void delegateRender(IFunctionalRenderer renderer) {
        delegateRenderers.add(renderer);
    }

    /**
     * Render all root bones using 1.12.2 GL immediate mode.
     * Caller must bind texture and set up GL state before calling this.
     */
    public void render() {
        render(BedrockRenderMode.NORMAL);
    }

    /**
     * Render only the illuminated subtree contribution for bloom post-processing.
     */
    public void renderBloom() {
        render(BedrockRenderMode.BLOOM);
    }

    public void render(BedrockRenderMode mode) {
        float prevBX = OpenGlHelper.lastBrightnessX;
        float prevBY = OpenGlHelper.lastBrightnessY;

        GlStateManager.pushMatrix();
        for (BedrockPart model : shouldRender) {
            model.render(mode, false);
        }
        GlStateManager.popMatrix();

        // Restore lightmap in case any illuminated bones changed it
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevBX, prevBY);

        // Execute delegate renderers (e.g. text show) after main pass
        if (mode == BedrockRenderMode.NORMAL && !delegateRenderers.isEmpty()) {
            int packedLight = (int) prevBX | ((int) prevBY << 16);
            for (IFunctionalRenderer delegate : delegateRenderers) {
                delegate.render(packedLight);
            }
            delegateRenderers = new ArrayList<>();
        }
    }

    @Nullable
    public Vector3f getOffset() {
        return offset;
    }

    @Nullable
    public Vector2f getSize() {
        return size;
    }

    public List<BedrockPart> getShouldRender() {
        return shouldRender;
    }

    public HashMap<String, BonesItem> getIndexBones() {
        return indexBones;
    }

    public HashMap<String, ModelRendererWrapper> getModelMap() {
        return modelMap;
    }

    /**
     * Get the path from root to the given node (inclusive).
     * Returns null if the wrapper is null.
     */
    @Nullable
    protected List<BedrockPart> getPath(@Nullable ModelRendererWrapper rendererWrapper) {
        if (rendererWrapper == null) {
            return null;
        }
        BedrockPart part = rendererWrapper.getModelRenderer();
        List<BedrockPart> path = new ArrayList<>();
        Stack<BedrockPart> stack = new Stack<>();
        do {
            stack.push(part);
            part = part.getParent();
        } while (part != null);
        while (!stack.isEmpty()) {
            part = stack.pop();
            path.add(part);
        }
        return path;
    }
}
