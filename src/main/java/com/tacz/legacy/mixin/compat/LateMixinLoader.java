package com.tacz.legacy.mixin.compat;

import net.minecraftforge.fml.common.Loader;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

public class LateMixinLoader implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        if (Loader.isModLoaded("mousetweaks")) {
            return Collections.singletonList("mixins.tacz.mousetweaks.json");
        }
        return Collections.emptyList();
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        return true;
    }
}