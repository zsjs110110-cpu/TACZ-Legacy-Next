package com.tacz.legacy.api.client.animation;

import com.tacz.legacy.api.client.animation.interpolator.Interpolator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ObjectAnimationChannel {
    public final ChannelType type;
    private final List<AnimationListener> listeners = new ArrayList<>();
    public String node;
    public AnimationChannelContent content;
    public Interpolator interpolator;
    /**
     * 此变量用于动画过渡，
     * 如果你不明白在做什么，请不要更改它
     */
    boolean transitioning = false;

    public ObjectAnimationChannel(ChannelType type) {
        this.type = type;
        this.content = new AnimationChannelContent();
    }

    public ObjectAnimationChannel(ChannelType type, AnimationChannelContent content) {
        this.type = type;
        this.content = content;
    }

    public void addListener(AnimationListener listener) {
        if (listener.getType().equals(type)) {
            listeners.add(listener);
        } else {
            throw new RuntimeException("trying to add wrong type of listener to channel.");
        }
    }

    public void removeListener(AnimationListener listener) {
        listeners.remove(listener);
    }

    public void clearListeners() {
        listeners.clear();
    }

    public List<AnimationListener> getListeners() {
        return listeners;
    }

    public float getEndTimeS() {
        if (content.keyframeTimeS.length == 0) {
            return 0;
        }
        return content.keyframeTimeS[content.keyframeTimeS.length - 1];
    }

    public void update(float timeS, boolean blend) {
        if (!transitioning) {
            float[] result = getResult(timeS);
            for (AnimationListener listener : listeners) {
                listener.update(result, blend);
            }
        }
    }

    public float[] getResult(float timeS) {
        int indexFrom = computeIndex(timeS);
        int indexTo = Math.min(content.keyframeTimeS.length - 1, indexFrom + 1);
        float alpha = computeAlpha(timeS, indexFrom);
        return interpolator.interpolate(indexFrom, indexTo, alpha);
    }

    private int computeIndex(float timeS) {
        int index = Arrays.binarySearch(content.keyframeTimeS, timeS);
        if (index >= 0) {
            return index;
        }
        return Math.max(0, -index - 2);
    }

    private float computeAlpha(float timeS, int indexFrom) {
        if (timeS <= content.keyframeTimeS[0]) {
            return 0.0f;
        }
        if (timeS >= content.keyframeTimeS[content.keyframeTimeS.length - 1]) {
            return 1.0f;
        }
        float local = timeS - content.keyframeTimeS[indexFrom];
        float delta = content.keyframeTimeS[indexFrom + 1] - content.keyframeTimeS[indexFrom];
        return local / delta;
    }

    public enum ChannelType {
        TRANSLATION,
        ROTATION,
        SCALE
    }
}
