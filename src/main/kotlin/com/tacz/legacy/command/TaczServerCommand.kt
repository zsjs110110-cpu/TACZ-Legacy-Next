package com.tacz.legacy.command

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.config.ServerToggleKey
import com.tacz.legacy.common.foundation.TaczDebugState
import com.tacz.legacy.common.item.GunTooltipPart
import com.tacz.legacy.common.resource.DefaultGunPackExporter
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZPackConvertor
import net.minecraft.command.CommandBase
import net.minecraft.command.CommandException
import net.minecraft.command.ICommandSender
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextComponentString
import net.minecraft.util.text.TextComponentTranslation
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.Locale

internal object TaczServerCommand : CommandBase() {
    private val toggleNameMap: Map<String, ServerToggleKey> = mapOf(
        "default_table_limit" to ServerToggleKey.DEFAULT_TABLE_LIMIT,
        "server_shoot_network_check" to ServerToggleKey.SERVER_SHOOT_NETWORK_CHECK,
        "server_shoot_cooldown_check" to ServerToggleKey.SERVER_SHOOT_COOLDOWN_CHECK,
    )

    override fun getName(): String = "tacz"

    override fun getUsage(sender: ICommandSender): String =
        "/tacz reload | overwrite <true|false> | config <default_table_limit|server_shoot_network_check|server_shoot_cooldown_check> <true|false> | debug <true|false> | attachment_lock [player] <true|false> | dummy [player] <amount> | hide_tooltip_part [player] <mask> | list_pack | issues [page] | convert"

    override fun getRequiredPermissionLevel(): Int = 2

    override fun execute(server: MinecraftServer, sender: ICommandSender, args: Array<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(TextComponentString(getUsage(sender)))
            return
        }

