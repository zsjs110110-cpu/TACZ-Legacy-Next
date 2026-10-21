package com.tacz.legacy.common.resource

import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TACZGunPackRuntimeTest {
    @Test
    fun `exporter loads bundled default pack and modern kinetic gun resolves loaded id`() {
        val gameDir = Files.createTempDirectory("tacz-runtime").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()

            val exportResult = DefaultGunPackExporter.exportIfNeeded(gameDir)
            val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()

            assertTrue(exportResult.targetDirectory.isDirectory)
            assertTrue(snapshot.packs.isNotEmpty())
            assertTrue(snapshot.guns.size >= 40)
            assertTrue(snapshot.attachments.size >= 50)
            assertTrue(snapshot.gunItemTypes().contains("modern_kinetic"))

            val resolvedGunId = snapshot.resolveDefaultGunId("modern_kinetic")
            assertNotNull(resolvedGunId)
            assertTrue(snapshot.guns.containsKey(resolvedGunId))
        } finally {
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }

    @Test
    fun `scanner loads custom zip pack and links attachment modifiers`() {
        val gameDir = Files.createTempDirectory("tacz-runtime-zip").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()
            val packDirectory = File(gameDir, "tacz").apply { mkdirs() }
            createDemoPackZip(File(packDirectory, "demo_pack.zip"))

            val snapshot = TACZGunPackRuntimeRegistry.reload(gameDir)
            val gunId = ResourceLocation("demo", "test_rifle")
            val attachmentId = ResourceLocation("demo", "test_scope")
            val gunDisplayId = ResourceLocation("demo", "test_rifle_display")
            val attachmentDisplayId = ResourceLocation("demo", "test_scope_display")

            assertEquals(1, snapshot.packs.size)
            assertTrue(snapshot.packInfos.containsKey("demo"))
            assertTrue(snapshot.guns.containsKey(gunId))
            assertTrue(snapshot.attachments.containsKey(attachmentId))
            assertEquals(1, snapshot.ammos.size)
            assertTrue(snapshot.attachments.getValue(attachmentId).data.modifiers.containsKey("ads"))
            assertTrue(snapshot.attachments.getValue(attachmentId).data.modifiers.containsKey("recoil"))
            assertTrue(snapshot.gunDisplays.containsKey(gunDisplayId))
            assertTrue(snapshot.attachmentDisplays.containsKey(attachmentDisplayId))
            assertEquals("rifle", snapshot.gunDisplays.getValue(gunDisplayId).raw.get("use_default_animation").asString)
            assertEquals(0.75f, snapshot.gunDisplays.getValue(gunDisplayId).raw.getAsJsonObject("muzzle_flash").get("scale").asFloat, 0.001f)
            assertEquals("scope_adapter", snapshot.attachmentDisplays.getValue(attachmentDisplayId).raw.get("adapter").asString)
            assertEquals(2, snapshot.attachmentDisplays.getValue(attachmentDisplayId).raw.getAsJsonArray("views").size())

            val runtimeGunData = requireNotNull(GunDataAccessor.getGunData(gunId))
            val explosion = requireNotNull(runtimeGunData.bulletData.explosionData)
            assertTrue(explosion.explode)
            assertTrue(explosion.destroyBlock)
        } finally {
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }

    @Test
    fun `scanner skips malformed zip pack and keeps valid packs loaded`() {
        val gameDir = Files.createTempDirectory("tacz-runtime-malformed-zip").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()
            val packDirectory = File(gameDir, "tacz").apply { mkdirs() }
            createDemoPackZip(File(packDirectory, "demo_pack.zip"))
            createMalformedPackZip(File(packDirectory, "broken_pack.zip"))

            val snapshot = TACZGunPackRuntimeRegistry.reload(gameDir)

            assertEquals(1, snapshot.packs.size)
            assertTrue(snapshot.packInfos.containsKey("demo"))
            assertTrue(snapshot.guns.containsKey(ResourceLocation("demo", "test_rifle")))
            assertTrue(snapshot.issues.any { it.contains("broken_pack.zip") })
        } finally {
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }

    private fun createDemoPackZip(target: File): Unit {
        FileOutputStream(target).use { output ->
            ZipOutputStream(output).use { zip ->
                writeEntry(zip, "gunpack.meta.json", """
                    {
                      "namespace": "demo"
                    }
                """.trimIndent())
                writeEntry(zip, "assets/demo/gunpack_info.json", """
                    {
                      // version comment should survive lenient parsing
                      "version": "1.0.0",
                      "name": "pack.demo.name"
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/index/guns/test_rifle.json", """
                    {
                      "name": "demo.gun.test_rifle.name",
                      "display": "demo:test_rifle_display",
                      "data": "demo:test_rifle_data",
                      "type": "rifle",
                      "item_type": "modern_kinetic",
                      "sort": 7
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/data/guns/test_rifle_data.json", """
                    {
                      "ammo": "demo:test_round",
                      "fire_mode": ["auto"],
                      "ammo_amount": 30,
                      "rpm": 600,
                                            "bullet": {
                                                "damage": 12,
                                                "speed": 90,
                                                "explosion": {
                                                    "explode": true,
                                                    "damage": 40,
                                                    "radius": 2,
                                                    "knockback": true,
                                                    "destroy_block": true,
                                                    "delay": 15
                                                }
                                            },
                      "weight": 3.1,
                      "aim_time": 0.15,
                      "allow_attachment_types": ["scope"]
                    }
                """.trimIndent())
                                writeEntry(zip, "assets/demo/display/guns/test_rifle_display.json", """
                                        {
                                            "model": "demo:gun/model/test_rifle_geo",
                                            "texture": "demo:gun/uv/test_rifle",
                                            "animation": "demo:test_rifle_anim",
                                            "state_machine": "demo:test_rifle_state_machine",
                                            "use_default_animation": "rifle",
                                            "default_animation": "demo:common/rifle_default",
                                            "player_animator_3rd": "demo:rifle_default.player_animation",
                                            "muzzle_flash": {
                                                "texture": "demo:flash/common_muzzle_flash",
                                                "scale": 0.75
                                            },
                                            "shell": {
                                                "initial_velocity": [5, 2, 1],
                                                "random_velocity": [1, 1, 0.25],
                                                "acceleration": [0, -10, 0],
                                                "angular_velocity": [360, -1200, 90],
                                                "living_time": 1.0
                                            },
                                            "ammo": {
                                                "tracer_color": "#FF8888"
                                            }
                                        }
                                """.trimIndent())
                writeEntry(zip, "data/demo/index/attachments/test_scope.json", """
                    {
                      "name": "demo.attachment.test_scope.name",
                      "display": "demo:test_scope_display",
                      "data": "demo:test_scope_data",
                      "type": "scope",
                      "sort": 3
                    }
                """.trimIndent())
                writeEntry(zip, "data/demo/data/attachments/test_scope_data.json", """
                    {
                      "weight": 0.25,
                      "ads_addend": -0.05,
                      "recoil_modifier": {
                        "pitch": 0.1,
                        "yaw": -0.2
                      }
                    }
                """.trimIndent())
                                writeEntry(zip, "assets/demo/display/attachments/test_scope_display.json", """
                                        {
                                            "slot": "demo:attachment/slot/test_scope",
                                            "model": "demo:attachment/model/test_scope_geo",
                                            "texture": "demo:attachment/uv/test_scope",
                                            "adapter": "scope_adapter",
                                            "show_muzzle": true,
                                            "zoom": [2.0, 4.0],
                                            "views": [1, 4],
                                            "scope": true,
                                            "sight": true,
                                            "fov": 55,
                                            "views_fov": [60.0, 40.0]
                                        }
                                """.trimIndent())
                writeEntry(zip, "data/demo/index/ammo/test_round.json", """
                    {
                      "name": "demo.ammo.test_round.name",
                      "display": "demo:test_round_display",
                      "stack_size": 16
                    }
                """.trimIndent())
            }
        }
    }

    private fun createMalformedPackZip(target: File): Unit {
        val poisonedEntryName = "assets/broken/malformed_entry_payload.txt"
        FileOutputStream(target).use { output ->
            ZipOutputStream(output).use { zip ->
                writeEntry(zip, "gunpack.meta.json", """
                    {
                      "namespace": "broken"
                    }
                """.trimIndent())
                writeEntry(zip, "assets/broken/gunpack_info.json", """
                    {
                      "version": "1.0.0",
                      "name": "pack.broken.name"
                    }
                """.trimIndent())
                writeEntry(zip, poisonedEntryName, "this entry name will be corrupted after the zip is written")
            }
        }
        corruptZipEntryName(target, poisonedEntryName)
    }

    private fun corruptZipEntryName(zipFile: File, originalEntryName: String): Unit {
        val original = originalEntryName.toByteArray(StandardCharsets.UTF_8)
        val corrupted = original.copyOf()
        val poisonIndex = originalEntryName.indexOf("malformed")
        check(poisonIndex >= 0) { "Expected malformed marker in test entry name" }
        corrupted[poisonIndex] = 0xC3.toByte()
        corrupted[poisonIndex + 1] = 0x28.toByte()

        val bytes = Files.readAllBytes(zipFile.toPath())
        val replacements = replaceAllOccurrences(bytes, original, corrupted)
        check(replacements >= 2) { "Expected to patch local header and central directory entry name" }
        Files.write(zipFile.toPath(), bytes)
    }

    private fun replaceAllOccurrences(bytes: ByteArray, original: ByteArray, replacement: ByteArray): Int {
        require(original.size == replacement.size) { "Replacement must keep entry length stable" }
        var replacements = 0
        var index = 0
        while (index <= bytes.size - original.size) {
            if (!matchesAt(bytes, original, index)) {
                index += 1
                continue
            }
            replacement.copyInto(bytes, destinationOffset = index)
            replacements += 1
            index += original.size
        }
        return replacements
    }

    private fun matchesAt(bytes: ByteArray, expected: ByteArray, offset: Int): Boolean {
        for (i in expected.indices) {
            if (bytes[offset + i] != expected[i]) {
                return false
            }
        }
        return true
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String): Unit {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
