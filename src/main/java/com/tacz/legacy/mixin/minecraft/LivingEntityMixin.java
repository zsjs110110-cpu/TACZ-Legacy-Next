package com.tacz.legacy.mixin.minecraft;

import com.tacz.legacy.api.entity.IGunOperator;
import com.tacz.legacy.api.entity.KnockBackModifier;
import com.tacz.legacy.api.entity.ReloadState;
import com.tacz.legacy.api.entity.ShootResult;
import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.common.entity.shooter.*;
import com.tacz.legacy.common.resource.GunDataAccessor;
import com.tacz.legacy.common.resource.GunCombatData;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * 将 IGunOperator 和 KnockBackModifier 接口注入到所有 EntityLivingBase 上。
 * 对应上游 TACZ 的 LivingEntityMixin。
 */
@Mixin(EntityLivingBase.class)
public abstract class LivingEntityMixin implements IGunOperator, KnockBackModifier {

    @Unique
    private ShooterDataHolder tacz$dataHolder;

    @Unique
    private LivingEntityDrawGun tacz$drawGun;

    @Unique
    private LivingEntityShoot tacz$shoot;

    @Unique
    private LivingEntityReload tacz$reload;

    @Unique
    private LivingEntityAim tacz$aim;

    @Unique
    private LivingEntityZoom tacz$zoom;

    @Unique
    private LivingEntityCrawl tacz$crawl;

    @Unique
    private LivingEntityBolt tacz$bolt;

    @Unique
    private LivingEntityFireSelect tacz$fireSelect;

    @Unique
    private LivingEntityMelee tacz$melee;

    @Unique
    private LivingEntitySprint tacz$sprint;

    @Unique
    private LivingEntityAmmoCheck tacz$ammoCheck;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void tacz$onInit(CallbackInfo ci) {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        tacz$dataHolder = new ShooterDataHolder();
        tacz$drawGun = new LivingEntityDrawGun(self, tacz$dataHolder);
        tacz$shoot = new LivingEntityShoot(self, tacz$dataHolder, tacz$drawGun);
        tacz$reload = new LivingEntityReload(self, tacz$dataHolder, tacz$drawGun);
        tacz$aim = new LivingEntityAim(self, tacz$dataHolder);
        tacz$zoom = new LivingEntityZoom(self, tacz$dataHolder);
        tacz$crawl = new LivingEntityCrawl(self, tacz$dataHolder);
        tacz$bolt = new LivingEntityBolt(self, tacz$dataHolder, tacz$drawGun, tacz$shoot);
        tacz$fireSelect = new LivingEntityFireSelect(self, tacz$dataHolder);
        tacz$melee = new LivingEntityMelee(self, tacz$dataHolder, tacz$drawGun);
        tacz$sprint = new LivingEntitySprint(self, tacz$dataHolder);
        tacz$ammoCheck = new LivingEntityAmmoCheck(self, tacz$dataHolder);
    }

    // ---- IGunOperator ----

    @Override
    public long getSynShootCoolDown() {
        return tacz$shoot.getShootCoolDown();
    }

    @Override
    public long getSynMeleeCoolDown() {
        return tacz$melee.getMeleeCoolDown();
    }

    @Override
    public long getSynDrawCoolDown() {
        return tacz$drawGun.getDrawCoolDown();
    }

    @Override
    public boolean getSynIsBolting() {
        return tacz$dataHolder.isBolting;
    }

    @Override
    public ReloadState getSynReloadState() {
        return tacz$reload.getReloadState();
    }

    @Override
    public float getSynAimingProgress() {
        return tacz$dataHolder.aimingProgress;
    }

    @Override
    public boolean getSynIsAiming() {
        return tacz$dataHolder.isAiming;
    }

    @Override
    public float getSynSprintTime() {
        return tacz$dataHolder.sprintTimeS;
    }

    @Override
    public void initialData() {
        boolean wasCrawling = tacz$dataHolder.isCrawling;
        tacz$dataHolder.initialData();
        // initialData() 会把 isCrawling 复位为 false（切枪/收枪）。复位只发生在服务端，
        // 不回同步的话客户端 holder 会粘在 true：镜头后坐力、散布 lie 档、动画全部仍按
        // 趴下计算，而且后续 ClientMessagePlayerCrawl 因两端"已一致"不再纠正。
        if (wasCrawling && !tacz$dataHolder.isCrawling) {
            EntityLivingBase self = (EntityLivingBase) (Object) this;
            com.tacz.legacy.common.entity.shooter.LivingEntityCrawl.syncReset(self, tacz$dataHolder);
        }
    }

    @Override
    public void draw(Supplier<ItemStack> gunItemSupplier) {
        tacz$drawGun.draw(gunItemSupplier);
    }

    @Override
    public void bolt() {
        tacz$bolt.bolt();
    }

    @Override
    public void reload() {
        tacz$reload.reload();
    }

    @Override
    public void cancelReload() {
        tacz$reload.cancelReload();
    }

    @Override
    public void fireSelect() {
        tacz$fireSelect.fireSelect();
    }

    @Override
    public void aim(boolean isAiming) {
        tacz$aim.aim(isAiming);
    }

    @Override
    public void melee() {
        tacz$melee.melee();
    }

    @Override
    public void zoom() {
        tacz$zoom.zoom();
    }

    @Override
    public void crawl() {
        tacz$crawl.crawl();
    }

