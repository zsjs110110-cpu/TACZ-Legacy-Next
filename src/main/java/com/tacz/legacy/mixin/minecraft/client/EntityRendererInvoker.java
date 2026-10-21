package com.tacz.legacy.mixin.minecraft.client;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityRenderer.class)
public interface EntityRendererInvoker {
    @Invoker("getFOVModifier")
    float tacz$invokeGetFOVModifier(float partialTicks, boolean useFOVSetting);

    @Invoker("hurtCameraEffect")
    void tacz$invokeHurtCameraEffect(float partialTicks);

    @Invoker("applyBobbing")
    void tacz$invokeApplyBobbing(float partialTicks);

    @Accessor("farPlaneDistance")
    float tacz$getFarPlaneDistance();
}