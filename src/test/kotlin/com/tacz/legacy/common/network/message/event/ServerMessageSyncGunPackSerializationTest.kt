package com.tacz.legacy.common.network.message.event

import io.netty.buffer.Unpooled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 B12-1 的回归护栏：同步包线格式改成「int 长度 + 原始 UTF-8 字节」之后，
 * 单个文件不再受 `ByteBufUtils.writeUTF8String` 的 16383 字节上限约束。
 *
 * 默认包本身就有超过该上限的可同步文件（`assets/tacz/scripts/minigun_state_machine.lua`
 * 约 31 KB、多个 lang 文件 16~21 KB），旧线格式下这些文件会在**编码期**抛
 * IllegalArgumentException 打断整个同步包 —— 而旧实现把阈值定在 30000，正好落在错误的一侧。
 */
class ServerMessageSyncGunPackSerializationTest {

    private fun roundTrip(message: ServerMessageSyncGunPack): ServerMessageSyncGunPack {
        val buf = Unpooled.buffer()
        message.toBytes(buf)
        val decoded = ServerMessageSyncGunPack()
        decoded.fromBytes(buf)
        return decoded
    }

    @Test
    fun `a file longer than the old 16383 byte string limit round-trips`() {
        // 32 KB 的 ASCII + 多字节中文混合，字节数明显大于字符数
        val big = buildString {
            repeat(8192) { append("local state = check_reload_status();\n") }
            repeat(2000) { append("-- 中文注释：机枪状态机\n") }
        }
        val bytes = big.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size
        assertTrue("测试样本应超过旧的 16383 上限，实际 $bytes", bytes > 16_383)

        val decoded = roundTrip(
            ServerMessageSyncGunPack().apply {
                packName = "tacz"
                chunkIndex = 0
                chunkTotal = 1
                sessionId = 1234L
                entries = linkedMapOf("assets/tacz/scripts/minigun_state_machine.lua" to big)
            },
        )

        assertEquals("tacz", decoded.packName)
        assertEquals(1234L, decoded.sessionId)
        assertEquals(1, decoded.entries.size)
        assertEquals(big, decoded.entries["assets/tacz/scripts/minigun_state_machine.lua"])
    }

    @Test
    fun `metadata and multiple entries round-trip in insertion order`() {
        val decoded = roundTrip(
            ServerMessageSyncGunPack().apply {
                packName = "demo_pack"
                chunkIndex = 2
                chunkTotal = 5
                sessionId = -7L
                entries = linkedMapOf(
                    "data/demo/data/guns/a_data.json" to "{\"rpm\":600}",
                    "data/demo/index/guns/a.json" to "{\"type\":\"rifle\"}",
                    "assets/demo/lang/zh_cn.json" to "{\"gun.demo.a\":\"演示步枪\"}",
                )
            },
        )

        assertEquals("demo_pack", decoded.packName)
        assertEquals(2, decoded.chunkIndex)
        assertEquals(5, decoded.chunkTotal)
        assertEquals(-7L, decoded.sessionId)
        assertEquals(
            listOf(
                "data/demo/data/guns/a_data.json",
                "data/demo/index/guns/a.json",
                "assets/demo/lang/zh_cn.json",
            ),
            decoded.entries.keys.toList(),
        )
        assertEquals("{\"gun.demo.a\":\"演示步枪\"}", decoded.entries["assets/demo/lang/zh_cn.json"])
    }

    /** 读侧防御：声明长度超过可读字节必须拒绝，不能按声明值分配 ByteArray */
    @Test
    fun `a forged value length larger than the buffer is rejected`() {
        val buf = Unpooled.buffer()
        ByteBufUtilsWrite.writeHello(buf)
        val decoded = ServerMessageSyncGunPack()
        val thrown = runCatching { decoded.fromBytes(buf) }.exceptionOrNull()
        assertTrue("expected the length guard to reject the forged buffer, got $thrown", thrown != null)
    }

    private object ByteBufUtilsWrite {
        /** 手工构造一个"条目数=1、声明长度远大于实际内容"的畸形包 */
        fun writeHello(buf: io.netty.buffer.ByteBuf) {
            net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(buf, "tacz")
            buf.writeInt(0)
            buf.writeInt(1)
            buf.writeLong(1L)
            buf.writeInt(1)
            net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(
                buf, "data/tacz/data/guns/x_data.json",
            )
            buf.writeInt(1_000_000) // 声明 1 MB，但后面只有一个字节
            buf.writeByte(7)
        }
    }
}
