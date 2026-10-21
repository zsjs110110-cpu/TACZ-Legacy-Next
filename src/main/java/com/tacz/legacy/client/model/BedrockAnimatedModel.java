package com.tacz.legacy.client.model;

import com.tacz.legacy.api.client.animation.AnimationListener;
import com.tacz.legacy.api.client.animation.AnimationListenerSupplier;
import com.tacz.legacy.api.client.animation.ObjectAnimationChannel;
import com.tacz.legacy.client.model.bedrock.BedrockModel;
import com.tacz.legacy.client.model.bedrock.BedrockPart;
import com.tacz.legacy.client.model.bedrock.ModelRendererWrapper;
import com.tacz.legacy.client.model.listener.camera.CameraAnimationObject;
import com.tacz.legacy.client.model.listener.constraint.ConstraintObject;
import com.tacz.legacy.client.model.listener.model.ModelRotateListener;
import com.tacz.legacy.client.model.listener.model.ModelScaleListener;
import com.tacz.legacy.client.model.listener.model.ModelTranslateListener;
import com.tacz.legacy.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.legacy.client.resource.pojo.model.BedrockVersion;
import com.tacz.legacy.client.resource.pojo.model.BonesItem;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.tacz.legacy.client.model.GunModelConstant.ROOT_NODE;

public class BedrockAnimatedModel extends BedrockModel implements AnimationListenerSupplier {
    public static final String CAMERA_NODE_NAME = "camera";
    public static final String CONSTRAINT_NODE = "constraint";

    public static final class PartRenderStateSnapshot {
        public final boolean visible;
        public final float offsetX;
        public final float offsetY;
        public final float offsetZ;
        public final Quaternionf additionalQuaternion;
        public final float scaleX;
        public final float scaleY;
        public final float scaleZ;

        public PartRenderStateSnapshot(
                boolean visible,
                float offsetX,
                float offsetY,
                float offsetZ,
                Quaternionf additionalQuaternion,
                float scaleX,
                float scaleY,
                float scaleZ
        ) {
            this.visible = visible;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.additionalQuaternion = additionalQuaternion;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scaleZ = scaleZ;
        }
    }

    public static final class RenderStateSnapshot {
        public final Map<String, PartRenderStateSnapshot> partStates;
        public final Quaternionf cameraRotation;
        public final Vector3f constraintTranslation;
        public final Vector3f constraintRotation;

        public RenderStateSnapshot(
                Map<String, PartRenderStateSnapshot> partStates,
                Quaternionf cameraRotation,
                @Nullable Vector3f constraintTranslation,
                @Nullable Vector3f constraintRotation
        ) {
            this.partStates = partStates;
            this.cameraRotation = cameraRotation;
            this.constraintTranslation = constraintTranslation;
            this.constraintRotation = constraintRotation;
        }
    }

    private final CameraAnimationObject cameraAnimationObject = new CameraAnimationObject();
    protected @Nullable List<BedrockPart> constraintPath;
    private @Nullable ConstraintObject constraintObject;
    protected @Nullable BedrockPart root;
    protected @Nullable List<BedrockPart> idleSightPath;

