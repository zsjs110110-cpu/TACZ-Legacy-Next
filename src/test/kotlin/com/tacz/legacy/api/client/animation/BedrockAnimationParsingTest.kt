package com.tacz.legacy.api.client.animation

import com.google.gson.GsonBuilder
import com.tacz.legacy.client.resource.pojo.animation.bedrock.AnimationKeyframes
import com.tacz.legacy.client.resource.pojo.animation.bedrock.BedrockAnimationFile
import com.tacz.legacy.client.resource.pojo.animation.bedrock.SoundEffectKeyframes
import com.tacz.legacy.client.resource.serialize.AnimationKeyframesSerializer
import com.tacz.legacy.client.resource.serialize.SoundEffectKeyframesSerializer
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for bedrock animation parsing and channel construction.
 * Does not require a Minecraft runtime.
 */
class BedrockAnimationParsingTest {

    private val animGson = GsonBuilder()
        .registerTypeAdapter(AnimationKeyframes::class.java, AnimationKeyframesSerializer())
        .registerTypeAdapter(SoundEffectKeyframes::class.java, SoundEffectKeyframesSerializer())
        .create()

    @Test
    fun `parse simple static animation with constant keyframes`() {
        val json = """
        {
          "format_version": "1.8.0",
          "animations": {
            "static_idle": {
              "loop": true,
              "animation_length": 0.1,
              "bones": {
                "root": {
                  "rotation": [0, 0, 0],
                  "position": [0, 0, 0]
                },
                "barrel": {
                  "rotation": [10, 20, 30],
                  "position": [1, 2, 3],
                  "scale": [1.5, 1.5, 1.5]
                }
              }
            }
          }
        }
        """.trimIndent()

        val animFile = animGson.fromJson(json, BedrockAnimationFile::class.java)
        assertNotNull(animFile)
        assertNotNull(animFile.animations)
        assertEquals(1, animFile.animations.size)
        assertTrue(animFile.animations.containsKey("static_idle"))

        val anim = animFile.animations["static_idle"]!!
        assertTrue(anim.isLoop)
        assertEquals(0.1, anim.animationLength, 1e-6)
        assertNotNull(anim.bones)
        assertEquals(2, anim.bones!!.size)

        // Check root bone
        val root = anim.bones!!["root"]!!
        assertNotNull(root.rotation)
        assertNotNull(root.position)

        // Check barrel bone has all three channels
        val barrel = anim.bones!!["barrel"]!!
        assertNotNull(barrel.rotation)
        assertNotNull(barrel.position)
        assertNotNull(barrel.scale)
    }

