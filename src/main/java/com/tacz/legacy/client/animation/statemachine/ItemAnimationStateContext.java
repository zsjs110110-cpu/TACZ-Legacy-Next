package com.tacz.legacy.client.animation.statemachine;

import com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext;

public class ItemAnimationStateContext extends AnimationStateContext {
    private float putAwayTime = 0f;
    protected float partialTicks = 0f;

    public float getPutAwayTime() {
        return putAwayTime;
    }

    public void setPutAwayTime(float putAwayTime) {
        this.putAwayTime = putAwayTime;
    }

    public float getPartialTicks() {
        return partialTicks;
    }

    public void setPartialTicks(float partialTicks) {
        this.partialTicks = partialTicks;
    }
}