    public BedrockAnimatedModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
        // 初始化相机动画对象
        ModelRendererWrapper cameraRendererWrapper = modelMap.get(CAMERA_NODE_NAME);
        if (cameraRendererWrapper != null) {
            cameraAnimationObject.cameraRenderer = cameraRendererWrapper;
        }
        // 初始化动画约束对象
        constraintPath = getPath(modelMap.get(CONSTRAINT_NODE));
        if (constraintPath != null) {
            constraintObject = new ConstraintObject();
            BedrockPart constraintNode = constraintPath.get(constraintPath.size() - 1);
            if (shouldRender.contains(constraintNode)) {
                constraintObject.bonesItem = indexBones.get(CONSTRAINT_NODE);
            } else {
                constraintObject.node = constraintNode;
            }
        }
        root = Optional.ofNullable(modelMap.get(ROOT_NODE)).map(ModelRendererWrapper::getModelRenderer).orElse(null);
        idleSightPath = getPath(modelMap.get("idle_view"));
    }

    @Nullable
    public List<BedrockPart> getConstraintPath() {
        return constraintPath;
    }

    public void cleanAnimationTransform() {
        for (ModelRendererWrapper rendererWrapper : modelMap.values()) {
            rendererWrapper.setOffsetX(0);
            rendererWrapper.setOffsetY(0);
            rendererWrapper.setOffsetZ(0);
            rendererWrapper.getAdditionalQuaternion().set(0, 0, 0, 1);
            rendererWrapper.setScaleX(1);
            rendererWrapper.setScaleY(1);
            rendererWrapper.setScaleZ(1);
        }
        if (constraintObject != null) {
            constraintObject.rotationConstraint.set(0, 0, 0);
            constraintObject.translationConstraint.set(0, 0, 0);
        }
    }

    public void cleanCameraAnimationTransform() {
        cameraAnimationObject.rotationQuaternion = new Quaternionf(0.0F, 0.0F, 0.0F, 1.0F);
    }

    public RenderStateSnapshot captureRenderState() {
        Map<String, PartRenderStateSnapshot> partStates = new HashMap<>();
        for (Map.Entry<String, ModelRendererWrapper> entry : modelMap.entrySet()) {
            BedrockPart part = entry.getValue().getModelRenderer();
            partStates.put(entry.getKey(), new PartRenderStateSnapshot(
                    part.visible,
                    part.offsetX,
                    part.offsetY,
                    part.offsetZ,
                    new Quaternionf(part.additionalQuaternion),
                    part.xScale,
                    part.yScale,
                    part.zScale
            ));
        }
        Vector3f translation = constraintObject != null ? new Vector3f(constraintObject.translationConstraint) : null;
        Vector3f rotation = constraintObject != null ? new Vector3f(constraintObject.rotationConstraint) : null;
        return new RenderStateSnapshot(
                partStates,
                new Quaternionf(cameraAnimationObject.rotationQuaternion),
                translation,
                rotation
        );
    }

    public void restoreRenderState(RenderStateSnapshot snapshot) {
        for (Map.Entry<String, PartRenderStateSnapshot> entry : snapshot.partStates.entrySet()) {
            ModelRendererWrapper wrapper = modelMap.get(entry.getKey());
            if (wrapper == null) {
                continue;
            }
            PartRenderStateSnapshot state = entry.getValue();
            BedrockPart part = wrapper.getModelRenderer();
            part.visible = state.visible;
            part.offsetX = state.offsetX;
            part.offsetY = state.offsetY;
            part.offsetZ = state.offsetZ;
            part.additionalQuaternion = new Quaternionf(state.additionalQuaternion);
            part.xScale = state.scaleX;
            part.yScale = state.scaleY;
            part.zScale = state.scaleZ;
        }
        cameraAnimationObject.rotationQuaternion = new Quaternionf(snapshot.cameraRotation);
        if (constraintObject != null) {
            if (snapshot.constraintTranslation != null) {
                constraintObject.translationConstraint.set(snapshot.constraintTranslation);
            } else {
                constraintObject.translationConstraint.set(0, 0, 0);
            }
            if (snapshot.constraintRotation != null) {
                constraintObject.rotationConstraint.set(snapshot.constraintRotation);
            } else {
                constraintObject.rotationConstraint.set(0, 0, 0);
            }
        }
    }

    /**
     * @param node     想要进行编程渲染流程的 node 名称
     * @param function 输入为 BedrockPart，返回 IFunctionalRenderer 以替换渲染
     */
    public void setFunctionalRenderer(String node, Function<BedrockPart, IFunctionalRenderer> function) {
        ModelRendererWrapper wrapper = modelMap.get(node);
        if (wrapper == null) {
            FunctionalBedrockPart functionalPart = new FunctionalBedrockPart(function, node);
            modelMap.put(node, new ModelRendererWrapper(functionalPart));
        } else if (wrapper.getModelRenderer() instanceof FunctionalBedrockPart) {
            ((FunctionalBedrockPart) wrapper.getModelRenderer()).functionalRenderer = function;
        }
    }

    @Nonnull
    public CameraAnimationObject getCameraAnimationObject() {
        return cameraAnimationObject;
    }

    @Nullable
    public ConstraintObject getConstraintObject() {
        return constraintObject;
    }

    @Override
    protected void loadNewModel(BedrockModelPOJO pojo) {
        assert pojo.getGeometryModelNew() != null;
        pojo.getGeometryModelNew().deco();
        if (pojo.getGeometryModelNew().getBones() == null) {
            return;
        }
        for (BonesItem bones : pojo.getGeometryModelNew().getBones()) {
            FunctionalBedrockPart bedrockPart = new FunctionalBedrockPart(null, bones.getName());
            modelMap.putIfAbsent(bones.getName(), new ModelRendererWrapper(bedrockPart));
        }
        super.loadNewModel(pojo);
    }

    @Override
    protected void loadLegacyModel(BedrockModelPOJO pojo) {
        assert pojo.getGeometryModelLegacy() != null;
        pojo.getGeometryModelLegacy().deco();
        if (pojo.getGeometryModelLegacy().getBones() == null) {
            return;
        }
        for (BonesItem bones : pojo.getGeometryModelLegacy().getBones()) {
            FunctionalBedrockPart bedrockPart = new FunctionalBedrockPart(null, bones.getName());
            modelMap.putIfAbsent(bones.getName(), new ModelRendererWrapper(bedrockPart));
        }
        super.loadLegacyModel(pojo);
    }

    @Override
    public AnimationListener supplyListeners(String nodeName, ObjectAnimationChannel.ChannelType type) {
        ModelRendererWrapper model = modelMap.get(nodeName);
        if (model == null) {
            com.tacz.legacy.TACZLegacy.logger.debug("Animation listener supply failed: node {} not found in modelMap", nodeName);
            return null;
        }
        AnimationListener cameraListener = cameraAnimationObject.supplyListeners(nodeName, type);
        if (cameraListener != null) {
            return cameraListener;
        }
        if (constraintObject != null) {
            AnimationListener constraintListener = constraintObject.supplyListeners(nodeName, type);
            if (constraintListener != null) {
                return constraintListener;
            }
        }
        if (type.equals(ObjectAnimationChannel.ChannelType.TRANSLATION)) {
            return new ModelTranslateListener(this, model, nodeName);
        }
        if (type.equals(ObjectAnimationChannel.ChannelType.ROTATION)) {
            return new ModelRotateListener(model);
        }
        if (type.equals(ObjectAnimationChannel.ChannelType.SCALE)) {
            return new ModelScaleListener(model);
        }
        return null;
    }

    public boolean getRenderHand() {
        return true;
    }

    @Nullable
    public BedrockPart getRootNode() {
        return root;
    }

    @Nullable
    public List<BedrockPart> getIdleSightPath() {
        return idleSightPath;
    }
}