        when (args[0].lowercase(Locale.ROOT)) {
            "reload" -> handleReload(sender)
            "overwrite" -> handleOverwrite(sender, args)
            "config" -> handleConfig(sender, args)
            "debug" -> handleDebug(sender, args)
            "attachment_lock" -> handleAttachmentLock(server, sender, args)
            "dummy" -> handleDummyAmmo(server, sender, args)
            "hide_tooltip_part" -> handleHideTooltipPart(server, sender, args)
            "list_pack" -> handleListPacks(sender)
            "issues" -> handleIssues(sender, args)
            "convert" -> handleConvert(sender)
            else -> sender.sendMessage(TextComponentString(getUsage(sender)))
        }
    }

    override fun getTabCompletions(
        server: MinecraftServer,
        sender: ICommandSender,
        args: Array<String>,
        targetPos: BlockPos?
    ): MutableList<String> {
        if (args.isEmpty()) {
            return mutableListOf()
        }
        if (args.size == 1) {
            return getListOfStringsMatchingLastWord(
                args,
                listOf("reload", "overwrite", "config", "debug", "attachment_lock", "dummy", "hide_tooltip_part", "list_pack", "issues", "convert")
            )
        }
        if (args.size == 2 && (args[0].equals("overwrite", true) || args[0].equals("debug", true))) {
            return getListOfStringsMatchingLastWord(args, listOf("true", "false"))
        }
        if (args.size == 2 && args[0].equals("config", true)) {
            return getListOfStringsMatchingLastWord(args, toggleNameMap.keys)
        }
        if (args.size == 3 && args[0].equals("config", true)) {
            return getListOfStringsMatchingLastWord(args, listOf("true", "false"))
        }
        if (args.size == 2 && (args[0].equals("attachment_lock", true) || args[0].equals("dummy", true) || args[0].equals("hide_tooltip_part", true))) {
            return getListOfStringsMatchingLastWord(args, server.onlinePlayerNames.toList())
        }
        if (args.size == 3 && args[0].equals("attachment_lock", true)) {
            return getListOfStringsMatchingLastWord(args, listOf("true", "false"))
        }
        return mutableListOf()
    }

    private fun handleReload(sender: ICommandSender): Unit {
        val startedAt = System.nanoTime()
        LegacyConfigManager.reloadAll()
        val exportResult = LegacyConfigManager.getGameDirectory()?.let(DefaultGunPackExporter::ensureInstalled)
        // 快照/数据脚本已由 ensureInstalled → TACZGunPackRuntimeRegistry.reload 刷新；
        // 再触发客户端资产重建（专用服为空实现，集成/客户端在 main 线程重建模型、贴图、脚本）。
        com.tacz.legacy.TACZLegacy.proxy.onGunPackReloaded()
        // 兼容层 ③ C2：把刷新后的枪包数据同步给在线客户端
        com.tacz.legacy.common.event.GunPackSyncHandler.broadcast()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        sender.sendMessage(TextComponentTranslation("commands.tacz.reload.success", String.format(Locale.ROOT, "%.3f", elapsedMs)))
        // 战利品注入只在 LootTableLoadEvent（表首次加载）时执行，reload 不会重注入已加载的表。
        // 不提示的话，改完 injector 的包作者会以为 reload 没生效，而日志显示的是"成功"。
        sender.sendMessage(TextComponentTranslation("commands.tacz.reload.loot_needs_world_reload"))
        if (exportResult?.reinstallFailed == true) {
            // 重装失败和"跳过导出"是两回事：前者默认包可能已经不完整，必须让玩家
            // 知道要重启，而不是读起来像"一切正常"。
            sender.sendMessage(TextComponentTranslation("commands.tacz.reload.reinstall_failed"))
        } else if (exportResult?.skipped == true) {
            // 用的是"已存在、跳过导出"的专用文案：overwrite_off 讲的是覆盖开关，
            // 与"目录已存在所以这次没重导出"不是一回事（X-4）。
            sender.sendMessage(TextComponentTranslation("commands.tacz.reload.skipped_needs_restart"))
        }
    }

    private fun handleOverwrite(sender: ICommandSender, args: Array<String>): Unit {
        if (args.size < 2) {
            sender.sendMessage(TextComponentString(getUsage(sender)))
            return
        }
        val enabled = parseBoolean(args[1])
        LegacyConfigManager.setOverwriteEnabled(enabled)
        sender.sendMessage(
            TextComponentTranslation(
                if (enabled) "commands.tacz.reload.overwrite_on" else "commands.tacz.reload.overwrite_off"
            )
        )
    }

    private fun handleConfig(sender: ICommandSender, args: Array<String>): Unit {
        if (args.size < 3) {
            sender.sendMessage(TextComponentString(getUsage(sender)))
            return
        }
        val key = toggleNameMap[args[1].lowercase(Locale.ROOT)]
        if (key == null) {
            sender.sendMessage(TextComponentString(getUsage(sender)))
            return
        }
        val enabled = parseBoolean(args[2])
        LegacyConfigManager.applyServerToggle(key, enabled)
        sender.sendMessage(TextComponentTranslation("${key.translationKey}.${if (enabled) "enabled" else "disabled"}"))
    }

    private fun handleDebug(sender: ICommandSender, args: Array<String>): Unit {
        if (args.size < 2) {
            sender.sendMessage(TextComponentString(getUsage(sender)))
            return
        }
        TaczDebugState.enabled = parseBoolean(args[1])
        sender.sendMessage(TextComponentString("[TACZ] debug ${if (TaczDebugState.enabled) "enabled" else "disabled"}."))
    }

    private fun handleAttachmentLock(server: MinecraftServer, sender: ICommandSender, args: Array<String>): Unit {
        val (player, valueIndex) = resolvePlayer(server, sender, args, defaultValueIndex = 1)
        if (args.size <= valueIndex) {
            sender.sendMessage(TextComponentString(getUsage(sender)))
            return
        }
        val locked = parseBoolean(args[valueIndex])
        withHeldGun(player, sender) { gunStack, gun ->
            gun.setAttachmentLock(gunStack, locked)
            sender.sendMessage(TextComponentString("[TACZ] set attachment lock=${locked} for ${player.name}."))
        }
    }

    private fun handleDummyAmmo(server: MinecraftServer, sender: ICommandSender, args: Array<String>): Unit {
        val (player, valueIndex) = resolvePlayer(server, sender, args, defaultValueIndex = 1)
        if (args.size <= valueIndex) {
            sender.sendMessage(TextComponentString(getUsage(sender)))
            return
        }
        val amount = parseInt(args[valueIndex], 0)
        withHeldGun(player, sender) { gunStack, gun ->
            gun.setDummyAmmoAmount(gunStack, amount)
            sender.sendMessage(TextComponentString("[TACZ] set dummy ammo=${amount} for ${player.name}."))
        }
    }

    private fun handleHideTooltipPart(server: MinecraftServer, sender: ICommandSender, args: Array<String>): Unit {
        val (player, valueIndex) = resolvePlayer(server, sender, args, defaultValueIndex = 1)
        if (args.size <= valueIndex) {
            sender.sendMessage(TextComponentString(getUsage(sender)))
            return
        }
        val mask = parseInt(args[valueIndex], 0)
        withHeldGun(player, sender) { gunStack, _ ->
            GunTooltipPart.setHideFlags(gunStack, mask)
            sender.sendMessage(TextComponentString("[TACZ] set tooltip mask=${mask} for ${player.name}."))
        }
    }

    /**
     * B10（X-47）：列出当前运行时快照里已加载的枪包。
     * 数据来自服务端快照，专用服上只反映服务端自己的包；集成服与客户端共用同一份扫描结果。
     */
    private fun handleListPacks(sender: ICommandSender): Unit {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        if (snapshot.packs.isEmpty()) {
            sender.sendMessage(TextComponentString("[TACZ] no gun pack loaded."))
            return
        }
        sender.sendMessage(TextComponentString("[TACZ] loaded ${snapshot.packs.size} gun pack(s):"))
        snapshot.packs.toSortedMap().forEach { (packKey, pack) ->
            // packs 的 key 是包目录/zip 文件名，不是命名空间；命名空间只能来自包内 gunpack_info。
            // 一个 zip 可以同时提供多个命名空间，此时按 infoByNamespace 逐条展开。
            if (pack.infoByNamespace.isEmpty()) {
                // B10：没有 gunpack_info 时没有可用的命名空间。若拿 packKey 去 guns.keys 里按
                // namespace 计数，结果恒为 0，会把"info 没解析出来"误报成"包里没有枪/配件"。
                // 因此这里只显示包名并明确标注，不做任何计数。
                sender.sendMessage(
                    TextComponentString("- $packKey (pack_info not parsed) [${pack.sourceFile.name}]")
                )
                return@forEach
            }
            pack.infoByNamespace.toSortedMap().forEach { (namespace, info) ->
                // 计数是按 namespace 在全局快照里统计的：同一 namespace 被多个包提供时，
                // 每个包对应的这一行都会显示该 namespace 的全局总数（现状，未按包拆分归属）。
                val gunCount = snapshot.guns.keys.count { it.namespace == namespace }
                val attachmentCount = snapshot.attachments.keys.count { it.namespace == namespace }
                sender.sendMessage(
                    TextComponentString(
                        "- $namespace \"${info.name}\" v${info.version} [${pack.sourceFile.name}] guns=$gunCount attachments=$attachmentCount"
                    )
                )
            }
        }
        if (snapshot.issues.isNotEmpty()) {
            sender.sendMessage(TextComponentString("[TACZ] ${snapshot.issues.size} issue(s); run /tacz issues to inspect."))
        }
    }

    /**
     * B10（D-24）：输出当前快照的 issues（原先只写日志，游戏内无法查询）。
     * 支持分页，避免一个破损包产生上百条 issue 时刷屏。
     */
    private fun handleIssues(sender: ICommandSender, args: Array<String>): Unit {
        val issues = TACZGunPackRuntimeRegistry.getSnapshot().issues
        if (issues.isEmpty()) {
            sender.sendMessage(TextComponentString("[TACZ] no gun pack issues."))
            return
        }
        val pageSize = 10
        val pageCount = (issues.size + pageSize - 1) / pageSize
        val page = if (args.size >= 2) parseInt(args[1], 1).coerceAtMost(pageCount) else 1
        sender.sendMessage(TextComponentString("[TACZ] gun pack issues: ${issues.size} (page $page/$pageCount)"))
        val start = (page - 1) * pageSize
        val end = minOf(start + pageSize, issues.size)
        for (index in start until end) {
            sender.sendMessage(TextComponentString("${index + 1}. ${issues[index]}"))
        }
        if (pageCount > 1) {
            sender.sendMessage(TextComponentString("[TACZ] usage: /tacz issues <1-$pageCount>"))
        }
    }

    /**
     * B10（X-47）：把旧格式枪包（zip 内为 `<namespace>/pack.json` 布局）转换为当前格式。
     *
     * 上游把旧包放在 `config/tacz/custom`；本移植的扫描根是 `<gameDir>/tacz`。
     * 两处都扫描、统一输出到扫描根，转换后 `/tacz reload` 即可加载。
     * 目录格式（未压缩）旧包无法转换，保持原样跳过，并在发现时给出可读提示。
     */
    private fun handleConvert(sender: ICommandSender): Unit {
        val gameDirectory = LegacyConfigManager.getGameDirectory()
        if (gameDirectory == null) {
            sender.sendMessage(TextComponentString("[TACZ] game directory is not available yet."))
            return
        }
        val targetDirectory = File(gameDirectory, TACZLegacy.MOD_ID)
        val sourceDirectories = listOf(
            File(gameDirectory, TACZLegacy.MOD_ID),
            File(gameDirectory, TACZPackConvertor.LEGACY_PACK_FOLDER.toString()),
        ).distinctBy { it.absoluteFile.path }

        val startedAt = System.nanoTime()
        var converted = 0
        var skipped = 0
        var failed = 0
        val archives = sourceDirectories.flatMap { directory ->
            directory.listFiles { file -> file.isFile && file.name.endsWith(".zip", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                .orEmpty()
                .toList()
        }
        // B10：目录格式旧包（未压缩的 `<namespace>/pack.json` 布局）不在上面的 *.zip 枚举里，
        // 本命令不实现目录转换，但不能静默跳过：给出一条可读提示，与 converted=0 区分开。
        val legacyDirectories = sourceDirectories.flatMap { directory ->
            directory.listFiles { file -> file.isDirectory }
                ?.filter { packDirectory ->
                    packDirectory.listFiles { child -> child.isDirectory }
                        ?.any { namespaceDirectory -> File(namespaceDirectory, "pack.json").isFile } == true
                }
                .orEmpty()
        }
        if (legacyDirectories.isNotEmpty()) {
            sender.sendMessage(
                TextComponentString(
                    "[TACZ] note: ${legacyDirectories.size} directory-format legacy pack(s) skipped " +
                        "(only *.zip is converted; a directory pack is a folder containing <namespace>/pack.json): " +
                        legacyDirectories.joinToString { it.name }
                )
            )
        }
        archives.forEach { archive ->
            val readResult = runCatching { TACZPackConvertor.fromZipFile(archive) }
            readResult.exceptionOrNull()?.let { throwable ->
                failed++
                TACZLegacy.logger.warn("[TACZ] failed to read legacy gun pack {}", archive.absolutePath, throwable)
            }
            val legacyPack = readResult.getOrNull()
            if (legacyPack == null) {
                // 不是旧格式（没有 <namespace>/pack.json）才算 skipped；读取失败已计入 failed。
                if (readResult.isSuccess) {
                    skipped++
                }
                return@forEach
            }
            try {
                legacyPack.convertTo(targetDirectory)
                converted++
            } catch (_: FileAlreadyExistsException) {
                skipped++
            } catch (throwable: Throwable) {
                // convertTo 只写同目录临时文件，失败时已在内部删除临时文件并重新抛出；
                // 这里无需（也不应）删除目标文件——目标要么不存在，要么是上一次的完整包。
                failed++
                TACZLegacy.logger.warn("[TACZ] failed to convert legacy gun pack {}", archive.absolutePath, throwable)
            }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        if (archives.isEmpty()) {
            sender.sendMessage(
                TextComponentString(
                    "[TACZ] no legacy pack (*.zip with <namespace>/pack.json) found under " +
                        sourceDirectories.joinToString { it.absolutePath }
                )
            )
            return
        }
        sender.sendMessage(
            TextComponentString(
                String.format(
                    Locale.ROOT,
                    "[TACZ] convert finished in %.1f ms: converted=%d, skipped=%d (not legacy or target exists), failed=%d. Target: %s",
                    elapsedMs,
                    converted,
                    skipped,
                    failed,
                    targetDirectory.absolutePath,
                )
            )
        )
        if (converted > 0) {
            sender.sendMessage(TextComponentString("[TACZ] run /tacz reload to load the converted pack(s)."))
        }
    }

    private fun resolvePlayer(
        server: MinecraftServer,
        sender: ICommandSender,
        args: Array<String>,
        defaultValueIndex: Int,
    ): Pair<EntityPlayerMP, Int> {
        if (args.size >= defaultValueIndex + 2) {
            return getPlayer(server, sender, args[1]) to (defaultValueIndex + 1)
        }
        val commandPlayer = sender.commandSenderEntity as? EntityPlayerMP
            ?: throw CommandException("commands.generic.player.unspecified")
        return commandPlayer to defaultValueIndex
    }

    private fun withHeldGun(
        player: EntityPlayerMP,
        sender: ICommandSender,
        operation: (ItemStack, com.tacz.legacy.api.item.IGun) -> Unit,
    ): Unit {
        val stack = player.heldItemMainhand
        val gun = com.tacz.legacy.api.item.IGun.getIGunOrNull(stack)
        if (stack.isEmpty || gun == null) {
            sender.sendMessage(TextComponentString("[TACZ] ${player.name} is not holding a TACZ gun."))
            return
        }
        operation(stack, gun)
    }
}
