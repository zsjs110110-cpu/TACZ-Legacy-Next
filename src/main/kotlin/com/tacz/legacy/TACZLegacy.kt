package com.tacz.legacy

import com.tacz.legacy.client.ClientProxy
import com.tacz.legacy.command.TaczServerCommand
import com.tacz.legacy.common.CommonProxy
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.foundation.BootstrapDiagnostics
import com.tacz.legacy.common.foundation.BootstrapStep
import com.tacz.legacy.common.resource.DefaultGunPackExporter
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.SidedProxy
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent
import net.minecraftforge.fml.common.event.FMLServerStartingEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

@Mod(
    modid = TACZLegacy.MOD_ID,
    name = TACZLegacy.MOD_NAME,
    version = TACZLegacy.VERSION,
    acceptedMinecraftVersions = "[1.12.2]",
    dependencies = "required-after:forge@[14.23.5.2847,);required-after:forgelin_continuous@[2.3.0.0,)",
    modLanguageAdapter = "io.github.chaosunity.forgelin.KotlinAdapter",
)
public object TACZLegacy {
    public const val MOD_ID: String = Tags.MOD_ID
    public const val MOD_NAME: String = Tags.MOD_NAME
    public const val VERSION: String = Tags.VERSION

    @JvmField
    internal var logger: Logger = LogManager.getLogger(MOD_ID)

    public fun getLogger(): Logger = logger

    @JvmStatic
    @SidedProxy(
        clientSide = "com.tacz.legacy.client.ClientProxy",
        serverSide = "com.tacz.legacy.common.CommonProxy",
    )
    internal lateinit var proxy: CommonProxy

    @Mod.EventHandler
    public fun preInit(event: FMLPreInitializationEvent): Unit {
        logger = event.modLog
        event.modMetadata.version = Tags.VERSION
        BootstrapDiagnostics.reset()

        logger.info("[FoundationSmoke] Starting TACZ-Legacy foundation bootstrap on {}.", event.side)
        BootstrapDiagnostics.record(BootstrapStep.PRELOAD_CONFIG)
        LegacyConfigManager.loadPreloadConfig(event.modConfigurationDirectory)

        BootstrapDiagnostics.record(BootstrapStep.CORE_CONFIG)
        LegacyConfigManager.loadMainConfigs(event)

        BootstrapDiagnostics.record(BootstrapStep.DEFAULT_PACK_EXPORT)
        val exportResult = DefaultGunPackExporter.exportIfNeeded(requireNotNull(LegacyConfigManager.getGameDirectory()))
        logger.info(
            "Default gun pack export: exported={}, skipped={}, target={}, backup={}",
            exportResult.exported,
            exportResult.skipped,
            exportResult.targetDirectory,
            exportResult.backupDirectory,
        )

        BootstrapDiagnostics.record(BootstrapStep.PROXY_PRE_INIT)
        proxy.preInit()
        logger.info("[FoundationSmoke] PREINIT complete.")
    }

    @Mod.EventHandler
    public fun init(event: FMLInitializationEvent): Unit {
        proxy.init()
        BootstrapDiagnostics.record(BootstrapStep.PROXY_INIT)
        logger.info("[FoundationSmoke] INIT complete.")
    }

    @Mod.EventHandler
    public fun postInit(event: FMLPostInitializationEvent): Unit {
        proxy.postInit()
        BootstrapDiagnostics.record(BootstrapStep.PROXY_POST_INIT)
        logger.info("[FoundationSmoke] POSTINIT complete.")
    }

    @Mod.EventHandler
    public fun serverStarting(event: FMLServerStartingEvent): Unit {
        event.registerServerCommand(TaczServerCommand)
        BootstrapDiagnostics.record(BootstrapStep.SERVER_COMMANDS)
        logger.info("Registered /tacz foundation command.")
    }
}
