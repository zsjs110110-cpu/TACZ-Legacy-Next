package com.tacz.legacy.common.resource

import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.item.LegacyItems
import net.minecraft.init.Bootstrap
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GunSoundParityTest {
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
    fun `gun data accessor reads fire sound multipliers from pack data`() {
        withSoundDemoPack {
            val gunData = requireNotNull(GunDataAccessor.getGunData(ResourceLocation("demo", "sound_rifle")))
            assertEquals(1.25f, gunData.fireSoundMultiplier, 0.0001f)
            assertEquals(0.5f, gunData.silenceSoundMultiplier, 0.0001f)
        }
    }

    @Test
    fun `gun sound routing promotes silencer distance and silence flag`() {
        withSoundDemoPack {
            val gunId = ResourceLocation("demo", "sound_rifle")
            val silencerId = ResourceLocation("demo", "sound_silencer")
            val gunStack = ItemStack(LegacyItems.MODERN_KINETIC_GUN).apply {
                LegacyItems.MODERN_KINETIC_GUN.setGunId(this, gunId)
            }

            val defaultProfile = TACZGunSoundRouting.resolveNearbyFireSoundProfile(gunStack)
            assertEquals(64, defaultProfile.soundDistance)
            assertFalse(defaultProfile.useSilenceSound)

            val silencerStack = ItemStack(LegacyItems.ATTACHMENT).apply {
                LegacyItems.ATTACHMENT.setAttachmentId(this, silencerId)
            }
            assertTrue(LegacyItems.MODERN_KINETIC_GUN.allowAttachment(gunStack, silencerStack))
            LegacyItems.MODERN_KINETIC_GUN.installAttachment(gunStack, silencerStack)

            val silencedProfile = TACZGunSoundRouting.resolveNearbyFireSoundProfile(gunStack)
            assertEquals(44, silencedProfile.soundDistance)
            assertTrue(silencedProfile.useSilenceSound)
            assertEquals(silencerId, LegacyItems.MODERN_KINETIC_GUN.getAttachmentId(gunStack, AttachmentType.MUZZLE))
        }
    }

    private fun withSoundDemoPack(block: () -> Unit) {
        val gameDir = Files.createTempDirectory("tacz-sound-demo").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()
            val root = File(gameDir, "tacz").apply { mkdirs() }
            createSoundDemoPack(File(root, "sound_demo.zip"))
            TACZGunPackRuntimeRegistry.reload(gameDir)
            block()
        } finally {
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }

    private fun createSoundDemoPack(target: File) {
        FileOutputStream(target).use { output ->
            ZipOutputStream(output).use { zip ->
                writeEntry(zip, "gunpack.meta.json", """
                    {
                      "namespace": "demo"
                    }
                """.trimIndent())
                writeEntry(zip, "assets/demo/gunpack_info.json", """
                    {
                      "name": "pack.demo.sound"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/index/guns/sound_rifle.json", """
                    {
                      "name": "demo.gun.sound_rifle.name",
                      "display": "demo:sound_rifle_display",
                      "data": "demo:sound_rifle_data",
                      "type": "rifle",
                      "item_type": "modern_kinetic"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/data/guns/sound_rifle_data.json", """
                    {
                      "ammo": "demo:test_round",
                      "fire_mode": ["auto"],
                      "ammo_amount": 30,
                      "rpm": 600,
                      "weight": 3.0,
                      "aim_time": 0.12,
                      "allow_attachment_types": ["muzzle"],
                      "fire_sound": {
                        "fire_multiplier": 1.25,
                        "silence_multiplier": 0.5
                      },
                      "bullet": {
                        "damage": 9,
                        "speed": 80
                      }
                    }
                """.trimIndent())
                writeEntry(zip, "assets/demo/display/guns/sound_rifle_display.json", """
                    {
                      "model": "demo:gun/model/sound_rifle_geo",
                      "texture": "demo:gun/uv/sound_rifle",
                      "animation": "demo:sound_rifle_animation",
                      "use_default_animation": "rifle",
                      "sounds": {
                        "shoot": "demo:sound_rifle/shoot",
                        "silence": "demo:sound_rifle/silence"
                      }
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/index/attachments/sound_silencer.json", """
                    {
                      "name": "demo.attachment.sound_silencer.name",
                      "display": "demo:sound_silencer_display",
                      "data": "demo:sound_silencer_data",
                      "type": "muzzle"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/tacz_tags/allow_attachments/sound_rifle.json", """
                    [
                      "demo:sound_silencer"
                    ]
                """.trimIndent())
                writeEntry(zip, "data/demo/data/attachments/sound_silencer_data.json", """
                    {
                      "weight": 0.4,
                      "silence": {
                        "distance_addend": -20,
                        "use_silence_sound": true
                      }
                    }
                """.trimIndent())
                writeEntry(zip, "assets/demo/display/attachments/sound_silencer_display.json", """
                    {
                      "slot": "demo:attachment/slot/sound_silencer",
                      "model": "demo:attachment/model/sound_silencer_geo",
                      "texture": "demo:attachment/uv/sound_silencer"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/index/ammo/test_round.json", """
                    {
                      "name": "demo.ammo.test_round.name",
                      "display": "demo:test_round_display",
                      "stack_size": 32
                    }
                """.trimIndent())
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}