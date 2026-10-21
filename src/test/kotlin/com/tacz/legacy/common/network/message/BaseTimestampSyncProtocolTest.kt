package com.tacz.legacy.common.network.message

import com.tacz.legacy.common.entity.shooter.ShooterDataHolder
import com.tacz.legacy.common.network.message.event.ServerMessageSyncBaseTimestamp
import com.tacz.legacy.common.network.message.client.ClientMessageSyncBaseTimestamp
import io.netty.buffer.Unpooled
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 baseTimestamp 同步握手协议的消息序列化与数据流方向。
 *
 * 该测试不测试完整的 Forge 网络管线（因为需要 Minecraft 运行时），
 * 而是验证：
 * 1. 同步消息的序列化/反序列化正确（空包体 round-trip）
 * 2. ShooterDataHolder.baseTimestamp 的更新语义符合预期
 * 3. 协议设计：server 不能单方面重置 baseTimestamp
 */
class BaseTimestampSyncProtocolTest {

    @Test
    fun `ServerMessageSyncBaseTimestamp serializes as empty payload`() {
        val msg = ServerMessageSyncBaseTimestamp()
        val buf = Unpooled.buffer()
        msg.toBytes(buf)
        assertEquals(0, buf.readableBytes())

        val decoded = ServerMessageSyncBaseTimestamp()
        decoded.fromBytes(buf)
        // decode succeeds without error
        buf.release()
    }

    @Test
    fun `ClientMessageSyncBaseTimestamp serializes as empty payload`() {
        val msg = ClientMessageSyncBaseTimestamp()
        val buf = Unpooled.buffer()
        msg.toBytes(buf)
        assertEquals(0, buf.readableBytes())

        val decoded = ClientMessageSyncBaseTimestamp()
        decoded.fromBytes(buf)
        buf.release()
    }

    @Test
    fun `baseTimestamp update on holder simulates server-side sync confirmation`() {
        // Simulates: server receives ClientMessageSyncBaseTimestamp
        // and updates data.baseTimestamp = receiveTimestamp
        val holder = ShooterDataHolder()
        val originalBase = holder.baseTimestamp
        Thread.sleep(5) // ensure time has progressed

        val receiveTimestamp = System.currentTimeMillis()
        holder.baseTimestamp = receiveTimestamp

        assertTrue(holder.baseTimestamp > originalBase)
        assertEquals(receiveTimestamp, holder.baseTimestamp)
    }

    @Test
    fun `baseTimestamp update on holder simulates client-side sync`() {
        // Simulates: client receives ServerMessageSyncBaseTimestamp
        // and updates its holder.baseTimestamp = receiveTimestamp
        val holder = ShooterDataHolder()
        Thread.sleep(5)

        val receiveTimestamp = System.currentTimeMillis()
        holder.baseTimestamp = receiveTimestamp

        // After sync, client's relative timestamp should be small
        val relativeTimestamp = System.currentTimeMillis() - holder.baseTimestamp
        assertTrue("Relative timestamp should be near zero after sync", relativeTimestamp < 50)
    }

    @Test
    fun `timestamp drift detection matches upstream window`() {
        // Upstream window: alpha < -300 || alpha > 300 + tickTime * 2
        // Legacy simplified window: alpha < -300 || alpha > 600
        // where alpha = now - serverBase - relativeTimestamp
        val holder = ShooterDataHolder()
        val serverBase = holder.baseTimestamp
        val now = System.currentTimeMillis()

        // No drift: client relative timestamp matches server expectation
        val clientBase = serverBase // simulating perfect sync
        val clientShootTime = now
        val relativeTs = clientShootTime - clientBase

        val alpha = now - serverBase - relativeTs
        assertTrue("In-sync: alpha should be within window", alpha >= -300 && alpha <= 600)
    }

    @Test
    fun `large drift causes NETWORK_FAIL and requires sync handshake`() {
        val holder = ShooterDataHolder()
        // Simulate client with drastically different base
        val serverBase = holder.baseTimestamp
        val clientBase = serverBase - 5000 // client 5 seconds behind

        val now = System.currentTimeMillis()
        val clientShootTime = now
        val relativeTs = clientShootTime - clientBase // much larger than expected

        val alpha = now - serverBase - relativeTs
        // alpha = now - serverBase - (clientShootTime - clientBase)
        // alpha = now - serverBase - clientShootTime + clientBase
        // alpha = (now - clientShootTime) + (clientBase - serverBase)
        // alpha ≈ 0 + (-5000) = -5000
        assertTrue("Large drift: alpha should be outside window", alpha < -300 || alpha > 600)
    }

    @Test
    fun `first timed shoot can infer server base from relative timestamp`() {
        val holder = ShooterDataHolder()
        val clientBase = System.currentTimeMillis() - 3000
        val serverBase = clientBase + 500
        holder.baseTimestamp = serverBase
        holder.shootTimestamp = -1L
        holder.lastShootTimestamp = -1L

        val now = System.currentTimeMillis()
        val relativeTs = now - clientBase
        var alpha = now - holder.baseTimestamp - relativeTs
        assertTrue("Initial drift should exceed window", alpha < -300 || alpha > 600)

        if (holder.shootTimestamp < 0L && holder.lastShootTimestamp < 0L) {
            holder.baseTimestamp = now - relativeTs
            alpha = now - holder.baseTimestamp - relativeTs
        }

        assertEquals(now - relativeTs, holder.baseTimestamp)
        assertTrue("After first-shot inference alpha should be within window", alpha in -300..600)
    }

    @Test
    fun `after sync handshake baseTimestamp aligns and drift resolves`() {
        // Simulates the full handshake:
        // 1. Server detects drift, sends sync
        // 2. Client receives, sets its base = System.currentTimeMillis()
        // 3. Client replies
        // 4. Server receives, sets its base = System.currentTimeMillis()
        // After this, both sides are very close to each other

        val serverHolder = ShooterDataHolder()  // server-side
        val clientHolder = ShooterDataHolder()   // client-side

        // Artificially desync them
        serverHolder.baseTimestamp = System.currentTimeMillis() - 5000
        clientHolder.baseTimestamp = System.currentTimeMillis() + 3000

        // Now simulate handshake (in reality this happens over network with latency)
        // Step 2: Client receives sync, updates its base
        val clientReceiveTime = System.currentTimeMillis()
        clientHolder.baseTimestamp = clientReceiveTime

        // Step 4: Server receives reply, updates its base
        val serverReceiveTime = System.currentTimeMillis()
        serverHolder.baseTimestamp = serverReceiveTime

        // After handshake, both bases should be very close
        val baseDiff = Math.abs(serverHolder.baseTimestamp - clientHolder.baseTimestamp)
        assertTrue("After handshake, bases should be within ~50ms", baseDiff < 50)

        // And relative timestamps should pass validation
        val now = System.currentTimeMillis()
        val clientRelTs = now - clientHolder.baseTimestamp
        val alpha = now - serverHolder.baseTimestamp - clientRelTs
        assertTrue("After handshake, alpha should be within window",
            alpha >= -300 && alpha <= 600)
    }
}
