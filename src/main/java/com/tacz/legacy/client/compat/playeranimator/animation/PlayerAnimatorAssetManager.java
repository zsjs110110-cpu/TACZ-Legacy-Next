package com.tacz.legacy.client.compat.playeranimator.animation;

import com.google.common.collect.Maps;
import com.tacz.legacy.TACZLegacy;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.gson.AnimationSerializing;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 枪包动画资产索引，key 为 display 的 {@code player_animator_3rd}，value 为 clip 名 → 动画。
 * <p>
 * 上游是一个 1.20 资源重载监听器；1.12.2 没有 FileToIdConverter，
 * 改为由 Legacy 的 [com.tacz.legacy.client.resource.TACZClientAssetManager]
 * 在枪包加载/重载时逐包灌入。
 */
public class PlayerAnimatorAssetManager {
    private static PlayerAnimatorAssetManager INSTANCE;

    private final HashMap<ResourceLocation, HashMap<String, KeyframeAnimation>> animations = new HashMap<>();

    public static PlayerAnimatorAssetManager get() {
        if (INSTANCE == null) {
            INSTANCE = new PlayerAnimatorAssetManager();
        }
        return INSTANCE;
    }

    public void putAnimation(ResourceLocation id, InputStream stream) throws IOException {
        List<KeyframeAnimation> keyframeAnimations = AnimationSerializing.deserializeAnimation(stream);
        for (KeyframeAnimation animation : keyframeAnimations) {
            Object nameObject = animation.extraData.get("name");
            if (nameObject instanceof String) {
                String name = PlayerAnimationRegistry.serializeTextToString((String) nameObject).toLowerCase(Locale.ENGLISH);
                HashMap<String, KeyframeAnimation> map = animations.get(id);
                if (map == null) {
                    map = Maps.newHashMap();
                    animations.put(id, map);
                }
                map.put(name, animation);
            }
        }
        HashMap<String, KeyframeAnimation> loaded = animations.get(id);
        TACZLegacy.logger.info("[PlayerAnimator] Loaded {} clips from {}", loaded == null ? 0 : loaded.size(), id);
    }

    public Optional<KeyframeAnimation> getAnimations(ResourceLocation id, String name) {
        Map<String, KeyframeAnimation> animationHashMap = this.animations.get(id);
        if (animationHashMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(animationHashMap.get(name));
    }

    public boolean containsKey(ResourceLocation id) {
        return animations.containsKey(id);
    }

    public void clearAll() {
        animations.clear();
    }
}
