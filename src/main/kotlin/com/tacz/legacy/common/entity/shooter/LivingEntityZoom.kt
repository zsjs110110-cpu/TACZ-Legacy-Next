package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.item.IAttachment
import com.tacz.legacy.api.item.attachment.AttachmentType
import net.minecraft.entity.EntityLivingBase

/**
 * 瞄具倍率切换。对应上游 TACZ 的 LivingEntityZoom。
 *
 * 上游 zoom() 为无参、无返回值的动作方法：按下倍镜切换键时，把当前瞄具的
 * zoom_number 推进一档。本移植的倍率存放在瞄具物品 NBT 中
 * （IAttachment#getZoomNumber / setZoomNumber），渲染
 * （FirstPersonRenderMatrices / FirstPersonFovHooks）与 IGun#getAimingZoom
 * 都从该 NBT 读取，并用 floorMod 对档位数取模，因此这里直接 +1 就能循环切换。
 */
public class LivingEntityZoom(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
) {
    /**
     * 切换到下一档倍率。装有瞄具时生效；没有瞄具时保持原状。
     * 不在方法内校验是否正在开镜：按键层决定何时发送切换请求，
     * 客户端/服务端都调用同一方法才能保证两边 NBT 档位一致。
     */
    public fun zoom() {
        val held = data.heldGun() ?: return
        // getAttachment 返回的是 NBT 副本，直接改副本不会写回枪；
        // 必须把加一后的瞄具栈重新 install 回枪 NBT。
        val scopeStack = held.iGun.getAttachment(held.stack, AttachmentType.SCOPE)
        val scope = IAttachment.getIAttachmentOrNull(scopeStack) ?: return
        val updated = scopeStack.copy()
        scope.setZoomNumber(updated, scope.getZoomNumber(updated) + 1)
        held.iGun.installAttachment(held.stack, updated)
    }
}
