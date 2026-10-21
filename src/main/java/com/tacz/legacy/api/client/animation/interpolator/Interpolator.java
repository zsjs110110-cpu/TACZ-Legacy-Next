package com.tacz.legacy.api.client.animation.interpolator;

import com.tacz.legacy.api.client.animation.AnimationChannelContent;

public interface Interpolator extends Cloneable {
    void compile(AnimationChannelContent content);

    float[] interpolate(int indexFrom, int indexTo, float alpha);

    Interpolator clone();
}
