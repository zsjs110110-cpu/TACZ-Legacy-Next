package com.tacz.legacy.client

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.client.animation.screen.RefitTransform
import com.tacz.legacy.client.audio.TACZClientAudioHooks
import com.tacz.legacy.client.event.FirstPersonRenderGunEvent
import com.tacz.legacy.client.event.LegacyClientHitMarkHandler
import com.tacz.legacy.client.event.LegacyClientInputEventHandler
import com.tacz.legacy.client.event.LegacyClientOverlayEventHandler
import com.tacz.legacy.client.event.TACZCameraRecoilHandler
import com.tacz.legacy.client.foundation.FocusedSmokeClientHooks
import com.tacz.legacy.client.gui.GunSmithTableScreen
import com.tacz.legacy.client.input.LegacyKeyBindings
import com.tacz.legacy.client.renderer.block.GunSmithTableTileEntityRenderer
import com.tacz.legacy.client.renderer.bloom.TACZBloomBridge
import com.tacz.legacy.client.renderer.entity.RenderKineticBullet
import com.tacz.legacy.client.renderer.item.TACZAmmoBoxItemRenderer
import com.tacz.legacy.client.renderer.item.TACZAmmoItemRenderer
import com.tacz.legacy.client.renderer.item.TACZAttachmentItemRenderer
import com.tacz.legacy.client.renderer.item.TACZBlockItemRenderer
import com.tacz.legacy.client.renderer.item.TACZGunItemRenderer
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.common.CommonProxy
import com.tacz.legacy.common.block.entity.GunSmithTableTileEntity
import com.tacz.legacy.common.foundation.BootstrapDiagnostics
import com.tacz.legacy.common.foundation.BootstrapStep
import com.tacz.legacy.common.gui.LegacyGuiIds
import com.tacz.legacy.common.inventory.GunSmithTableContainer
import com.tacz.legacy.common.entity.EntityKineticBullet
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.client.particle.LegacyParticleFactoryRegistry
import com.tacz.legacy.client.registry.ModelRegisterer
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.client.registry.ClientRegistry
import net.minecraftforge.fml.client.registry.IRenderFactory
import net.minecraftforge.fml.client.registry.RenderingRegistry

internal class ClientProxy : CommonProxy() {
    init {
        MinecraftForge.EVENT_BUS.register(ModelRegisterer)
        MinecraftForge.EVENT_BUS.register(ClientConfigEventHandler)
        MinecraftForge.EVENT_BUS.register(TACZClientAudioHooks)
        MinecraftForge.EVENT_BUS.register(LegacyClientHitMarkHandler)
        MinecraftForge.EVENT_BUS.register(LegacyClientInputEventHandler)
        MinecraftForge.EVENT_BUS.register(LegacyClientOverlayEventHandler)
        MinecraftForge.EVENT_BUS.register(FocusedSmokeClientHooks)
        // RefitTransform also uses @JvmStatic handlers on a Kotlin object.
        MinecraftForge.EVENT_BUS.register(RefitTransform::class.java)
        // FirstPersonRenderGunEvent uses @JvmStatic handlers on a Kotlin object.
        // Forge 1.12 only scans static subscribers when registering the class itself.
        MinecraftForge.EVENT_BUS.register(FirstPersonRenderGunEvent::class.java)
        MinecraftForge.EVENT_BUS.register(TACZCameraRecoilHandler::class.java)
        // 兼容层 ③ C2：断开连接时清空远程枪包与未完成的分片，
        // 否则跨会话残留会让客户端静默拼出错误数据、或让远程包永久接管本地同名目录。
        MinecraftForge.EVENT_BUS.register(object {
            @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
            fun onDisconnect(event: net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent) {
                com.tacz.legacy.common.network.message.event.GunPackSyncAssembler.reset()
                val hadRemotePacks = com.tacz.legacy.common.resource.TACZRemotePackStore.snapshot().isNotEmpty()
                com.tacz.legacy.common.resource.TACZRemotePackStore.clear()
                if (!hadRemotePacks) {
                    return
                }
                // 清掉远程包后快照里仍残留着它们解析出的枪/显示定义，必须重建，
                // 否则退回主菜单或进单人存档时还在用上一个服务器的数据。
                // 重建后同样要刷新客户端资产，避免"本地数据 + 远程资产"。
                try {
                    val mc = net.minecraft.client.Minecraft.getMinecraft()
                    com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry.reload(mc.gameDir)
                    this@ClientProxy.onGunPackReloaded()
                } catch (throwable: Throwable) {
                    com.tacz.legacy.TACZLegacy.logger.warn(
                        "[GunPackRuntime] Failed to rebuild snapshot after dropping remote gun packs: {}",
                        throwable.message,
                    )
                }
            }
        })
    }

