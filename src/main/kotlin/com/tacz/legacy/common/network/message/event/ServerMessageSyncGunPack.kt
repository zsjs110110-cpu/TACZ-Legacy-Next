package com.tacz.legacy.common.network.message.event

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZRemotePackStore
import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.network.ByteBufUtils
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext
import java.nio.charset.StandardCharsets

/**
 * 兼容层 ③ C2：服务端 → 客户端同步枪包数据（数值/脚本/lang，不含贴图模型）。
 *
 * 一个包可能超过单个网络包的大小上限，因此按 [chunkTotal] 分片；
 * 客户端由 [GunPackSyncAssembler] 重组，收齐后替换 [TACZRemotePackStore] 并重建快照。
 *
 * 审计 B12-1：文件内容**不再用 `ByteBufUtils.writeUTF8String` 写出**。该方法用 2 字节 varint
 * 写长度前缀（`ByteBufUtils.varIntByteCount(n)`：`(n & 0xFFFFC000)==0 ? 2 : 3`，再
 * `Validate.isTrue(count < 3)`），单个字符串上限因此是 **16383 字节**，超过就在编码期抛
 * `IllegalArgumentException` 打断整个同步包。默认包有多个 lang/脚本文件本身就更长
 * （`assets/tacz/scripts/minigun_state_machine.lua` 约 31 KB），只调阈值无法解决。
 * 线格式改为「int 长度 + 原始 UTF-8 字节」，读写两端同版本（本 mod 自带），因此不产生兼容分支。
 * 路径键仍用 writeUTF8String（短，且保持可读的调试输出）。
 */
public class ServerMessageSyncGunPack : IMessage {

    public var packName: String = ""
    public var chunkIndex: Int = 0
    public var chunkTotal: Int = 0
    /** 会话代际：每次服务端同步生成一个新值，客户端据此丢弃上一会话残留的分片。 */
    public var sessionId: Long = 0L
    public var entries: MutableMap<String, String> = LinkedHashMap()

    public override fun fromBytes(buf: ByteBuf) {
        packName = ByteBufUtils.readUTF8String(buf)
        chunkIndex = buf.readInt()
        chunkTotal = buf.readInt()
        sessionId = buf.readLong()
        val count = buf.readInt()
        require(count in 0..MAX_ENTRIES) { "Sync packet claims $count entries" }
        val map = LinkedHashMap<String, String>(count.coerceAtLeast(0))
        repeat(count) {
            val key = ByteBufUtils.readUTF8String(buf)
            val length = buf.readInt()
            // 读侧必须自证：长度字段来自对端，声明值超过可读字节一律拒绝，
            // 不给畸形/截断的包分配超大 ByteArray 的余地。
            require(length in 0..MAX_VALUE_BYTES && length <= buf.readableBytes()) {
                "Synced file '$key' declares $length bytes, buffer has ${buf.readableBytes()}"
            }
            val bytes = ByteArray(length)
            buf.readBytes(bytes)
            map[key] = String(bytes, StandardCharsets.UTF_8)
        }
        entries = map
    }

    public override fun toBytes(buf: ByteBuf) {
        ByteBufUtils.writeUTF8String(buf, packName)
        buf.writeInt(chunkIndex)
        buf.writeInt(chunkTotal)
        buf.writeLong(sessionId)
        buf.writeInt(entries.size)
        entries.forEach { (key, value) ->
            ByteBufUtils.writeUTF8String(buf, key)
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            buf.writeInt(bytes.size)
            buf.writeBytes(bytes)
        }
    }

    public companion object {
        /** 读侧防御：单分片最多多少个文件条目 */
        private const val MAX_ENTRIES: Int = 100_000

        /**
         * 读侧防御：单个文件内容的字节上限，与发送侧
         * [com.tacz.legacy.common.event.GunPackSyncHandler.MAX_FILE_BYTES] 同值。
         *
         * 线格式改造后编码层不再有 16383 限制，这里只保留一个"明显不是文本"的安全上限：
         * 1.12.2 的 netty 帧上限是 varint21 的 2 MiB，取 1 MB 给同包其它条目与头部留余量。
         */
        private const val MAX_VALUE_BYTES: Int = 1_000_000
    }

