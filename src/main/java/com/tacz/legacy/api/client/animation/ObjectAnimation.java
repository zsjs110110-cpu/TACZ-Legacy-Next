package com.tacz.legacy.api.client.animation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * 创建一个 {@link ObjectAnimationRunner} 实例以运行 {@link ObjectAnimation}
 */
public class ObjectAnimation {
    public final String name;
    private final Map<String, List<ObjectAnimationChannel>> channels = new HashMap<>();
    @Nullable
    private ObjectAnimationSoundChannel soundChannel;
    public @Nonnull PlayType playType = PlayType.PLAY_ONCE_HOLD;
    private float maxEndTimeS = 0f;

    protected ObjectAnimation(@Nonnull String name) {
        this.name = Objects.requireNonNull(name);
    }

    /**
     * 创建源对象动画的拷贝，新对象动画不包含任何监听器。
     */
    public ObjectAnimation(ObjectAnimation source) {
        this.name = source.name;
        this.playType = source.playType;
        this.maxEndTimeS = source.maxEndTimeS;
        for (Map.Entry<String, List<ObjectAnimationChannel>> entry : source.channels.entrySet()) {
            List<ObjectAnimationChannel> newList = new ArrayList<>();
            for (ObjectAnimationChannel channel : entry.getValue()) {
                ObjectAnimationChannel newChannel = new ObjectAnimationChannel(channel.type, channel.content);
                newChannel.node = channel.node;
                newChannel.interpolator = channel.interpolator;
                newList.add(newChannel);
            }
            this.channels.put(entry.getKey(), newList);
        }
        if (source.soundChannel != null) {
            this.soundChannel = new ObjectAnimationSoundChannel(source.soundChannel.content);
        }
    }

    protected void addChannel(ObjectAnimationChannel channel) {
        channels.compute(channel.node, (node, list) -> {
            if (list == null) {
                list = new ArrayList<>();
            }
            list.add(channel);
            return list;
        });
        if (channel.getEndTimeS() > maxEndTimeS) {
            maxEndTimeS = channel.getEndTimeS();
        }
    }

    protected void setSoundChannel(@Nonnull ObjectAnimationSoundChannel soundChannel) {
        if (soundChannel.getEndTimeS() > maxEndTimeS) {
            maxEndTimeS = (float) soundChannel.getEndTimeS();
        }
        this.soundChannel = soundChannel;
    }

    public Map<String, List<ObjectAnimationChannel>> getChannels() {
        return channels;
    }

    @Nullable
    public ObjectAnimationSoundChannel getSoundChannel() {
        return this.soundChannel;
    }

    public void applyAnimationListeners(AnimationListenerSupplier supplier) {
        for (List<ObjectAnimationChannel> channelList : channels.values()) {
            for (ObjectAnimationChannel channel : channelList) {
                AnimationListener listener = supplier.supplyListeners(channel.node, channel.type);
                if (listener != null) {
                    if (!channel.getListeners().contains(listener)) {
                        channel.addListener(listener);
                    }
                }
            }
        }
    }

    public void update(boolean blend, float timeNs) {
        for (List<ObjectAnimationChannel> channels : channels.values()) {
            for (ObjectAnimationChannel channel : channels) {
                channel.update(timeNs / 1e9f, blend);
            }
        }
    }

    public float getMaxEndTimeS() {
        return maxEndTimeS;
    }

    public enum PlayType {
        PLAY_ONCE_HOLD,
        PLAY_ONCE_STOP,
        LOOP
    }
}
