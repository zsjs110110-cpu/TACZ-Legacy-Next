package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.client.ClientMessagePlayerCrawl
import com.tacz.legacy.common.network.message.event.ServerMessageUpdateEntityData
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.nbt.NBTTagCompound

/**
 * 趴下状态处理。对应上游 TACZ 的 LivingEntityCrawl。
 *
 * 1.12.2 没有 1.20 的 Pose 系统，因此这里只维护 [ShooterDataHolder.isCrawling]：
 * - 后坐力：TACZGunPropertyResolver.resolveCameraRecoil 读取它应用 crawl_recoil_multiplier；
 * - 散布：InaccuracyType 的 lie 档读取它；
 * - 动画：GunAnimationStateContext.isCrawl 读取它。
 * 是否允许趴下由枪械数据 can_crawl 控制（GunCombatData.canCrawl）。
 */
public class LivingEntityCrawl(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
) {
    /**
     * 切换趴下状态。
     * 上游为无参、无返回值的切换动作；按住/切换的输入语义由客户端按键层处理。
     * 没有可用枪械或当前枪械 can_crawl=false 时强制站起，避免残留趴下状态。
     */
    public fun crawl() {
        val held = data.heldGun()
        data.isCrawling = if (held == null || !held.gunData.canCrawl) {
            false
        } else {
            !data.isCrawling
        }
        if (shooter.world.isRemote) {
            // 客户端只做本地预测，权威值必须由服务端维护：把期望状态发给服务端，
            // 服务端比对后翻转并 S2C 回同步（can_crawl=false 等情况会纠正本地预测）。
            // 对齐上游 LocalPlayerCrawl#crawl 的 "本地翻转 + sendToServer" 两步。
            // 只有玩家实体发包：ClientMessagePlayerCrawl 的 handler 固定作用于发包玩家本人。
            if (shooter is EntityPlayer) {
                TACZNetworkHandler.sendToServer(ClientMessagePlayerCrawl(data.isCrawling))
            }
            return
        }
        syncToClients()
    }

    /**
     * 审计 B6-1：状态翻转发生在**服务端** holder 上，而三个消费点里
     * 镜头后座（`TACZCameraRecoilHandler`）与动画状态机（`GunAnimationStateContext`）
     * 读的是**客户端** holder —— 不同步则那两处恒为 false，趴下对镜头/动画零效果，
     * 第三人称与其他玩家更没有读路径。这里用 S2C ID 31 把权威值下发给所有观察者，
     * 并且连发起者本人也发一份，用于纠正本地预测（例如 can_crawl=false 强制站起）。
     */
    private fun syncToClients() {
        if (shooter.world.isRemote) {
            return
        }
        publish(shooter, data.isCrawling)
    }

    public companion object {
        /**
         * 服务端在 holder 被整体复位（`ShooterDataHolder.initialData()`，切枪/收枪时触发）
         * 清掉趴下状态后调用：复位只发生在服务端，若不回同步，客户端 holder 会一直停在
         * true —— 镜头后坐力、散布 lie 档、动画state 全部按趴下计算，且后续 C2S 判重
         * （`ClientMessagePlayerCrawl`）会因两端已"一致"而不再纠正，形成粘滞。
         */
        @JvmStatic
        public fun syncReset(shooter: EntityLivingBase, data: ShooterDataHolder) {
            if (shooter.world.isRemote) {
                return
            }
            publish(shooter, data.isCrawling)
        }

        private fun publish(shooter: EntityLivingBase, crawling: Boolean) {
            val tag = NBTTagCompound()
            tag.setBoolean(ServerMessageUpdateEntityData.TAG_CRAWLING, crawling)
            val message = ServerMessageUpdateEntityData(shooter.entityId, tag)
            TACZNetworkHandler.sendToTrackingEntity(message, shooter)
            // sendToTrackingEntity 不会发给实体自己（若它是玩家），单独补一份给发起者
            (shooter as? EntityPlayerMP)?.let { TACZNetworkHandler.sendToPlayer(message, it) }
        }
    }
}
