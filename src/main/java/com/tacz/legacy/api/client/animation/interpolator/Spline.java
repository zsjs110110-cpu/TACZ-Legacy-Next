package com.tacz.legacy.api.client.animation.interpolator;

import com.tacz.legacy.api.client.animation.AnimationChannelContent;

/**
 * glTF CUBICSPLINE 插值器。每个关键帧在 {@link AnimationChannelContent#values} 中按
 * [入切线, 值, 出切线] 顺序连续存储，故单帧长度为 3×维度（VEC3→9，VEC4→12）。
 * 采用按关键帧时间间隔缩放的三次 Hermite 曲线，旋转结果需归一化。
 */
public class Spline implements Interpolator {
    private AnimationChannelContent content;

    @Override
    public void compile(AnimationChannelContent content) {
        this.content = content;
    }

    @Override
    public float[] interpolate(int indexFrom, int indexTo, float alpha) {
        float[] from = content.values[indexFrom];
        float[] to = content.values[indexTo];
        int dim = from.length / 3;
        float[] result = new float[dim];
        if (dim <= 0) {
            return result;
        }
        if (indexFrom == indexTo) {
            // 无法构成切线区间：直接取该关键帧的值部分 [dim, 2*dim)
            for (int k = 0; k < dim; k++) {
                result[k] = from[dim + k];
            }
            if (dim == 4) {
                normalize(result);
            }
            return result;
        }
        float deltaTime = content.keyframeTimeS[indexTo] - content.keyframeTimeS[indexFrom];
        float t2 = alpha * alpha;
        float t3 = t2 * alpha;
        for (int k = 0; k < dim; k++) {
            float valueFrom = from[dim + k];        // 起点值
            float outTangent = from[2 * dim + k];   // 起点出切线
            float valueTo = to[dim + k];            // 终点值
            float inTangent = to[k];                // 终点入切线
            result[k] = (2 * t3 - 3 * t2 + 1) * valueFrom
                    + (t3 - 2 * t2 + alpha) * deltaTime * outTangent
                    + (-2 * t3 + 3 * t2) * valueTo
                    + (t3 - t2) * deltaTime * inTangent;
        }
        if (dim == 4) {
            normalize(result);
        }
        return result;
    }

    private static void normalize(float[] quaternion) {
        float length = (float) Math.sqrt(
                quaternion[0] * quaternion[0]
                        + quaternion[1] * quaternion[1]
                        + quaternion[2] * quaternion[2]
                        + quaternion[3] * quaternion[3]);
        if (length > 1e-6f) {
            quaternion[0] /= length;
            quaternion[1] /= length;
            quaternion[2] /= length;
            quaternion[3] /= length;
        }
    }

    @Override
    public Spline clone() {
        try {
            Spline spline = (Spline) super.clone();
            spline.content = this.content;
            return spline;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
