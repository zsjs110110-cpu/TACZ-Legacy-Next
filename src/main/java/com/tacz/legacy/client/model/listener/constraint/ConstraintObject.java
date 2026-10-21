package com.tacz.legacy.client.model.listener.constraint;

import com.tacz.legacy.api.client.animation.AnimationListener;
import com.tacz.legacy.api.client.animation.AnimationListenerSupplier;
import com.tacz.legacy.api.client.animation.ObjectAnimationChannel;
import com.tacz.legacy.client.model.bedrock.BedrockPart;
import com.tacz.legacy.client.resource.pojo.model.BonesItem;
import org.joml.Vector3f;

import javax.annotation.Nullable;

import static com.tacz.legacy.client.model.BedrockAnimatedModel.CONSTRAINT_NODE;

public class ConstraintObject implements AnimationListenerSupplier {
    public Vector3f translationConstraint = new Vector3f(0, 0, 0);
    public Vector3f rotationConstraint = new Vector3f(0, 0, 0);
    /** 当constraint节点为根时，node为空 */
    public BedrockPart node;
    /** 当constraint节点不为根时，bonesItem为空 */
    public BonesItem bonesItem;

    @Nullable
    @Override
    public AnimationListener supplyListeners(String nodeName, ObjectAnimationChannel.ChannelType type) {
        if (!nodeName.equals(CONSTRAINT_NODE)) {
            return null;
        }
        if (type.equals(ObjectAnimationChannel.ChannelType.ROTATION)) {
            return new ConstraintRotateListener(this);
        }
        if (type.equals(ObjectAnimationChannel.ChannelType.TRANSLATION)) {
            return new ConstraintTranslateListener(this);
        }
        return null;
    }
}