    @Override
    public ShootResult shoot(Supplier<Float> pitch, Supplier<Float> yaw, long timestamp, float chargeProgress) {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        if (!self.world.isRemote) {
            // 客户端在本地独立计时换弹/拉栓，服务端则在收到请求后的下一个服务端 tick 才开始计时，
            // 因此服务端总是比客户端晚结束（最多约一个 tick + 网络抖动）。若不处理，客户端认为换弹已结束
            // 并播放开火动画，而服务端以 IS_RELOADING 拒绝，出现“只有动画没有子弹”的幽灵射击。
            // 这里以 SYNC_GRACE_MS 的提前量推进一次状态：若在宽限时间内即可结束，就视为已经结束。
            if (tacz$dataHolder.reloadStateType.isReloading() && tacz$dataHolder.reloadTimestamp > 0) {
                long originalTimestamp = tacz$dataHolder.reloadTimestamp;
                tacz$dataHolder.reloadTimestamp = originalTimestamp - LivingEntityShoot.SYNC_GRACE_MS;
                tacz$reload.tickReload();
                if (tacz$dataHolder.reloadStateType.isReloading()) {
                    tacz$dataHolder.reloadTimestamp = originalTimestamp;
                }
            }
            if (tacz$dataHolder.isBolting && tacz$dataHolder.boltTimestamp > 0) {
                long originalTimestamp = tacz$dataHolder.boltTimestamp;
                tacz$dataHolder.boltTimestamp = originalTimestamp - LivingEntityShoot.SYNC_GRACE_MS;
                tacz$bolt.tickBolt();
                if (tacz$dataHolder.isBolting) {
                    tacz$dataHolder.boltTimestamp = originalTimestamp;
                }
            }
        }
        return tacz$shoot.shoot(pitch, yaw, timestamp, chargeProgress);
    }

    @Override
    public boolean needCheckAmmo() {
        return tacz$ammoCheck.needCheckAmmo();
    }

    @Override
    public boolean consumesAmmoOrNot() {
        return tacz$ammoCheck.consumesAmmoOrNot();
    }

    @Override
    public float getProcessedSprintStatus() {
        return tacz$sprint.getProcessedSprintStatus();
    }

    @Override
    public ShooterDataHolder getDataHolder() {
        return tacz$dataHolder;
    }

    @Override
    public boolean nextBulletIsTracer() {
        return tacz$shoot.nextBulletIsTracer();
    }

    @Override
    public void tick() {
        tacz$reload.tickReload();
        tacz$bolt.tickBolt();
        tacz$aim.tickAimingProgress();
        tacz$melee.tickMelee();
        tacz$sprint.tickSprint();
        tacz$tickHeat();
    }

    @Unique
    private void tacz$tickHeat() {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        // 与上游 TACZ 一致：热量计算只在服务端执行，客户端通过 ItemStack NBT 同步获取
        if (self.world.isRemote) return;
        ItemStack mainHand = self.getHeldItemMainhand();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof IGun)) return;
        IGun iGun = (IGun) mainHand.getItem();
        GunCombatData gunData = GunDataAccessor.getGunData(iGun.getGunId(mainHand));
        if (gunData == null || !gunData.getHasHeatData()) return;

        long heatTs = tacz$dataHolder.heatTimestamp;
        if (heatTs < 0) return;

        // 脚本 hook: tick_heat
        org.luaj.vm2.LuaFunction tickHeatFunc = GunScriptHooks.INSTANCE.find(gunData, "tick_heat");
        if (tickHeatFunc != null) {
            com.tacz.legacy.common.entity.shooter.TACZGunScriptAPI api =
                    com.tacz.legacy.common.entity.shooter.TACZGunScriptAPI.Companion.create(self, tacz$dataHolder, mainHand, null, null);
            try {
                tickHeatFunc.call(
                        org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(api),
                        org.luaj.vm2.LuaValue.valueOf(heatTs)
                );
                return;
            } catch (RuntimeException e) {
                // 脚本错误不应让实体 tick 崩溃，回退到默认冷却逻辑
                GunScriptHooks.INSTANCE.report(gunData, "tick_heat", e);
            }
        }

        long now = System.currentTimeMillis();
        long elapsed = now - heatTs;
        boolean locked = iGun.isOverheatLocked(mainHand);

        if (locked) {
            // 过热锁定：等待 overHeatTime 后开始冷却
            if (elapsed < gunData.getHeatOverHeatTimeMs()) return;
            float cooling = (float) elapsed / 10000.0f * gunData.getHeatCoolingMultiplier();
            float current = iGun.getHeatAmount(mainHand);
            float newHeat = current - cooling;
            iGun.setHeatAmount(mainHand, Math.max(newHeat, 0));
            if (newHeat <= 0) {
                iGun.setOverheatLocked(mainHand, false);
            }
        } else {
            // 正常冷却：等待 coolingDelay 后开始冷却
            float current = iGun.getHeatAmount(mainHand);
            if (current <= 0) return;
            if (elapsed < gunData.getHeatCoolingDelayMs()) return;
            float cooling = (float) elapsed / 10000.0f * gunData.getHeatCoolingMultiplier();
            float newHeat = current - cooling;
            iGun.setHeatAmount(mainHand, Math.max(newHeat, 0));
        }
    }

    // ---- KnockBackModifier ----

    @Override
    public void resetKnockBackStrength() {
        tacz$dataHolder.knockbackStrength = -1.0;
    }

    @Override
    public double getKnockBackStrength() {
        return tacz$dataHolder.knockbackStrength;
    }

    @Override
    public void setKnockBackStrength(double strength) {
        tacz$dataHolder.knockbackStrength = strength;
    }
}
