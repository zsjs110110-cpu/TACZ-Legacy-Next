package com.tacz.legacy.common.resource

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.resource.ResourceManager
import com.tacz.legacy.common.config.LegacyConfigManager
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator

internal data class ExportResult(
    val exported: Boolean,
    val skipped: Boolean,
    val targetDirectory: File,
    val backupDirectory: File?,
    /** 运行中重装默认包失败（备份目录里可能有一份完整副本，需要重启修复）。 */
    val reinstallFailed: Boolean = false,
)

internal object DefaultGunPackExporter {
    internal const val DEFAULT_PACK_NAME: String = "tacz_default_gun"
    private const val RESOURCE_ROOT: String = "assets/tacz/custom/tacz_default_gun"

    /** 默认包根目录必然存在的标记文件，用来判断目录是否被解压完整（X-7 / item 2）。 */
    private const val PACK_ROOT_MARKER: String = "gunpack.meta.json"

    /**
     * 备份目录里保留的份数（含刚刚创建的这一份）。
     *
     * 每次导出都会把旧目录整体移到 `config/tacz/backup/<包名>-<时间戳>`。
     * 不清理的话，一份约 70 MB 的默认包会在每次启动时留下新副本：
     * 本机记录到过 172 份、11.9 GB。安装成功后旧副本已经没有用处
     * （失败路径会在同一次调用内用掉当前这份），因此只留最近两份。
     */
    private const val RETAINED_BACKUPS: Int = 2

    /**
     * 导出默认枪包。
     *
     * 用 `@Synchronized` 串行化：这个方法会写固定名字的 staging 目录
     * （`<pack>.staging`），启动路径与 `/tacz reload` 并发进入时会互相踩掉对方
     * 正在复制的半成品（X-7）。方法本身可重入（[ensureInstalled] 会回调它）。
     */
    @Synchronized
    internal fun exportIfNeeded(gameDirectory: File, force: Boolean = false): ExportResult {
        val taczDirectory = File(gameDirectory, TACZLegacy.MOD_ID).apply { mkdirs() }
        val targetDirectory = File(taczDirectory, DEFAULT_PACK_NAME)
        val overwrite = LegacyConfigManager.shouldOverwriteDefaultPack()

        if (targetDirectory.exists()) {
            if (!force && !overwrite) {
                exportRegisteredResources(taczDirectory, overwrite)
                TACZGunPackRuntimeRegistry.reload(gameDirectory)
                // 跳过安装不等于没有垃圾可清：老版本每次启动都会重新导出并留一份备份，
                // 本机记录到 172 份 / 11.9 GB。这里顺手清一次，已有安装下次启动就自动减肥。
                pruneStaleBackups(backupRoot(), DEFAULT_PACK_NAME)
                return ExportResult(exported = false, skipped = true, targetDirectory = targetDirectory, backupDirectory = null)
            }
        }

        // 先把资源复制到临时目录，成功后再替换目标目录；失败时目标目录保持不变。
        // 覆盖已有目录前会生成备份，并把实际备份路径返回。
        val backupDirectory = installPack(targetDirectory, RESOURCE_ROOT, javaClass.classLoader)
        exportRegisteredResources(taczDirectory, overwrite)
        TACZGunPackRuntimeRegistry.reload(gameDirectory)
        return ExportResult(exported = true, skipped = false, targetDirectory = targetDirectory, backupDirectory = backupDirectory)
    }

