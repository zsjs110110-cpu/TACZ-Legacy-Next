package com.tacz.legacy.client.gui

import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.DefaultGunPackExporter
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZRuntimeSnapshot
import net.minecraft.init.Bootstrap
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files

class TACZGuiPreviewResolverTest {
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
    fun `resolver maps runtime-backed stacks to display ids by item kind`() {
        withDefaultPackSnapshot { snapshot ->
            val gunStack = ItemStack(LegacyItems.MODERN_KINETIC_GUN).apply {
                LegacyItems.MODERN_KINETIC_GUN.setGunId(this, ResourceLocation("tacz", "ak47"))
            }
            val attachmentStack = ItemStack(LegacyItems.ATTACHMENT).apply {
                LegacyItems.ATTACHMENT.setAttachmentId(this, ResourceLocation("tacz", "scope_acog_ta31"))
            }
            val ammoStack = ItemStack(LegacyItems.AMMO).apply {
                LegacyItems.AMMO.setAmmoId(this, ResourceLocation("tacz", "762x39"))
            }
            val blockStack = ItemStack(LegacyItems.GUN_SMITH_TABLE).apply {
                LegacyItems.GUN_SMITH_TABLE.setBlockId(this, ResourceLocation("tacz", "gun_smith_table"))
            }

            val gunPreview = TACZGuiPreviewResolver.resolve(gunStack, snapshot)
            assertNotNull(gunPreview)
            assertEquals(TACZGuiPreviewResolver.PreviewKind.GUN, gunPreview!!.kind)
            assertEquals(TACZGunPackPresentation.resolveGunDisplayId(snapshot, ResourceLocation("tacz", "ak47")), gunPreview.displayId)

            val attachmentPreview = TACZGuiPreviewResolver.resolve(attachmentStack, snapshot)
            assertNotNull(attachmentPreview)
            assertEquals(TACZGuiPreviewResolver.PreviewKind.ATTACHMENT, attachmentPreview!!.kind)
            assertEquals(
                TACZGunPackPresentation.resolveAttachmentDisplayId(snapshot, ResourceLocation("tacz", "scope_acog_ta31")),
                attachmentPreview.displayId,
            )

            val ammoPreview = TACZGuiPreviewResolver.resolve(ammoStack, snapshot)
            assertNotNull(ammoPreview)
            assertEquals(TACZGuiPreviewResolver.PreviewKind.AMMO, ammoPreview!!.kind)
            assertEquals(TACZGunPackPresentation.resolveAmmoDisplayId(snapshot, ResourceLocation("tacz", "762x39")), ammoPreview.displayId)

            val blockPreview = TACZGuiPreviewResolver.resolve(blockStack, snapshot)
            assertNotNull(blockPreview)
            assertEquals(TACZGuiPreviewResolver.PreviewKind.BLOCK, blockPreview!!.kind)
            assertEquals(
                TACZGunPackPresentation.resolveBlockDisplayId(snapshot, ResourceLocation("tacz", "gun_smith_table")),
                blockPreview.displayId,
            )
        }
    }

    private fun withDefaultPackSnapshot(block: (TACZRuntimeSnapshot) -> Unit) {
        val gameDir = Files.createTempDirectory("tacz-gui-preview").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()
            DefaultGunPackExporter.exportIfNeeded(gameDir)
            block(TACZGunPackRuntimeRegistry.getSnapshot())
        } finally {
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }
}
