package com.tacz.legacy.common

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.block.entity.LegacyBlockEntities
import com.tacz.legacy.common.event.GunPackSyncHandler
import com.tacz.legacy.common.event.LegacyMissingMappingHandler
import com.tacz.legacy.common.event.LootInjectionHandler
import com.tacz.legacy.common.event.PreventGunLeftClickHandler
import com.tacz.legacy.common.foundation.FocusedSmokeRuntime
import com.tacz.legacy.common.gui.LegacyGuiHandler
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.registry.BlockRegisterer
import com.tacz.legacy.common.registry.EntityRegisterer
import com.tacz.legacy.common.registry.ItemRegisterer
import com.tacz.legacy.common.registry.SoundRegisterer
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.network.NetworkRegistry

internal open class CommonProxy {
    init {
        MinecraftForge.EVENT_BUS.register(BlockRegisterer)
        MinecraftForge.EVENT_BUS.register(ItemRegisterer)
        MinecraftForge.EVENT_BUS.register(EntityRegisterer)
        MinecraftForge.EVENT_BUS.register(SoundRegisterer)
        MinecraftForge.EVENT_BUS.register(LegacyMissingMappingHandler)
        MinecraftForge.EVENT_BUS.register(PreventGunLeftClickHandler)
        MinecraftForge.EVENT_BUS.register(ShooterTickHandler)
        MinecraftForge.EVENT_BUS.register(FocusedSmokeRuntime)
        // 兼容层 ③ C13：枪包战利品注入（LootTableLoadEvent）
        MinecraftForge.EVENT_BUS.register(LootInjectionHandler)
        // 兼容层 ③ C2：玩家登录时同步枪包数据
        MinecraftForge.EVENT_BUS.register(GunPackSyncHandler)
    }

    internal open fun preInit(): Unit {
        LegacyBlockEntities.registerAll()
        TACZNetworkHandler.init()
        NetworkRegistry.INSTANCE.registerGuiHandler(TACZLegacy, LegacyGuiHandler)
    }

    internal open fun init(): Unit = Unit

    internal open fun postInit(): Unit = Unit

    /**
     * 枪包在 `/tacz reload` 后重建客户端资产的回调。
     * 服务端（专用服）无客户端，默认空实现；由 ClientProxy 覆盖，在客户端主线程重建模型/贴图/脚本。
     */
    internal open fun onGunPackReloaded(): Unit = Unit

    internal open fun createClientGuiElement(id: Int, player: EntityPlayer, world: World, pos: BlockPos): Any? = null
}