    /**
     * 只在默认枪包缺失（或残缺）时安装；已存在时只刷新注册资源与运行时快照。
     *
     * `/tacz reload` 走这条路径（[exportIfNeeded] 留给启动期）。每次 reload 都把
     * 75 MB / 2939 个文件的内置默认包整棵重新解包会占住服务端线程数秒
     * （日志表现为 `Can't keep up! Running ~3776ms behind`），也是"包越多 reload
     * 越慢"的线性项来源（R-03 / B9 诊断）。
     *
     * 覆盖已存在目录属于**启动期**策略：是否覆盖仍由 `DefaultPackDebug` 决定，
     * 由 [exportIfNeeded] 在客户端/专用服启动时执行。默认包被玩家改坏时重启一次
     * 即可修复，不需要每次 reload 都付 75 MB 的复制代价。
     *
     * 只检查目录存在是不够的：进程在 `Files.move` 回退路径上被杀、或玩家删掉子树，
     * 会留下"目录在但内容不全"的状态，此时静默跳过会让默认包一直坏到下次重启。
     * 因此用根标记文件做一次廉价完整性探针，缺失就走强制重装。
     */
    @Synchronized
    internal fun ensureInstalled(gameDirectory: File): ExportResult {
        val taczDirectory = File(gameDirectory, TACZLegacy.MOD_ID).apply { mkdirs() }
        val targetDirectory = File(taczDirectory, DEFAULT_PACK_NAME)
        if (targetDirectory.exists()) {
            if (!File(targetDirectory, PACK_ROOT_MARKER).isFile) {
                TACZLegacy.logger.warn(
                    "[GunPackRuntime] Default gun pack at {} is missing {}; reinstalling it",
                    targetDirectory,
                    PACK_ROOT_MARKER,
                )
                // 运行中重装要整棵替换一个正被资源包 / 音频流打开的目录，Windows 上
                // 会抛 AccessDenied；而 /tacz reload 的调用方没有兜底，异常会让整条命令
                // 中断。这里退化成"本次 reload 放弃修复、保持现状"，玩家重启一次即可。
                //
                // 注意 installPack 的失败路径是先备份移走、再替换；move 失败时目标目录
                // 可能是空的或半残的。此时**不能**把它注册进运行时快照，否则比修复前更糟
                // （原本内存里还有一份可用的包）。所以 catch 里重新探一次标记文件：
                // 完整才刷新，不完整就保留上一份快照并报失败。
                return try {
                    exportIfNeeded(gameDirectory, force = true)
                } catch (e: Throwable) {
                    if (e is VirtualMachineError || e is ThreadDeath) {
                        throw e
                    }
                    TACZLegacy.logger.warn(
                        "[GunPackRuntime] Could not reinstall the default gun pack at {} while the game is running; " +
                            "restart the game to repair it",
                        targetDirectory,
                        e,
                    )
                    val intact = File(targetDirectory, PACK_ROOT_MARKER).isFile
                    if (intact) {
                        // installPack 的失败路径要么没动目标目录、要么已经把备份恢复回来，
                        // 两种情况磁盘上都是完整的一份。这时刷新快照就是"修好了"，
                        // 不能再报"沿用旧快照、请重启"——那个提示只在下面那条分支成立。
                        exportRegisteredResources(taczDirectory, overwrite = false)
                        TACZGunPackRuntimeRegistry.reload(gameDirectory)
                        return ExportResult(
                            exported = true,
                            skipped = false,
                            targetDirectory = targetDirectory,
                            backupDirectory = null,
                        )
                    }
                    TACZLegacy.logger.warn(
                        "[GunPackRuntime] The default gun pack at {} is incomplete after the failed reinstall; " +
                            "keeping the previous runtime snapshot until the game restarts. " +
                            "Note: only the decoded gameplay data survives - models/textures and gun audio are " +
                            "re-read from disk on the next asset rebuild, so they stay missing until then",
                        targetDirectory,
                    )
                    ExportResult(
                        exported = false,
                        skipped = false,
                        targetDirectory = targetDirectory,
                        backupDirectory = null,
                        reinstallFailed = true,
                    )
                }
            }
            exportRegisteredResources(taczDirectory, overwrite = false)
            TACZGunPackRuntimeRegistry.reload(gameDirectory)
            return ExportResult(
                exported = false,
                skipped = true,
                targetDirectory = targetDirectory,
                backupDirectory = null,
            )
        }
        return exportIfNeeded(gameDirectory)
    }

