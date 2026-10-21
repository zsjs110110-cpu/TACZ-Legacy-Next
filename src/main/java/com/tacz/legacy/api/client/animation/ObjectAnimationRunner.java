package com.tacz.legacy.api.client.animation;

import com.tacz.legacy.util.math.MathUtil;
import net.minecraft.client.Minecraft;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class ObjectAnimationRunner {
    @Nonnull
    private final ObjectAnimation animation;
    protected long transitionTimeNs;
    protected ArrayList<float[]> valueFrom;
    protected ArrayList<float[]> valueRecover;
    protected ArrayList<ObjectAnimationChannel> transitionFromChannels;
    protected ArrayList<ObjectAnimationChannel> transitionToChannels;
    protected ArrayList<ObjectAnimationChannel> recoverChannels;
    private boolean running = false;
    private boolean pausing = false;
    private long lastUpdateNs;
    private long progressNs;
    private boolean isTransitioning = false;
    @Nullable
    private ObjectAnimationRunner transitionTo;
    private long transitionProgressNs;

    public ObjectAnimationRunner(@Nonnull ObjectAnimation animation) {
        this.animation = Objects.requireNonNull(animation);
    }

    public @Nonnull ObjectAnimation getAnimation() {
        return animation;
    }

    @Nullable
    public ObjectAnimationRunner getTransitionTo() {
        return transitionTo;
    }

    public boolean isTransitioning() {
        return isTransitioning;
    }

    public void run() {
        if (!running) {
            running = true;
            lastUpdateNs = System.nanoTime();
        }
        pausing = false;
    }

    public void pause() {
        running = false;
        pausing = true;
    }

    public void hold() {
        progressNs = (long) (animation.getMaxEndTimeS() * 1e9) + 1;
        running = false;
    }

    public void stop() {
        progressNs = (long) (animation.getMaxEndTimeS() * 1e9) + 2;
        running = false;
    }

    public void reset() {
        progressNs = 0;
    }

    public long getProgressNs() {
        return progressNs;
    }

    public void setProgressNs(long progressNs) {
        this.progressNs = progressNs;
    }

    public void transition(ObjectAnimationRunner transitionTo, long transitionTimeNS) {
        if (this.transitionTo == null) {
            this.valueFrom = new ArrayList<>();
            this.valueRecover = new ArrayList<>();
            this.transitionFromChannels = new ArrayList<>();
            this.transitionToChannels = new ArrayList<>();
            this.recoverChannels = new ArrayList<>();
            this.transitionTo = transitionTo;
            this.running = false;
            for (Map.Entry<String, List<ObjectAnimationChannel>> entry : animation.getChannels().entrySet()) {
                List<ObjectAnimationChannel> toChannels = transitionTo.animation.getChannels().get(entry.getKey());
                if (toChannels != null) {
                    for (ObjectAnimationChannel channel : entry.getValue()) {
                        ObjectAnimationChannel matchedToChannel = null;
                        for (ObjectAnimationChannel c : toChannels) {
                            if (c.type.equals(channel.type)) {
                                matchedToChannel = c;
                                break;
                            }
                        }
                        float[] value = channel.getResult(progressNs / 1e9f);
                        if (channel.type == ObjectAnimationChannel.ChannelType.ROTATION && value.length == 3) {
                            value = MathUtil.toQuaternion(value[0], value[1], value[2]);
                        }
                        if (matchedToChannel != null) {
                            valueFrom.add(value);
                            transitionFromChannels.add(channel);
                            transitionToChannels.add(matchedToChannel);
                            matchedToChannel.transitioning = true;
                        } else {
                            valueRecover.add(value);
                            recoverChannels.add(channel);
                        }
                    }
                } else {
                    for (ObjectAnimationChannel channel : entry.getValue()) {
                        float[] value = channel.getResult(progressNs / 1e9f);
                        if (channel.type == ObjectAnimationChannel.ChannelType.ROTATION && value.length == 3) {
                            value = MathUtil.toQuaternion(value[0], value[1], value[2]);
                        }
                        valueRecover.add(value);
                        recoverChannels.add(channel);
                    }
                }
            }
        } else if (isTransitioning) {
            ArrayList<float[]> newValueFrom = new ArrayList<>();
            ArrayList<float[]> newValueRecover = new ArrayList<>();
            ArrayList<ObjectAnimationChannel> newTransitionFromChannels = new ArrayList<>();
            ArrayList<ObjectAnimationChannel> newTransitionToChannels = new ArrayList<>();
            ArrayList<ObjectAnimationChannel> newRecoverChannels = new ArrayList<>();
            for (int i = 0; i < transitionFromChannels.size(); i++) {
                assert this.transitionTo != null;
                ObjectAnimationChannel fromChannel = transitionFromChannels.get(i);
                ObjectAnimationChannel toChannel = transitionToChannels.get(i);
                float[] from = valueFrom.get(i);
                float[] to = toChannel.getResult(this.transitionTo.progressNs / 1e9f);
                float[] result;
                float progress = easeOutCubic((float) transitionProgressNs / transitionTimeNs);
                if (fromChannel.type.equals(ObjectAnimationChannel.ChannelType.TRANSLATION)) {
                    result = new float[3];
                    lerp(from, to, progress, result);
                } else if (fromChannel.type.equals(ObjectAnimationChannel.ChannelType.ROTATION)) {
                    result = new float[4];
                    if (to.length == 3) {
                        to = MathUtil.toQuaternion(to[0], to[1], to[2]);
                    }
                    slerp(from, to, progress, result);
                } else {
                    result = new float[3];
                    lerp(from, to, progress, result);
                }

                List<ObjectAnimationChannel> newToChannels = transitionTo.animation.getChannels().get(fromChannel.node);
                if (newToChannels != null) {
                    ObjectAnimationChannel newToChannel = null;
                    for (ObjectAnimationChannel c : newToChannels) {
                        if (c.type.equals(fromChannel.type)) {
                            newToChannel = c;
                            break;
                        }
                    }
                    if (newToChannel != null) {
                        newValueFrom.add(result);
                        newTransitionFromChannels.add(fromChannel);
                        newTransitionToChannels.add(newToChannel);
                        newToChannel.transitioning = true;
                    } else {
                        newValueRecover.add(result);
                        newRecoverChannels.add(fromChannel);
                    }
                } else {
                    newValueRecover.add(result);
                    newRecoverChannels.add(fromChannel);
                }
                toChannel.transitioning = false;
            }
            this.valueFrom = newValueFrom;
            this.valueRecover = newValueRecover;
            this.transitionToChannels = newTransitionToChannels;
            this.transitionFromChannels = newTransitionFromChannels;
            this.recoverChannels = newRecoverChannels;
            this.transitionTo = transitionTo;
        }
        this.transitionTimeNs = transitionTimeNS;
        this.transitionProgressNs = 0;
        this.isTransitioning = true;
    }

    public long getTransitionTimeNs() {
        return transitionTimeNs;
    }

    public long getTransitionProgressNs() {
        return transitionProgressNs;
    }

    public void setTransitionProgressNs(long progressNs) {
        this.transitionProgressNs = progressNs;
    }

    public void stopTransition() {
        this.isTransitioning = false;
        for (ObjectAnimationChannel channel : transitionToChannels) {
            channel.transitioning = false;
        }
        this.transitionTimeNs = 0;
        this.transitionProgressNs = 0;
        this.transitionFromChannels = null;
        this.transitionToChannels = null;
        this.recoverChannels = null;
        this.valueFrom = null;
        this.valueRecover = null;
    }

    private void updateProgress(long alphaProgress) {
        if (running) {
            progressNs += alphaProgress;
        }
        switch (animation.playType) {
            case PLAY_ONCE_HOLD:
                if (progressNs / 1e9 > animation.getMaxEndTimeS()) {
                    hold();
                }
                break;
            case PLAY_ONCE_STOP:
                if (progressNs / 1e9 > animation.getMaxEndTimeS()) {
                    stop();
                }
                break;
            case LOOP:
                if (progressNs / 1e9 > animation.getMaxEndTimeS()) {
                    if (animation.getMaxEndTimeS() == 0) {
                        progressNs = 0;
                    } else {
                        progressNs = progressNs % (long) (animation.getMaxEndTimeS() * 1e9);
                    }
                }
                break;
        }
    }

    public void update(boolean blend) {
        long fromTimeNs = progressNs;
        long currentNs = System.nanoTime();
        long alphaProgress = currentNs - lastUpdateNs;
        updateProgress(alphaProgress);
        lastUpdateNs = currentNs;
        if (isTransitioning) {
            transitionProgressNs += alphaProgress;
            if (transitionProgressNs >= transitionTimeNs) {
                stopTransition();
            } else {
                float transitionProgress = (float) transitionProgressNs / transitionTimeNs;
                updateTransition(easeOutCubic(transitionProgress), blend);
            }
        } else {
            animation.update(blend, progressNs);
            ObjectAnimationSoundChannel soundChannel = animation.getSoundChannel();
            if (soundChannel != null && Minecraft.getMinecraft().player != null) {
                soundChannel.playSound(fromTimeNs / 1e9, progressNs / 1e9, Minecraft.getMinecraft().player, 16, 1, 1);
            }
        }
    }

    public void updateSoundOnly() {
        long fromTimeNs = progressNs;
        long currentNs = System.nanoTime();
        updateProgress(currentNs - lastUpdateNs);
        lastUpdateNs = currentNs;
        ObjectAnimationSoundChannel soundChannel = animation.getSoundChannel();
        if (soundChannel != null && Minecraft.getMinecraft().player != null) {
            soundChannel.playSound(fromTimeNs / 1e9, progressNs / 1e9, Minecraft.getMinecraft().player, 16, 1, 1);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPausing() {
        return pausing;
    }

    public boolean isHolding() {
        return progressNs == (long) (getAnimation().getMaxEndTimeS() * 1e9) + 1;
    }

    public boolean isStopped() {
        return progressNs == (long) (getAnimation().getMaxEndTimeS() * 1e9) + 2;
    }

    private void updateTransition(float progress, boolean blend) {
        assert transitionTo != null;
        for (int i = 0; i < transitionToChannels.size(); i++) {
            ObjectAnimationChannel fromChannel = transitionFromChannels.get(i);
            ObjectAnimationChannel toChannel = transitionToChannels.get(i);

            float[] from = valueFrom.get(i);
            float[] to = toChannel.getResult(transitionTo.progressNs / 1e9f);
            float[] result;

            if (fromChannel.type.equals(ObjectAnimationChannel.ChannelType.TRANSLATION)) {
                result = new float[3];
                lerp(from, to, progress, result);
            } else if (fromChannel.type.equals(ObjectAnimationChannel.ChannelType.ROTATION)) {
                result = new float[4];
                if (to.length == 3) {
                    to = MathUtil.toQuaternion(to[0], to[1], to[2]);
                }
                slerp(from, to, progress, result);
            } else {
                result = new float[3];
                lerp(from, to, progress, result);
            }
            for (AnimationListener listener : fromChannel.getListeners()) {
                listener.update(result, blend);
            }
        }
        if (animation.playType != ObjectAnimation.PlayType.PLAY_ONCE_STOP) {
            for (int i = 0; i < recoverChannels.size(); i++) {
                ObjectAnimationChannel channel = recoverChannels.get(i);
                float[] from = valueRecover.get(i);
                float[] result;
                if (channel.type.equals(ObjectAnimationChannel.ChannelType.TRANSLATION)) {
                    result = new float[3];
                    float[] to = new float[]{0, 0, 0};
                    for (AnimationListener listener : channel.getListeners()) {
                        lerp(from, to, progress, result);
                        listener.update(result, blend);
                    }
                } else if (channel.type.equals(ObjectAnimationChannel.ChannelType.ROTATION)) {
                    result = new float[4];
                    float[] to = new float[]{0, 0, 0, 1};
                    for (AnimationListener listener : channel.getListeners()) {
                        slerp(from, to, progress, result);
                        listener.update(result, blend);
                    }
                } else if (channel.type.equals(ObjectAnimationChannel.ChannelType.SCALE)) {
                    result = new float[3];
                    float[] to = new float[]{1, 1, 1};
                    for (AnimationListener listener : channel.getListeners()) {
                        lerp(from, to, progress, result);
                        listener.update(result, blend);
                    }
                }
            }
        }
    }

    private float easeOutCubic(double x) {
        return (float) (1 - Math.pow(1 - x, 4));
    }

    private void lerp(float[] from, float[] to, float alpha, float[] result) {
        for (int i = 0; i < result.length; i++) {
            result[i] = from[i] * (1 - alpha) + to[i] * alpha;
        }
    }

    private void slerp(float[] from, float[] to, float alpha, float[] result) {
        float ax = from[0];
        float ay = from[1];
        float az = from[2];
        float aw = from[3];
        float bx = to[0];
        float by = to[1];
        float bz = to[2];
        float bw = to[3];

        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0) {
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }
        float epsilon = 1e-6f;
        float s0, s1;
        if ((1.0 - dot) > epsilon) {
            float omega = (float) Math.acos(dot);
            float invSinOmega = 1.0f / (float) Math.sin(omega);
            s0 = (float) Math.sin((1.0 - alpha) * omega) * invSinOmega;
            s1 = (float) Math.sin(alpha * omega) * invSinOmega;
        } else {
            s0 = 1.0f - alpha;
            s1 = alpha;
        }
        result[0] = s0 * ax + s1 * bx;
        result[1] = s0 * ay + s1 * by;
        result[2] = s0 * az + s1 * bz;
        result[3] = s0 * aw + s1 * bw;
    }
}