    public class Handler : IMessageHandler<ServerMessageSyncGunPack, IMessage?> {
        public override fun onMessage(message: ServerMessageSyncGunPack, ctx: MessageContext): IMessage? {
            // MAJOR 48：每收到一个同步包先做一次超时清理。assembler 没有独立定时器
            // （不引入客户端 tick 事件），若某包的后续分片永不到达，只能靠"又有同步包到达"
            // 这一个时机触发；完全没有后续流量时，残留分片会留到会话结束（reset()）为止。
            // 该调用在 chunks 为空时立即返回，常态成本可忽略。
            GunPackSyncAssembler.dropStalePacks()
            GunPackSyncAssembler.accept(message)
            return null
        }
    }
}

/**
 * 客户端分片重组：**每个包各自收齐、各自应用**，不等待其它包。
 *
 * 曾经的实现是"全部包收齐后一次性应用"，但那样任何一个包缺片都会让整轮失效；
 * 现在改成逐包应用（[TACZRemotePackStore.update] 是合并语义，多应用几次只是多扫描几次）。
 * 未收齐的包由 [dropStalePacks] 在超过 [STALE_PACK_MS] 后丢弃；该清理在 [accept] 内与
 * [ServerMessageSyncGunPack.Handler] 每次收到包时各触发一次，没有独立定时器（见 [dropStalePacks]）。
 */
internal object GunPackSyncAssembler {

    private val chunks = LinkedHashMap<String, MutableMap<Int, Map<String, String>>>()
    private val completed = LinkedHashMap<String, Map<String, String>>()
    private var currentSession: Long = Long.MIN_VALUE

    /** 每个包开始收片的时刻，用于丢弃"永远收不齐"的包。 */
    private val chunkStartedAt = HashMap<String, Long>()

    /**
     * 一个包超过这个时长仍未收齐就丢弃。
     *
     * 不设上限的话，任何一个包缺片都会让 [chunks] 永远非空，
     * 于是**其他已经收齐的包也永远不应用** —— 表现为"连上了但枪包一直没生效"。
     */
    private const val STALE_PACK_MS: Long = 30_000L

    /**
     * 收齐之后等这么久再重建资产，把同一轮里陆续到达的多个包合并成一次。
     *
     * 一次重建要 8 秒左右，而服务端一轮会发 6 个包；每个包都重建一次就是 40 秒以上，
     * 期间主线程发不出 keep-alive，服务端 30 秒读超时直接把玩家踢掉。
     */
    private const val APPLY_DEBOUNCE_MS: Long = 2_000L

    /** 每次请求合并就 +1；旧批次的延迟任务看到计数器变了就自行作废。 */
    private var applyGeneration = 0