    @Test
    fun `parse keyframed animation with catmullrom lerp`() {
        val json = """
        {
          "format_version": "1.8.0",
          "animations": {
            "draw": {
              "animation_length": 0.5,
              "bones": {
                "root": {
                  "rotation": {
                    "0.0": {
                      "post": [47.5, 0, 0],
                      "lerp_mode": "catmullrom"
                    },
                    "0.25": {
                      "post": [-6.71, -5.19, 7.62],
                      "lerp_mode": "catmullrom"
                    },
                    "0.5": {
                      "post": [0, 0, 0],
                      "lerp_mode": "catmullrom"
                    }
                  },
                  "position": {
                    "0.0": {
                      "post": [-4.5, -6.45, 4.05],
                      "lerp_mode": "catmullrom"
                    },
                    "0.5": {
                      "post": [0, 0, 0],
                      "lerp_mode": "catmullrom"
                    }
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

        val animFile = animGson.fromJson(json, BedrockAnimationFile::class.java)
        val anim = animFile.animations["draw"]!!
        assertEquals(0.5, anim.animationLength, 1e-6)
        assertFalse(anim.isLoop)

        val rootBone = anim.bones!!["root"]!!
        // Rotation should have 3 keyframes
        val rotKf = rootBone.rotation!!.keyframes
        assertEquals(3, rotKf.size)
        // First keyframe at 0.0
        assertEquals(0.0, rotKf.firstDoubleKey(), 1e-6)
        // Last keyframe at 0.5
        assertEquals(0.5, rotKf.lastDoubleKey(), 1e-6)
        // Check lerp_mode is catmullrom
        assertEquals("catmullrom", rotKf.values.first().lerpMode())
    }

    @Test
    fun `createAnimationFromBedrock produces ObjectAnimations with correct channels`() {
        val json = """
        {
          "format_version": "1.8.0",
          "animations": {
            "idle": {
              "loop": true,
              "animation_length": 1.0,
              "bones": {
                "root": {
                  "rotation": [5, 10, 15],
                  "position": [0.5, 1.0, 1.5]
                },
                "magazine": {
                  "position": {
                    "0.0": {
                      "post": [0, 0, 0]
                    },
                    "0.5": {
                      "post": [0, -3, 0]
                    },
                    "1.0": {
                      "post": [0, 0, 0]
                    }
                  }
                }
              }
            },
            "reload": {
              "animation_length": 2.0,
              "bones": {
                "magazine": {
                  "position": {
                    "0.0": {"post": [0, 0, 0]},
                    "1.0": {"post": [0, -5, 0]},
                    "2.0": {"post": [0, 0, 0]}
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

        val animFile = animGson.fromJson(json, BedrockAnimationFile::class.java)
        val animations = Animations.createAnimationFromBedrock(animFile)

        assertEquals(2, animations.size)

        // Find idle animation
        val idle = animations.find { it.name == "idle" }!!
        // root should have rotation + translation channels
        val rootChannels = idle.channels["root"]!!
        assertEquals(2, rootChannels.size)
        assertTrue(rootChannels.any { it.type == ObjectAnimationChannel.ChannelType.ROTATION })
        assertTrue(rootChannels.any { it.type == ObjectAnimationChannel.ChannelType.TRANSLATION })

        // magazine should have translation channel with 3 keyframes
        val magChannels = idle.channels["magazine"]!!
        assertEquals(1, magChannels.size)
        val magTranslation = magChannels[0]
        assertEquals(ObjectAnimationChannel.ChannelType.TRANSLATION, magTranslation.type)
        assertEquals(3, magTranslation.content.keyframeTimeS.size)

        // Find reload animation
        val reload = animations.find { it.name == "reload" }!!
        val reloadMagChannels = reload.channels["magazine"]!!
        assertEquals(1, reloadMagChannels.size)
        assertEquals(3, reloadMagChannels[0].content.keyframeTimeS.size)
    }

    @Test
    fun `interpolation produces correct values at keyframe times`() {
        val json = """
        {
          "format_version": "1.8.0",
          "animations": {
            "test": {
              "animation_length": 1.0,
              "bones": {
                "bone1": {
                  "position": {
                    "0.0": {"post": [0, 0, 0]},
                    "1.0": {"post": [16, 0, 0]}
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

        val animFile = animGson.fromJson(json, BedrockAnimationFile::class.java)
        val animations = Animations.createAnimationFromBedrock(animFile)
        val testAnim = animations[0]
        val channel = testAnim.channels["bone1"]!![0]

        // At t=0, should be [0,0,0] (after 1/16 scaling)
        val resultAtZero = channel.getResult(0f)
        assertEquals(0f, resultAtZero[0], 1e-4f)

        // At t=1.0, should be [16/16, 0, 0] = [1, 0, 0]
        val resultAtEnd = channel.getResult(1.0f)
        assertEquals(1f, resultAtEnd[0], 1e-4f)

        // At t=0.5, should be approximately [0.5, 0, 0]
        val resultAtMid = channel.getResult(0.5f)
        assertEquals(0.5f, resultAtMid[0], 1e-2f)
    }

    @Test
    fun `animation with pre and post keyframe values`() {
        val json = """
        {
          "format_version": "1.8.0",
          "animations": {
            "test": {
              "animation_length": 1.0,
              "bones": {
                "bone1": {
                  "position": {
                    "0.0": {
                      "pre": [0, 0, 0],
                      "post": [16, 0, 0]
                    },
                    "1.0": {
                      "pre": [32, 0, 0],
                      "post": [48, 0, 0]
                    }
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

        val animFile = animGson.fromJson(json, BedrockAnimationFile::class.java)
        val animations = Animations.createAnimationFromBedrock(animFile)
        val testAnim = animations[0]
        val channel = testAnim.channels["bone1"]!![0]

        // Pre/post produces 6-element arrays in content
        assertEquals(6, channel.content.values[0].size)
        assertEquals(6, channel.content.values[1].size)
    }

    @Test
    fun `animation with sound effects`() {
        val json = """
        {
          "format_version": "1.8.0",
          "animations": {
            "reload": {
              "animation_length": 2.0,
              "sound_effects": {
                "0.5": {"effect": "tacz:guns/reload_start"},
                "1.5": {"effect": "tacz:guns/reload_finish"}
              },
              "bones": {
                "magazine": {
                  "position": [0, 0, 0]
                }
              }
            }
          }
        }
        """.trimIndent()

        val animFile = animGson.fromJson(json, BedrockAnimationFile::class.java)
        val animations = Animations.createAnimationFromBedrock(animFile)
        val reload = animations[0]

        assertNotNull(reload.soundChannel)
        val soundContent = reload.soundChannel!!.content
        assertEquals(2, soundContent.keyframeTimeS.size)
        assertEquals(0.5, soundContent.keyframeTimeS[0], 1e-6)
        assertEquals(1.5, soundContent.keyframeTimeS[1], 1e-6)
        assertEquals("tacz", soundContent.keyframeSoundName[0].namespace)
        assertEquals("guns/reload_start", soundContent.keyframeSoundName[0].path)
    }

    @Test
    fun `scale keyframes are not pixel-scaled`() {
        val json = """
        {
          "format_version": "1.8.0",
          "animations": {
            "test": {
              "animation_length": 1.0,
              "bones": {
                "bone1": {
                  "scale": {
                    "0.0": {"post": [1, 1, 1]},
                    "1.0": {"post": [2, 2, 2]}
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

        val animFile = animGson.fromJson(json, BedrockAnimationFile::class.java)
        val animations = Animations.createAnimationFromBedrock(animFile)
        val channel = animations[0].channels["bone1"]!![0]
        assertEquals(ObjectAnimationChannel.ChannelType.SCALE, channel.type)

        // Scale values should be raw (not divided by 16)
        val resultAtZero = channel.getResult(0f)
        assertEquals(1f, resultAtZero[0], 1e-4f)
        assertEquals(1f, resultAtZero[1], 1e-4f)

        val resultAtEnd = channel.getResult(1.0f)
        assertEquals(2f, resultAtEnd[0], 1e-4f)
        assertEquals(2f, resultAtEnd[1], 1e-4f)
    }
}