    /**
     * 将枪包资源安全地安装到 [targetDirectory]：
     * 1. 复制到目标目录旁的临时（staging）目录；
     * 2. 复制失败 → 仅清理临时目录，原目标目录不受影响；
     * 3. 复制成功 → 把旧目录整体移动到备份目录（若存在），再用临时目录替换；
     * 4. 任一步失败 → 把已完成的备份恢复回原位；恢复失败会记录备份路径与异常，不再静默吞掉。
     * 返回实际备份目录（无旧目录时为 null）。
     */
    private fun installPack(targetDirectory: File, resourceRoot: String, classLoader: ClassLoader): File? {
        val stagingDirectory = File(targetDirectory.parentFile, "${targetDirectory.name}.staging")
        deleteDirectory(stagingDirectory.toPath())
        stagingDirectory.mkdirs()
        try {
            copyBundledPackTo(stagingDirectory.toPath(), resourceRoot, classLoader)
        } catch (throwable: Throwable) {
            // 复制失败：原目标目录未被触碰，仅清理临时目录
            deleteDirectory(stagingDirectory.toPath())
            throw throwable
        }

        // 备份与安装纳入同一恢复流程：备份阶段跨卷回退时的递归删除也可能在中途失败，
        // 因此它同样位于这个 try 内，异常时会把已完成的备份恢复回原位。
        var backupDirectory: File? = null
        try {
            if (targetDirectory.exists()) {
                backupDirectory = backupExistingDirectory(targetDirectory)
            }
            moveDirectory(stagingDirectory.toPath(), targetDirectory.toPath())
        } catch (throwable: Throwable) {
            // 替换失败：把备份移回原位；恢复失败必须记录，不能静默吞掉
            if (backupDirectory != null) {
                restoreBackup(backupDirectory, targetDirectory)
            }
            deleteDirectory(stagingDirectory.toPath())
            throw throwable
        }
        // 安装成功，旧副本不再需要：只留最近几份，避免 backup 目录无限增长。
        // 放在成功路径之后，失败时仍需靠当前这份恢复，不能提前删。
        pruneStaleBackups(backupDirectory?.parentFile, DEFAULT_PACK_NAME)
        return backupDirectory
    }

    /** 默认包的备份根目录（`config/tacz/backup`）；配置目录不可用时返回 null。 */
    private fun backupRoot(): File? = LegacyConfigManager.getModConfigDirectory()?.let { File(it, "backup") }

    /**
     * 清理 [backupRoot] 里属于 [packName] 的过旧备份，只保留 [RETAINED_BACKUPS] 份。
     *
     * 同一目录下可能有多个包的备份，因此按「包名前缀」过滤，
     * 不用「备份根目录下所有子目录」——那种写法会把别的包的备份也删掉。
     * 清理失败只记日志：残留不影响正确性，下次启动还会再尝试。
     */
    private fun pruneStaleBackups(backupRoot: File?, packName: String) {
        if (backupRoot == null || !backupRoot.isDirectory) {
            return
        }
        val prefix = "$packName-"
        val siblings = backupRoot.listFiles { file -> file.isDirectory && file.name.startsWith(prefix) } ?: return
        if (siblings.size <= RETAINED_BACKUPS) {
            return
        }
        val ordered = siblings.sortedBy { it.lastModified() }
        val stale = ordered.dropLast(RETAINED_BACKUPS)
        var removed = 0
        for (old in stale) {
            runCatching { deleteDirectory(old.toPath()) }.onSuccess {
                removed++
            }.onFailure { failure ->
                TACZLegacy.logger.warn(
                    "Unable to delete the stale gun pack backup {}; it will be retried on the next export.",
                    old,
                    failure,
                )
            }
        }
        if (removed > 0) {
            TACZLegacy.logger.info(
                "[GunPackRuntime] Pruned {} stale backup(s) of {}, kept the {} most recent.",
                removed,
                packName,
                RETAINED_BACKUPS,
            )
        }
    }