    @Synchronized
    fun accept(message: ServerMessageSyncGunPack) {
        val packName = message.packName
        if (message.chunkTotal <= 0) {
            return
        }
        // 新会话：丢弃上一会话可能残留的分片
        if (message.sessionId != currentSession) {
            currentSession = message.sessionId
            chunks.clear()
            completed.clear()
            chunkStartedAt.clear()
        }
        dropStalePacks()
        val bucket = chunks.getOrPut(packName) { LinkedHashMap() }
        chunkStartedAt.putIfAbsent(packName, System.currentTimeMillis())
        bucket[message.chunkIndex] = message.entries
        if (bucket.size < message.chunkTotal) {
            return
        }
        // 判齐：必须覆盖 0..total-1，防止重复 index 覆盖导致"数量够但缺片"
        if (!(0 until message.chunkTotal).all { it in bucket }) {
            chunks.remove(packName)
            chunkStartedAt.remove(packName)
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Synced pack {} has missing chunks; dropped.",
                packName,
            )
            return
        }
        chunks.remove(packName)
        chunkStartedAt.remove(packName)
        val merged = LinkedHashMap<String, String>()
        bucket.entries.sortedBy { it.key }.forEach { merged.putAll(it.value) }
        completed[packName] = merged
        TACZLegacy.logger.info(
            "[GunPackRuntime] Received synced gun pack {} ({} file(s)).",
            packName, merged.size,
        )
        // 每收齐一个包就应用一次。原先要"等所有包齐"才应用，代价是：
        // 只要有一个包彻底停摆（丢片、断线前最后一片没到），**其它已收齐的包也永远不生效**。
        // 多次应用只是多跑几次扫描，比"整轮卡死"便宜。
        applyCompleted()
    }

    /**
     * 丢弃超过 [STALE_PACK_MS] 仍未收齐的包。
     *
     * 两个触发点：[accept] 内（会话切换之后，避免把上一会话的残留当成超时告警）与
     * [ServerMessageSyncGunPack.Handler] 每次收到包时（覆盖 chunkTotal <= 0 的畸形包，
     * 以及"本包缺片、之后再无本包分片"的情形）。[chunks] 为空时立即返回，常态零成本。
     *
     * 已知限制：没有独立定时器。若连接保持但**不再有任何同步包到达**，残留分片不会被清理，
     * 只能等下一次同步包或 [reset]（断开/切服）；本批不为此新增客户端 tick 事件。
     */
    @Synchronized
    internal fun dropStalePacks() {
        if (chunks.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        val stale = chunkStartedAt.filterValues { now - it > STALE_PACK_MS }.keys.toList()
        if (stale.isEmpty()) {
            return
        }
        stale.forEach {
            val missing = chunks[it]?.let { got -> "received ${got.size} chunk(s)" } ?: "no chunks"
            chunks.remove(it)
            chunkStartedAt.remove(it)
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Synced pack {} did not complete within {} ms ({}); dropped " +
                    "so the remaining packs can still be applied.",
                it, STALE_PACK_MS, missing,
            )
        }
        if (chunks.isEmpty() && completed.isNotEmpty()) {
            applyCompleted()
        }
    }

    /**
     * 安排一次延迟应用（防抖）。
     *
     * 不直接重建：先等 [APPLY_DEBOUNCE_MS]，期间如果还有别的包收齐，就把这次作废、
     * 由后来者重新计时。这样"六个包陆续到达"只重建一次，而不是六次。
     */
    @Synchronized
    private fun applyCompleted() {
        if (completed.isEmpty()) {
            return
        }
        val generation = ++applyGeneration
        val thread = Thread({
            try {
                Thread.sleep(APPLY_DEBOUNCE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            Minecraft.getMinecraft().addScheduledTask {
                if (generation != applyGeneration) {
                    // 更晚的一次已经接管，本次丢弃（包仍在 completed 里，不会丢数据）
                    return@addScheduledTask
                }
                applyNow()
            }
        }, "tacz-gunpack-apply")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 真正重建。锁只包住"取走待应用数据"，重建本身在锁外跑 ——
     * 否则主线程持锁 8 秒会把网络线程的 [accept] 一起堵死，同步包收不进来。
     */
    private fun applyNow() {
        val packs: Map<String, Map<String, String>> = synchronized(this) {
            if (completed.isEmpty()) {
                return
            }
            LinkedHashMap(completed).also { completed.clear() }
        }
        val mc = Minecraft.getMinecraft()
        TACZRemotePackStore.update(TACZRemotePackStore.snapshot() + packs)
        TACZGunPackRuntimeRegistry.reload(mc.gameDir)
        // 快照换了就必须同步重建客户端资产（显示定义/音效索引/Lua 环境），
        // 否则会留下"新数据 + 旧资产"：模型与音效仍按旧的 display 解析。
        TACZLegacy.proxy.onGunPackReloaded()
        TACZLegacy.logger.info(
            "[GunPackRuntime] Applied {} synced gun pack(s) and rebuilt snapshot.",
            packs.size,
        )
    }

    /** 断开连接/切换服务器时清空未完成的分片。 */
    @Synchronized
    fun reset() {
        chunks.clear()
        completed.clear()
        chunkStartedAt.clear()
        // 作废可能还在 sleep 的延迟任务，避免它把上一会话的数据应用到新会话
        applyGeneration++
    }
}
