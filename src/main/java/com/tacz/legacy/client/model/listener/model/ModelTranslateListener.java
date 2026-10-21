package com.tacz.legacy.client.model.listener.model;

import com.tacz.legacy.api.client.animation.AnimationListener;
import com.tacz.legacy.api.client.animation.ObjectAnimationChannel;
import com.tacz.legacy.client.model.BedrockAnimatedModel;
import com.tacz.legacy.client.model.bedrock.ModelRendererWrapper;
import com.tacz.legacy.client.resource.pojo.model.BonesItem;

import javax.annotation.Nullable;

public class ModelTranslateListener implements AnimationListener {
    private final ModelRendererWrapper rendererWrapper;
    private final @Nullable BonesItem bonesItem;

    public ModelTranslateListener(BedrockAnimatedModel model, ModelRendererWrapper rendererWrapper, String nodeName) {
        this.rendererWrapper = rendererWrapper;
        if (model.getShouldRender().contains(rendererWrapper.getModelRenderer())) {
            this.bonesItem = model.getIndexBones().get(nodeName);
        } else {
            this.bonesItem = null;
        }
    }

    @Override
    public void update(float[] values, boolean blend) {
        if (blend) {
            rendererWrapper.addOffsetX(values[0]);
            rendererWrapper.addOffsetY(-values[1]);
            rendererWrapper.addOffsetZ(values[2]);
        } else {
            rendererWrapper.setOffsetX(values[0]);
            rendererWrapper.setOffsetY(-values[1]);
            rendererWrapper.setOffsetZ(values[2]);
        }
    }

    @Override
    public float[] initialValue() {
        float[] recover = new float[3];
        if (bonesItem != null) {
            recover[0] = bonesItem.getPivot().get(0) / 16f;
            recover[1] = -bonesItem.getPivot().get(1) / 16f;
            recover[2] = bonesItem.getPivot().get(2) / 16f;
        } else {
            recover[0] = rendererWrapper.getRotationPointX() / 16f;
            recover[1] = rendererWrapper.getRotationPointY() / 16f;
            recover[2] = rendererWrapper.getRotationPointZ() / 16f;
        }
        return recover;
    }

    @Override
    public ObjectAnimationChannel.ChannelType getType() {
        return ObjectAnimationChannel.ChannelType.TRANSLATION;
    }
}