    /**
     * 把 [targetDirectory] 整体移动到备份目录。优先使用同卷移动（改名，可逆）；
     * 跨卷等移动失败的情况回退为「复制 + 删除」，且删除失败时会把已完成的备份恢复回原位再抛出，
     * 避免调用方在「原目录残缺且拿不到备份路径」的状态下继续。
     */
    private fun backupExistingDirectory(targetDirectory: File): File {
        val backupRoot = File(requireNotNull(LegacyConfigManager.getModConfigDirectory()), "backup").apply { mkdirs() }
        val backupDirectory = File(backupRoot, "${targetDirectory.name}-${System.currentTimeMillis()}")
        try {
            moveDirectory(targetDirectory.toPath(), backupDirectory.toPath())
            return backupDirectory
        } catch (moveFailure: Throwable) {
            // 备份目录与游戏目录可能跨卷，此时移动不可用，退化为复制后删除原目录
            TACZLegacy.logger.warn(
                "Unable to move gun pack directory {} to backup {}; falling back to copy.",
                targetDirectory,
                backupDirectory,
                moveFailure,
            )
        }
        copyDirectory(targetDirectory.toPath(), backupDirectory.toPath())
        try {
            deleteDirectory(targetDirectory.toPath())
        } catch (deleteFailure: Throwable) {
            // 旧目录可能只被删除了一部分：此时备份是完整的，直接把备份内容补回复位。
            // 不能沿用 restoreBackup —— 它会先递归删除这个残缺目录，而同一删除故障会让恢复再次失败。
            TACZLegacy.logger.error(
                "Failed to delete gun pack directory {} after backing it up to {}; restoring the missing content from the backup.",
                targetDirectory,
                backupDirectory,
                deleteFailure,
            )
            // 恢复自身若再抛异常，不能顶替掉下面要重新抛出的原始删除异常
            runCatching { restoreBackupContent(backupDirectory, targetDirectory) }.onFailure { restoreFailure ->
                TACZLegacy.logger.error(
                    "Failed to restore gun pack directory {} from backup {}; the previous content is kept at the backup path.",
                    targetDirectory,
                    backupDirectory,
                    restoreFailure,
                )
            }
            throw deleteFailure
        }
        return backupDirectory
    }

    /**
     * 把 [backupDirectory] 恢复回 [targetDirectory]。与备份阶段对称：优先移动，
     * 跨卷等移动不可用时退化为「复制 + 删除」。恢复失败时记录备份路径与异常，并保留完整备份。
     */
    private fun restoreBackup(backupDirectory: File, targetDirectory: File) {
        val failure = runCatching {
            if (targetDirectory.exists()) {
                deleteDirectory(targetDirectory.toPath())
            }
            try {
                moveDirectory(backupDirectory.toPath(), targetDirectory.toPath())
            } catch (moveFailure: Throwable) {
                // 与备份阶段对称：移动不可用（如跨卷）时退化为复制
                TACZLegacy.logger.warn(
                    "Unable to move backup {} back to {}; falling back to copy.",
                    backupDirectory,
                    targetDirectory,
                    moveFailure,
                )
                copyDirectory(backupDirectory.toPath(), targetDirectory.toPath())
            }
        }.exceptionOrNull() ?: return
        TACZLegacy.logger.error(
            "Failed to restore gun pack directory {} from backup {}; the previous content is kept at the backup path.",
            targetDirectory,
            backupDirectory,
            failure,
        )
    }