    override fun preInit(): Unit {
        super.preInit()
        RenderingRegistry.registerEntityRenderingHandler(
            EntityKineticBullet::class.java,
            IRenderFactory { manager -> RenderKineticBullet(manager) },
        )
    }

    override fun init(): Unit {
        super.init()
        LegacyKeyBindings.registerAll()
        LegacyParticleFactoryRegistry.register()

        // Wire TEISR for gun items (requires "parent": "builtin/entity" in item model JSON)
        LegacyItems.MODERN_KINETIC_GUN.setTileEntityItemStackRenderer(TACZGunItemRenderer)
        LegacyItems.AMMO.setTileEntityItemStackRenderer(TACZAmmoItemRenderer)
        LegacyItems.AMMO_BOX.setTileEntityItemStackRenderer(TACZAmmoBoxItemRenderer)
        LegacyItems.ATTACHMENT.setTileEntityItemStackRenderer(TACZAttachmentItemRenderer)
        LegacyItems.GUN_SMITH_TABLE.setTileEntityItemStackRenderer(TACZBlockItemRenderer)
        LegacyItems.WORKBENCH_A.setTileEntityItemStackRenderer(TACZBlockItemRenderer)
        LegacyItems.WORKBENCH_B.setTileEntityItemStackRenderer(TACZBlockItemRenderer)
        LegacyItems.WORKBENCH_C.setTileEntityItemStackRenderer(TACZBlockItemRenderer)

        // World-space TESR for gun smith table / workbench blocks
        ClientRegistry.bindTileEntitySpecialRenderer(GunSmithTableTileEntity::class.java, GunSmithTableTileEntityRenderer())
        ModelRegisterer.registerAmmoBoxPropertyOverride()

        // Load client-side assets (models, textures) from the already-loaded gun pack snapshot
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        TACZClientAssetManager.reload(snapshot)

        // 兼容层 ①：把枪包目录注册为 vanilla 资源包
        // 让 vanilla assets 系统（lang/贴图/声音）能从枪包目录读取，供 JEI 等第三方 mod 使用
        // 对齐上游 GunPackLoader 把枪包接入 PackRepository 的语义
        try {
            val mc = net.minecraft.client.Minecraft.getMinecraft()
            val registered = registerGunPackResourcePacks(mc)
            if (registered > 0) {
                mc.refreshResources()
                TACZLegacy.logger.info("[GunPackRuntime] Registered $registered gun pack(s) as vanilla resource packs")
            }
        } catch (e: Exception) {
            TACZLegacy.logger.warn("[GunPackRuntime] Vanilla resource pack registration skipped: ${e.message}")
        }

        TACZBloomBridge.initIfPresent()
        // 瞄具镜片遮罩需要模板缓冲，必须在渲染开始前启用（渲染中途启用会重建帧缓冲导致画面错乱）
        com.tacz.legacy.client.util.RenderHelper.ensureMainFramebufferStencil()

        BootstrapDiagnostics.record(BootstrapStep.CLIENT_RUNTIME_READY)
        TACZLegacy.logger.info("[FoundationSmoke] CLIENT runtime hooks ready.")
    }

    override fun createClientGuiElement(id: Int, player: EntityPlayer, world: World, pos: BlockPos): Any? {
        return when (id) {
            LegacyGuiIds.GUN_SMITH_TABLE -> GunSmithTableScreen(GunSmithTableContainer(player.inventory, world, pos), player.inventory)
            else -> null
        }
    }

