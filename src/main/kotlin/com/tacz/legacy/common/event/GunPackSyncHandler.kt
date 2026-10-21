package com.tacz.legacy.common.event

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageSyncGunPack
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.fml.common.FMLCommonHandler
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.PlayerEvent
import java.nio.charset.StandardCharsets

/**
 * 兼容层 ③ C2：把服务端的枪包数据同步给客户端。
 *
 * **数据源是服务端当前快照**（[com.tacz.legacy.common.resource.TACZLoadedPack.rawFiles]），
 * 不是磁盘：快照是服务端解析时真正读到的字节，两者同源才能叫"一致性来源"。
 * 顺带修掉旧实现漏 zip 包的问题（旧实现只遍历目录）。
 *
 * 下发范围见 [com.tacz.legacy.common.resource.TACZPackTransport.isSyncable]：
 * 数值/脚本/语言/显示定义；贴图/模型/声音仍由客户端本地包提供（上游架构）。
 *
 * 分片：按 [CHUNK_BYTES] 切块，避免单包超出网络层限制。
 *
 * 审计 B12-1（方向：改线格式，不是调阈值）：单文件曾经受
 * `ByteBufUtils.writeUTF8String` 的 **2 字节 varint 长度前缀**约束，上限 16383 字节，
 * 默认包自身的 lang/scripts 文件就超过它（如 `minigun_state_machine.lua` 约 31 KB），
 * 于是"整包跳过"会让专用服的远程客户端拿不到默认包的任何下发。
 * [com.tacz.legacy.common.network.message.event.ServerMessageSyncGunPack] 已把文件内容改成
 * 「int 长度 + 原始 UTF-8 字节」写出，编码层不再有 16383 限制。
 * 这里保留的 [MAX_FILE_BYTES] 只是**发送侧的安全上限**（与 vanilla 约 2 MB 的包大小限制同量级，
 * 也等于消息读侧的防御值）：正常枪包文本不会碰到它；真碰到的极可能是误入可同步范围的二进制，
 * 仍按"整包不同步 + 逐条告警"处理，因为只丢一个文件会让客户端拿到"索引有、data 缺"的静默坏包。
 */
internal object GunPackSyncHandler {

    private const val CHUNK_BYTES = 256 * 1024

    /**
     * 单文件上限（UTF-8 字节）—— 发送侧安全阀，与
     * [com.tacz.legacy.common.network.message.event.ServerMessageSyncGunPack] 读侧的
     * `MAX_VALUE_BYTES` 同值。线格式改造前这里是 16_000（受 writeUTF8String 的 16383 硬限），
     * 那个上限已随 `int + bytes` 编码一起移除。
     *
     * 为什么还留一个上限：分片以文件为最小单位，单个文件多大就必须能塞进一个网络包；
     * 1.12.2 的 netty 帧上限是 varint21 的 2 MiB，取 1 MB 给包内其它条目与头部留一倍余量。
     * 正常枪包文本（最大的默认包脚本约 31 KB）远达不到它，真达到的基本是误入同步范围的二进制。
     *
     * 注意：这仍是"整包跳过"的判据，不是分片粒度。
     */
    private const val MAX_FILE_BYTES = 1_000_000

    /** 一条告警最多列出多少个超限文件，避免坏包里上百个大文件把日志刷爆。 */
    private const val MAX_REPORTED_FILES = 5

    /**
     * 玩家登录后延迟多久再下发同步包（毫秒）。
     *
     * 为什么必须延迟：客户端连入专用服时会**先**重建一次本地资产（读盘 + 解析 models/textures，
     * 实测默认包约 11 秒）；如果同步包紧跟登录到达，客户端会在这次重建**还没结束**时再排一个
     * "应用远程包 + 全量重建"的任务，两次重建首尾相接，主线程连续阻塞超过 30 秒，
     * 期间一个 `CPacketKeepAlive` 都发不出去，服务端的连接超时判定就会把玩家踢掉
     * （实测 17:02:15 登录 → 17:02:45 `123 lost connection: Timed out`）。
     *
     * 15 秒的取值：覆盖默认包的首次重建耗时（约 11 秒）并留出区块加载的余量，
     * 同时让"应用远程包"这次重建单独发生（实测约 17 秒，仍在 30 秒超时以内）。
     */
    private const val JOIN_SYNC_DELAY_MS = 15_000L

