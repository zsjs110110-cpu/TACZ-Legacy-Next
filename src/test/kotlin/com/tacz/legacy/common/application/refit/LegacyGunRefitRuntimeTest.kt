package com.tacz.legacy.common.application.refit

import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.init.Bootstrap
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LegacyGunRefitRuntimeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            Bootstrap.register()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            TACZGunPackRuntimeRegistry.clearForTests()
        }
    }

    @Test
    fun `compatible inventory slots only include selected type and allowed attachments`() {
        withCustomSnapshot {
            val gunStack = ItemStack(LegacyItems.MODERN_KINETIC_GUN)
            LegacyItems.MODERN_KINETIC_GUN.setGunId(gunStack, ResourceLocation("demo", "refit_rifle"))

            val allowedScope = ItemStack(LegacyItems.ATTACHMENT).apply {
                LegacyItems.ATTACHMENT.setAttachmentId(this, ResourceLocation("demo", "scope_ok"))
            }
            val disallowedScope = ItemStack(LegacyItems.ATTACHMENT).apply {
                LegacyItems.ATTACHMENT.setAttachmentId(this, ResourceLocation("demo", "scope_blocked"))
            }
            val laser = ItemStack(LegacyItems.ATTACHMENT).apply {
                LegacyItems.ATTACHMENT.setAttachmentId(this, ResourceLocation("demo", "laser_ok"))
            }

            val compatible = LegacyGunRefitRuntime.compatibleInventorySlots(
                gunStack = gunStack,
                selectedType = AttachmentType.SCOPE,
                slots = listOf(
                    LegacyRefitInventorySlot(2, allowedScope),
                    LegacyRefitInventorySlot(3, laser),
                    LegacyRefitInventorySlot(4, disallowedScope),
                ),
            )

            assertEquals(listOf(2), compatible.map(LegacyRefitInventorySlot::slotIndex))
        }
    }

    @Test
    fun `compatible creative attachments include any allowed attachment of selected type`() {
        withCustomSnapshot {
            val gunStack = ItemStack(LegacyItems.MODERN_KINETIC_GUN)
            LegacyItems.MODERN_KINETIC_GUN.setGunId(gunStack, ResourceLocation("demo", "refit_rifle"))

            val compatible = LegacyGunRefitRuntime.compatibleCreativeAttachments(
                gunStack = gunStack,
                selectedType = AttachmentType.SCOPE,
            )

            assertEquals(listOf(ResourceLocation("demo", "scope_ok")), compatible.map(LegacyItems.ATTACHMENT::getAttachmentId))
        }
    }

    @Test
    fun `laser payload writes attachment and gun colors back into gun nbt`() {
        withCustomSnapshot {
            val gunStack = ItemStack(LegacyItems.MODERN_KINETIC_GUN)
            LegacyItems.MODERN_KINETIC_GUN.setGunId(gunStack, ResourceLocation("demo", "refit_rifle"))

            val laser = ItemStack(LegacyItems.ATTACHMENT)
            LegacyItems.ATTACHMENT.setAttachmentId(laser, ResourceLocation("demo", "laser_ok"))
            assertNotNull(LegacyGunRefitRuntime.swapAttachment(gunStack, laser, AttachmentType.LASER))

            LegacyGunRefitRuntime.applyLaserPayload(
                gunStack,
                LegacyLaserColorPayload(
                    attachmentColors = mapOf(AttachmentType.LASER to 0x123456),
                    gunColor = 0x654321,
                ),
            )

            assertEquals(ResourceLocation("demo", "laser_ok"), LegacyItems.MODERN_KINETIC_GUN.getAttachmentId(gunStack, AttachmentType.LASER))
            val installedLaserTag = LegacyItems.MODERN_KINETIC_GUN.getAttachmentTag(gunStack, AttachmentType.LASER)
            assertNotNull(installedLaserTag)
            assertTrue(installedLaserTag!!.hasKey("AttachmentId"))
            assertTrue(installedLaserTag.hasKey("LaserColor", 3))
            assertEquals(ResourceLocation("demo", "laser_ok").toString(), installedLaserTag.getString("AttachmentId"))
            assertEquals(0x123456, installedLaserTag.getInteger("LaserColor"))
            assertTrue(LegacyItems.MODERN_KINETIC_GUN.hasCustomLaserColor(gunStack))
            assertEquals(0x654321, LegacyItems.MODERN_KINETIC_GUN.getLaserColor(gunStack))
        }
    }

    @Test
    fun `canOpenRefit rejects empty or attachment locked guns`() {
        withCustomSnapshot {
            assertFalse(LegacyGunRefitRuntime.canOpenRefit(ItemStack.EMPTY))

            val gunStack = ItemStack(LegacyItems.MODERN_KINETIC_GUN)
            LegacyItems.MODERN_KINETIC_GUN.setGunId(gunStack, ResourceLocation("demo", "refit_rifle"))

            assertTrue(LegacyGunRefitRuntime.canOpenRefit(gunStack))

            LegacyItems.MODERN_KINETIC_GUN.setAttachmentLock(gunStack, true)
            assertFalse(LegacyGunRefitRuntime.canOpenRefit(gunStack))
        }
    }

    @Test
    fun `extended mag capacity and ammo refund follow attachment level data`() {
        withCustomSnapshot {
            val gunStack = ItemStack(LegacyItems.MODERN_KINETIC_GUN)
            LegacyItems.MODERN_KINETIC_GUN.setGunId(gunStack, ResourceLocation("demo", "refit_rifle"))

            val extendedMag = ItemStack(LegacyItems.ATTACHMENT)
            LegacyItems.ATTACHMENT.setAttachmentId(extendedMag, ResourceLocation("demo", "ext_mag_level_2"))
            LegacyItems.MODERN_KINETIC_GUN.installAttachment(gunStack, extendedMag)
            LegacyItems.MODERN_KINETIC_GUN.setCurrentAmmoCount(gunStack, 37)

            assertEquals(45, LegacyGunRefitRuntime.computeAmmoCapacity(gunStack))

            val refund = LegacyGunRefitRuntime.refundLoadedAmmo(gunStack, creativeMode = false)
            assertEquals(listOf(16, 16, 5), refund.refundStacks.map(ItemStack::getCount))
            assertTrue(refund.refundStacks.all { stack ->
                stack.item == LegacyItems.AMMO && LegacyItems.AMMO.getAmmoId(stack) == ResourceLocation("demo", "test_round")
            })
            assertEquals(0, LegacyItems.MODERN_KINETIC_GUN.getCurrentAmmoCount(gunStack))
        }
    }

    private fun withCustomSnapshot(block: () -> Unit) {
        val gameDir = Files.createTempDirectory("tacz-refit-runtime").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()
            val root = File(gameDir, "tacz").apply { mkdirs() }
            createRuntimeDemoPack(File(root, "refit_runtime_demo.zip"))
            TACZGunPackRuntimeRegistry.reload(gameDir)
            block()
        } finally {
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }

    private fun createRuntimeDemoPack(target: File) {
        FileOutputStream(target).use { output ->
            ZipOutputStream(output).use { zip ->
                writeEntry(zip, "gunpack.meta.json", """
                    {
                      "namespace": "demo"
                    }
                """.trimIndent())
                writeEntry(zip, "assets/demo/gunpack_info.json", """
                    {
                      "name": "demo.pack.name"
                    }
                """.trimIndent())
                writeEntry(zip, "assets/demo/lang/en_us.json", """
                    {
                      "demo.pack.name": "Refit Runtime Demo"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/index/guns/refit_rifle.json", """
                    {
                      "name": "demo.gun.refit_rifle.name",
                      "display": "demo:refit_rifle_display",
                      "data": "demo:refit_rifle_data",
                      "type": "rifle",
                      "item_type": "modern_kinetic"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/data/guns/refit_rifle_data.json", """
                    {
                      "ammo": "demo:test_round",
                      "fire_mode": ["auto"],
                      "ammo_amount": 30,
                      "extended_mag_ammo_amount": [40, 45, 50],
                      "rpm": 600,
                      "aim_time": 0.15,
                      "allow_attachment_types": ["scope", "laser", "extended_mag"]
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/index/ammo/test_round.json", """
                    {
                      "name": "demo.ammo.test_round.name",
                      "stack_size": 16
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/index/attachments/scope_ok.json", """
                    {
                      "name": "demo.attachment.scope_ok.name",
                      "display": "demo:scope_ok_display",
                      "data": "demo:scope_ok_data",
                      "type": "scope"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/data/attachments/scope_ok_data.json", "{}")
                writeEntry(zip, "data/demo/index/attachments/scope_blocked.json", """
                    {
                      "name": "demo.attachment.scope_blocked.name",
                      "display": "demo:scope_blocked_display",
                      "data": "demo:scope_blocked_data",
                      "type": "scope"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/data/attachments/scope_blocked_data.json", "{}")
                writeEntry(zip, "data/demo/index/attachments/laser_ok.json", """
                    {
                      "name": "demo.attachment.laser_ok.name",
                      "display": "demo:laser_ok_display",
                      "data": "demo:laser_ok_data",
                      "type": "laser"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/data/attachments/laser_ok_data.json", "{}")
                writeEntry(zip, "data/demo/index/attachments/ext_mag_level_2.json", """
                    {
                      "name": "demo.attachment.ext_mag_level_2.name",
                      "display": "demo:ext_mag_level_2_display",
                      "data": "demo:ext_mag_level_2_data",
                      "type": "extended_mag"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/data/attachments/ext_mag_level_2_data.json", """
                    {
                      "extended_mag_level": 2
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/tacz_tags/allow_attachments/refit_rifle.json", """
                    [
                      "demo:scope_ok",
                      "demo:laser_ok",
                      "demo:ext_mag_level_2"
                    ]
                """.trimIndent())
                writeEntry(zip, "assets/demo/display/guns/refit_rifle_display.json", "{}")
                writeEntry(zip, "assets/demo/display/ammo/test_round_display.json", "{}")
                writeEntry(zip, "assets/demo/display/attachments/scope_ok_display.json", "{}")
                writeEntry(zip, "assets/demo/display/attachments/scope_blocked_display.json", "{}")
                writeEntry(zip, "assets/demo/display/attachments/laser_ok_display.json", "{}")
                writeEntry(zip, "assets/demo/display/attachments/ext_mag_level_2_display.json", "{}")
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