    /**
     * `/tacz reload` 之后重建客户端资产（模型、贴图、动画、Lua 脚本、音效、显示实例），
     * 使改动无需重启游戏即可生效。
     *
     * 命令运行在（集成）服务端线程，而 [TACZClientAssetManager.reload] 会经 [clear] 删除 GL 动态贴图、
     * 并在 GL 上下文中重建模型/贴图，必须在客户端主线程执行，因此用 addScheduledTask 投递。
     * 快照此时已由 exportIfNeeded → TACZGunPackRuntimeRegistry.reload 刷新为最新。
     */
    override fun onGunPackReloaded(): Unit {
        val mc = net.minecraft.client.Minecraft.getMinecraft() ?: return
        mc.addScheduledTask {
            try {
                TACZClientAssetManager.reload(TACZGunPackRuntimeRegistry.getSnapshot())
                TACZLegacy.logger.info("[GunPackRuntime] Client assets rebuilt after /tacz reload.")
            } catch (e: Exception) {
                TACZLegacy.logger.warn("[GunPackRuntime] Client asset rebuild failed: {}", e.message, e)
            }
            // 上面只重建 TACZ 自管的模型/贴图/动画/Lua/音效索引；
            // 接进 vanilla 资源系统的部分（lang、vanilla 侧贴图、FolderResourcePack）需要重新注册。
            // 注意：在这里调用 mc.refreshResources() 会在集成服环境下触发
            //   Minecraft.runGameLoop → MinecraftServer.runScheduledTasks → addScheduledTask(就地执行) 的无限递归，
            //   实测 StackOverflow（2026-10-19 11:22）。因此只重新注册，不自动重载 vanilla 资源，
            //   由玩家按 F3+T（或下次启动）让 lang/贴图生效。
            try {
                val newlyRegistered = registerGunPackResourcePacks(mc)
                if (newlyRegistered > 0) {
                    TACZLegacy.logger.info(
                        "[GunPackRuntime] Registered {} new gun pack(s) as vanilla resource packs; " +
                            "press F3+T to apply lang/texture changes.",
                        newlyRegistered,
                    )
                } else {
                    TACZLegacy.logger.info(
                        "[GunPackRuntime] Vanilla resource packs unchanged; lang/texture edits need F3+T.",
                    )
                }
            } catch (e: Exception) {
                TACZLegacy.logger.warn("[GunPackRuntime] Vanilla resource pack re-registration failed: {}", e.message)
            }
        }
    }

    /**
     * 把 `tacz/` 下每个枪包目录注册为 vanilla 资源包（供 lang/贴图/声音/JEI 使用）。
     *
     * 可重复调用：已注册过的目录按名字去重，因此 init 与每次 `/tacz reload` 都可以安全调用。
     * 通过 `MinecraftInvoker`（mixin accessor）访问 `Minecraft.defaultResourcePacks`，
     * 不再依赖裸反射 —— 之前的 SRG 名写法把字段名写错，导致注册整段被 catch 跳过。
     *
     * @return 本次新增注册的包数量（0 表示没有新包）
     */
    private fun registerGunPackResourcePacks(mc: net.minecraft.client.Minecraft): Int {
        val taczDir = java.io.File(mc.gameDir, TACZLegacy.MOD_ID)
        if (!taczDir.isDirectory) return 0
        val invoker = mc as? com.tacz.legacy.mixin.minecraft.client.MinecraftInvoker ?: return 0
        val packs = invoker.`tacz$getDefaultResourcePacks`()
        val existing = packs.mapNotNull { (it as? TACZFolderResourcePack)?.packDir?.name }.toMutableSet()
        var added = 0
        taczDir.listFiles()?.filter { it.isDirectory }?.forEach { packDir ->
            if (!existing.add(packDir.name)) return@forEach
            try {
                packs.add(TACZFolderResourcePack(packDir))
                added++
            } catch (e: Exception) {
                TACZLegacy.logger.warn(
                    "[GunPackRuntime] Failed to register vanilla resource pack for {}: {}",
                    packDir.name, e.message,
                )
            }
        }
        return added
    }
}

// 兼容层 ①：包装枪包目录为 vanilla FolderResourcePack，让 assets 系统能读取 lang/贴图/声音
private class TACZFolderResourcePack(
    val packDir: java.io.File,
) : net.minecraft.client.resources.FolderResourcePack(packDir) {
    /**
     * 枪包目录没有 `pack.mcmeta`，父类默认实现会 `getInputStream("pack.mcmeta")` 并抛
     * `ResourcePackFileNotFoundException`（每次资源重载都在日志里刷一条）。
     * 这里直接返回 null，让 vanilla 把它当作"无元数据包"处理。
     */
    override fun <T : net.minecraft.client.resources.data.IMetadataSection> getPackMetadata(
        serializer: net.minecraft.client.resources.data.MetadataSerializer,
        sectionName: String,
    ): T? = null
}