    private val FORCE_SYNC: Boolean = java.lang.Boolean.getBoolean("tacz.syncGunPack")

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.player as? EntityPlayerMP ?: return
        val server = FMLCommonHandler.instance().minecraftServerInstance ?: return
        // 非专用服只在显式 -Dtacz.syncGunPack=true 时才同步；这种联调场景延迟同样无害。
        if (!FORCE_SYNC && !server.isDedicatedServer) {
            return
        }
        // 不在登录事件里直接发（见 JOIN_SYNC_DELAY_MS）：等客户端把加入世界的那次重建跑完。
        // 用独立线程睡眠而不是排 tick 任务，因为服务端 TPS 波动时 tick 计数会漂移。
        val thread = Thread({
            try {
                Thread.sleep(JOIN_SYNC_DELAY_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            server.addScheduledTask {
                // 延迟期间玩家可能已经退出或换服，仍在玩家列表里才发。
                if (server.playerList.players.contains(player)) {
                    sendTo(player)
                }
            }
        }, "tacz-gunpack-join-sync")
        thread.isDaemon = true
        thread.start()
    }

    /** `/tacz reload` 后把新数据重新推给所有在线玩家。 */
    fun broadcast() {
        val server = FMLCommonHandler.instance().minecraftServerInstance ?: return
        server.addScheduledTask {
            server.playerList.players.forEach { sendTo(it) }
        }
    }

    private fun sendTo(player: EntityPlayerMP) {
        val server = FMLCommonHandler.instance().minecraftServerInstance ?: return
        // 只有专用服（真正多人）需要同步；单人/局域网客户端本地就有包，同步只会多传几 MB 并触发一次重建。
        if (!FORCE_SYNC && !server.isDedicatedServer) {
            return
        }
        if (FORCE_SYNC && !server.isDedicatedServer) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] -Dtacz.syncGunPack=true 已在非专用服上强制开启同步：" +
                    "客户端会用服务端下发的同名枪包覆盖本地包。仅用于联调。",
            )
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        if (snapshot.packs.isEmpty()) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] No gun pack in the current snapshot; nothing to sync to {}.",
                player.name,
            )
            return
        }
        var packs = 0
        var chunksSent = 0
        val skippedPacks = ArrayList<String>()
        // 会话代际：客户端用它丢弃上一次会话残留的分片
        val sessionId = System.currentTimeMillis()
        snapshot.packs.forEach { (packName, pack) ->
            // 超限文件不能"只丢这一个"：客户端会拿到完整索引却缺 data，
            // 表现为整把枪静默消失（索引指向不存在的 data）。宁可整包不同步并明确告警。
            //
            // 分片以文件为最小单位（entries 是"路径 → 完整文本"），所以单文件不可再拆；
            // 但编码层的 16383 上限已随线格式改造消除（见本类头注释），阈值只剩安全阀作用。
            //
            // MAJOR 47：每个文件只编码一次。先建 path → UTF-8 字节，超限判断与后续分片
            // 共用同一份字节，避免对默认包 795 个可同步文件各做两遍 toByteArray。
            // 代价是单个包的全部可同步字节同时驻留（默认包在 MB 量级，可接受）。
            val encoded = LinkedHashMap<String, ByteArray>(pack.rawFiles.size)
            val oversized = LinkedHashMap<String, Int>()
            pack.rawFiles.forEach { (path, text) ->
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                encoded[path] = bytes
                if (bytes.size > MAX_FILE_BYTES) {
                    oversized[path] = bytes.size
                }
            }
            if (oversized.isNotEmpty()) {
                val reported = oversized.entries.take(MAX_REPORTED_FILES)
                val offenders = reported.joinToString(", ") { (path, size) -> "$path ($size B)" }
                val more = if (oversized.size > reported.size) {
                    " (+${oversized.size - reported.size} more file(s))"
                } else {
                    ""
                }
                TACZLegacy.logger.warn(
                    "[GunPackRuntime] Pack '{}' not synced to {}: {} file(s) exceed the {} byte " +
                        "per-file safety limit of the sync packet (a file cannot be split across " +
                        "chunks, and this is also the read-side bound in ServerMessageSyncGunPack). " +
                        "Offending file(s): {}{}. The whole pack is skipped on purpose: sending only " +
                        "the rest would leave the client with index entries pointing at missing data. " +
                        "Clients see this pack only if they installed it locally.",
                    packName, player.name, oversized.size, MAX_FILE_BYTES, offenders, more,
                )
                skippedPacks += packName
                return@forEach
            }
            if (pack.rawFiles.isEmpty()) {
                return@forEach
            }
            val chunks = splitBySize(pack.rawFiles, encoded, CHUNK_BYTES)
            chunks.forEachIndexed { index, chunk ->
                TACZNetworkHandler.sendToPlayer(
                    ServerMessageSyncGunPack().apply {
                        this.packName = packName
                        this.chunkIndex = index
                        this.chunkTotal = chunks.size
                        this.sessionId = sessionId
                        entries = LinkedHashMap(chunk)
                    },
                    player,
                )
                chunksSent++
            }
            packs++
        }
        if (packs > 0) {
            TACZLegacy.logger.info(
                "[GunPackRuntime] Synced {} gun pack(s) in {} chunk(s) to {}{}.",
                packs, chunksSent, player.name,
                if (skippedPacks.isNotEmpty()) {
                    " (${skippedPacks.size} pack(s) skipped as oversized: $skippedPacks)"
                } else {
                    ""
                },
            )
        } else {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Snapshot has {} pack(s) but none were syncable{}; " +
                    "remote clients will not receive gun pack data.",
                snapshot.packs.size,
                if (skippedPacks.isNotEmpty()) {
                    " (${skippedPacks.size} skipped as oversized: $skippedPacks)"
                } else {
                    ""
                },
            )
        }
    }

    /**
     * 按 [maxBytes] 把 [files] 切成多个分片（分片内仍是"路径 → 原文"，线格式不变）。
     *
     * [encoded] 是 [files] 的 UTF-8 字节缓存（每个文件只编码一次，键与 [files] 一致且同序），
     * 这里只读它的 size 估算体积，不再重复 `toByteArray`。
     */
    private fun splitBySize(
        files: Map<String, String>,
        encoded: Map<String, ByteArray>,
        maxBytes: Int,
    ): List<Map<String, String>> {
        val chunks = ArrayList<Map<String, String>>()
        var current = LinkedHashMap<String, String>()
        var currentBytes = 0
        encoded.forEach { (key, bytes) ->
            // 估算线上开销：值字节 + 路径（路径同样用 writeUTF8String 写出）+ 长度前缀/计数字段余量
            val cost = bytes.size + key.length + 8
            if (current.isNotEmpty() && currentBytes + cost > maxBytes) {
                chunks += current
                current = LinkedHashMap()
                currentBytes = 0
            }
            current[key] = files.getValue(key)
            currentBytes += cost
        }
        if (current.isNotEmpty()) {
            chunks += current
        }
        return chunks
    }
}