    /**
     * 备份阶段的恢复：把备份内容直接补回 [targetDirectory]，**不**先删除残缺目录。
     * 删除失败通常意味着目录仍被占用或权限不足，沿用「先删除再恢复」的策略会因同一故障再次失败。
     * 这里用 walkFileTree 逐项尽力恢复：单个条目（含整个子目录枚举）失败都只记录并继续，
     * 不会阻断其余已删除文件的补回，也不会抛异常打断调用方。
     */
    private fun restoreBackupContent(backupDirectory: File, targetDirectory: File) {
        val backupRoot = backupDirectory.toPath()
        val targetRoot = targetDirectory.toPath()
        val failures = mutableListOf<String>()

        fun recordFailure(relative: String, failure: Throwable) {
            TACZLegacy.logger.error(
                "Failed to restore {} into gun pack directory {} from backup {}.",
                relative,
                targetDirectory,
                backupDirectory,
                failure,
            )
            failures += relative
        }

        try {
            Files.walkFileTree(backupRoot, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = backupRoot.relativize(dir).toString()
                    runCatching {
                        Files.createDirectories(if (relative.isEmpty()) targetRoot else targetRoot.resolve(relative))
                    }.onFailure { recordFailure(relative, it) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = backupRoot.relativize(file).toString()
                    val targetPath = targetRoot.resolve(relative)
                    runCatching {
                        // 只补回缺失文件：残留文件通常仍被占用，触碰它们会让恢复再次失败
                        if (!Files.exists(targetPath)) {
                            Files.createDirectories(targetPath.parent)
                            Files.copy(file, targetPath)
                        }
                    }.onFailure { recordFailure(relative, it) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    recordFailure(backupRoot.relativize(file).toString(), exc)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) {
                        recordFailure(backupRoot.relativize(dir).toString(), exc)
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (failure: IOException) {
            // 遍历整体失败（例如备份根目录不可读）：记录后继续走下方汇总，保留备份路径信息
            recordFailure(".", failure)
        }

        if (failures.isNotEmpty()) {
            TACZLegacy.logger.error(
                "Gun pack directory {} is only partially restored ({} entries failed); the complete previous content is kept at backup {}.",
                targetDirectory,
                failures.size,
                backupDirectory,
            )
        }
    }

    /**
     * 移动目录：优先原子移动；文件系统不支持原子移动时退化为普通移动。
     */
    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // 该文件系统不支持原子移动：退化为普通同目录移动（此时目标路径已不存在）
            Files.move(source, target)
        }
    }

    private fun exportRegisteredResources(rootDirectory: File, overwrite: Boolean): Unit {
        ResourceManager.EXTRA_ENTRIES.forEach { entry ->
            val targetDirectory = File(rootDirectory, entry.extraDirName)
            if (targetDirectory.exists() && !overwrite) {
                return@forEach
            }
            runCatching {
                installPack(targetDirectory, entry.srcPath, entry.modMainClass.classLoader)
            }.onSuccess { backupDirectory ->
                TACZLegacy.logger.info(
                    "Exported registered gun pack resource {} from {} to {}{}.",
                    entry.srcPath,
                    entry.modMainClass.name,
                    targetDirectory,
                    backupDirectory?.let { " (backup: $it)" } ?: "",
                )
            }.onFailure { throwable ->
                TACZLegacy.logger.warn(
                    "Failed to export registered gun pack resource {} from {}.",
                    entry.srcPath,
                    entry.modMainClass.name,
                    throwable,
                )
            }
        }
    }

    private fun copyBundledPackTo(targetDirectory: Path, resourceRoot: String, classLoader: ClassLoader): Unit {
        val resourceUrl = classLoader.getResource(resourceRoot)
            ?: throw IllegalStateException("Unable to locate bundled default gun pack at $resourceRoot")

        when (resourceUrl.protocol) {
            "file" -> copyDirectory(Paths.get(resourceUrl.toURI()), targetDirectory)
            "jar" -> {
                val jarRootUri = URI.create(resourceUrl.toURI().toString().substringBefore("!"))
                val fileSystem = try {
                    FileSystems.newFileSystem(jarRootUri, emptyMap<String, Any>())
                } catch (_: FileSystemAlreadyExistsException) {
                    FileSystems.getFileSystem(jarRootUri)
                }
                val sourceRoot = fileSystem.getPath("/$resourceRoot")
                copyDirectory(sourceRoot, targetDirectory)
            }
            else -> throw IllegalStateException("Unsupported resource protocol: ${resourceUrl.protocol}")
        }
    }

    private fun copyDirectory(sourceRoot: Path, targetRoot: Path): Unit {
        Files.createDirectories(targetRoot)
        Files.walk(sourceRoot).use { stream ->
            stream.forEach { sourcePath ->
                val relative = sourceRoot.relativize(sourcePath).toString()
                val targetPath = if (relative.isEmpty()) {
                    targetRoot
                } else {
                    targetRoot.resolve(relative)
                }
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteDirectory(directory: Path): Unit {
        if (!Files.exists(directory)) {
            return
        }
        Files.walk(directory)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }
}