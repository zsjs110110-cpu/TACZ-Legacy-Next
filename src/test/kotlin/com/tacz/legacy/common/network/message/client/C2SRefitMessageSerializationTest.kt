package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.api.item.attachment.AttachmentType
import io.netty.buffer.Unpooled
import net.minecraft.init.Bootstrap
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class C2SRefitMessageSerializationTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            Bootstrap.register()
        }
    }

    @Test
    fun `ClientMessageRefitGun round-trips slot indexes and type`() {
        val message = ClientMessageRefitGun(12, 4, AttachmentType.LASER)
        val buf = Unpooled.buffer()
        message.toBytes(buf)

        val decoded = ClientMessageRefitGun()
        decoded.fromBytes(buf)

        assertEquals(12, readInt(decoded, "attachmentSlotIndex"))
        assertEquals(4, readInt(decoded, "gunSlotIndex"))
        assertEquals(AttachmentType.LASER, readType(decoded, "attachmentType"))
    }

    @Test
    fun `ClientMessageUnloadAttachment round-trips gun slot and type`() {
        val message = ClientMessageUnloadAttachment(6, AttachmentType.SCOPE)
        val buf = Unpooled.buffer()
        message.toBytes(buf)

        val decoded = ClientMessageUnloadAttachment()
        decoded.fromBytes(buf)

        assertEquals(6, readInt(decoded, "gunSlotIndex"))
        assertEquals(AttachmentType.SCOPE, readType(decoded, "attachmentType"))
    }

    @Test
    fun `ClientMessageLaserColor round-trips payload and slot`() {
        val message = ClientMessageLaserColor()
        writeField(message, "gunSlotIndex", 8)
        writeField(message, "attachmentColors", linkedMapOf(AttachmentType.LASER to 0x123456, AttachmentType.SCOPE to 0x654321))
        writeField(message, "gunColor", 0xABCDEF)

        val buf = Unpooled.buffer()
        message.toBytes(buf)

        val decoded = ClientMessageLaserColor()
        decoded.fromBytes(buf)

        assertEquals(8, readInt(decoded, "gunSlotIndex"))
        @Suppress("UNCHECKED_CAST")
        val colors = readField(decoded, "attachmentColors") as LinkedHashMap<AttachmentType, Int>
        assertEquals(linkedMapOf(AttachmentType.LASER to 0x123456, AttachmentType.SCOPE to 0x654321), colors)
        assertEquals(0xABCDEF, readField(decoded, "gunColor"))
    }

    private fun readInt(instance: Any, fieldName: String): Int = field(instance, fieldName).getInt(instance)

    private fun readType(instance: Any, fieldName: String): AttachmentType = field(instance, fieldName).get(instance) as AttachmentType

    private fun readField(instance: Any, fieldName: String): Any? = field(instance, fieldName).get(instance)

    private fun writeField(instance: Any, fieldName: String, value: Any?) {
        field(instance, fieldName).set(instance, value)
    }

    private fun field(instance: Any, fieldName: String) = instance::class.java.getDeclaredField(fieldName).apply {
        isAccessible = true
    }
}
