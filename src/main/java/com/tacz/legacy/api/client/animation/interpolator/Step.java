package com.tacz.legacy.api.client.animation.interpolator;

import com.tacz.legacy.api.client.animation.AnimationChannelContent;

import java.util.Arrays;

public class Step implements Interpolator {
    private AnimationChannelContent content;

    @Override
    public void compile(AnimationChannelContent content) {
        this.content = content;
    }

    @Override
    public float[] interpolate(int indexFrom, int indexTo, float alpha) {
        float[] result;
        int offset = getOffset(content.values[indexFrom].length);
        if (alpha < 1 || indexFrom == indexTo) {
            result = Arrays.copyOfRange(content.values[indexFrom], offset, content.values[indexFrom].length);
        } else {
            int length = content.values[indexTo].length;
            length = getResultLength(length);
            result = Arrays.copyOfRange(content.values[indexTo], 0, length);
        }
        return result;
    }

    private static int getOffset(int length) {
        if (length == 8) return 4;
        if (length == 6) return 3;
        return 0;
    }

    private static int getResultLength(int length) {
        if (length == 8) return 4;
        if (length == 6) return 3;
        return length;
    }

    @Override
    public Step clone() {
        try {
            Step step = (Step) super.clone();
            step.content = this.content;
            return step;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
