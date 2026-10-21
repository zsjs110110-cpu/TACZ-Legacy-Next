package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.api.item.IGun;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBiped.class)
public abstract class ModelBipedGunPoseMixin {

    @Shadow
    public ModelBiped.ArmPose rightArmPose;

    @Shadow
    public ModelBiped.ArmPose leftArmPose;

    @Inject(method = "setRotationAngles", at = @At("HEAD"))
    private void tacz$forceGunArmPose(
            float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch, float scaleFactor,
            Entity entityIn, CallbackInfo ci
    ) {
        if (!(entityIn instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase living = (EntityLivingBase) entityIn;
        ItemStack mainHand = living.getHeldItemMainhand();
        if (!mainHand.isEmpty() && mainHand.getItem() instanceof IGun) {
            if (living.getPrimaryHand() == EnumHandSide.LEFT) {
                this.leftArmPose = ModelBiped.ArmPose.BOW_AND_ARROW;
            } else {
                this.rightArmPose = ModelBiped.ArmPose.BOW_AND_ARROW;
            }
        }
    }
}