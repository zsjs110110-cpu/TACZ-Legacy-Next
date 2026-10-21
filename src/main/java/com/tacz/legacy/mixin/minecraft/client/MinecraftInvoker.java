package com.tacz.legacy.mixin.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourcePack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(Minecraft.class)
public interface MinecraftInvoker {
    @Accessor("defaultResourcePacks")
    List<IResourcePack> tacz$getDefaultResourcePacks();

    @Accessor("leftClickCounter")
    int tacz$getLeftClickCounter();

    @Accessor("leftClickCounter")
    void tacz$setLeftClickCounter(int value);

    @Invoker("clickMouse")
    void tacz$invokeClickMouse();

    @Invoker("sendClickBlockToController")
    void tacz$invokeSendClickBlockToController(boolean leftClick);
}