package com.tacz.legacy.client.resource.gltf;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Decoded glTF animation data reduced to the subset that the Legacy animation
 * controller needs at runtime: animation name, target node/path, interpolation,
 * keyframe times, and decoded float channel values.
 */
public final class GltfAnimationData {
    private final List<Animation> animations;

    public GltfAnimationData(List<Animation> animations) {
        this.animations = Collections.unmodifiableList(new ArrayList<>(animations));
    }

    @Nonnull
    public List<Animation> getAnimations() {
        return animations;
    }

    public static final class Animation {
        private final String name;
        private final List<Channel> channels;

        public Animation(String name, List<Channel> channels) {
            this.name = name;
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        }

        public String getName() {
            return name;
        }

        @Nonnull
        public List<Channel> getChannels() {
            return channels;
        }
    }

    public static final class Channel {
        private final String nodeName;
        private final Path path;
        private final Interpolation interpolation;
        private final float[] keyframeTimeS;
        private final float[][] values;
        private final float[] defaultValue;

        public Channel(String nodeName, Path path, Interpolation interpolation, float[] keyframeTimeS, float[][] values, float[] defaultValue) {
            this.nodeName = nodeName;
            this.path = path;
            this.interpolation = interpolation;
            this.keyframeTimeS = keyframeTimeS;
            this.values = values;
            this.defaultValue = defaultValue == null ? new float[0] : Arrays.copyOf(defaultValue, defaultValue.length);
        }

        public String getNodeName() {
            return nodeName;
        }

        public Path getPath() {
            return path;
        }

        public Interpolation getInterpolation() {
            return interpolation;
        }

        public float[] getKeyframeTimeS() {
            return keyframeTimeS;
        }

        public float[][] getValues() {
            return values;
        }

        public float[] getDefaultValue() {
            return Arrays.copyOf(defaultValue, defaultValue.length);
        }
    }

    public enum Path {
        TRANSLATION,
        ROTATION,
        SCALE
    }

    public enum Interpolation {
        LINEAR,
        STEP,
        SPLINE
    }
}
