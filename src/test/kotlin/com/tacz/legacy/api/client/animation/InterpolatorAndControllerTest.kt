package com.tacz.legacy.api.client.animation

import com.tacz.legacy.api.client.animation.interpolator.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for interpolators (CustomInterpolator, Linear, SLerp, Step) and
 * the AnimationController → ObjectAnimationRunner → AnimationListener drive chain.
 * No Minecraft runtime required.
 */
class InterpolatorAndControllerTest {

    // ------ Linear interpolator ------

    @Test
    fun `linear interpolation between two vec3 keyframes`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f, 1f)
        content.values = arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 20f, 30f))
        content.lerpModes = arrayOf(
            AnimationChannelContent.LerpMode.LINEAR,
            AnimationChannelContent.LerpMode.LINEAR
        )

        val linear = Linear()
        linear.compile(content)

        val mid = linear.interpolate(0, 1, 0.5f)
        assertEquals(3, mid.size)
        assertEquals(5f, mid[0], 1e-3f)
        assertEquals(10f, mid[1], 1e-3f)
        assertEquals(15f, mid[2], 1e-3f)

        val start = linear.interpolate(0, 1, 0f)
        assertEquals(0f, start[0], 1e-3f)

        val end = linear.interpolate(0, 1, 1f)
        assertEquals(10f, end[0], 1e-3f)
    }

    @Test
    fun `linear interpolation same index returns value at offset`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f)
        content.values = arrayOf(floatArrayOf(5f, 10f, 15f))
        content.lerpModes = arrayOf(AnimationChannelContent.LerpMode.LINEAR)

        val linear = Linear()
        linear.compile(content)

        val result = linear.interpolate(0, 0, 0.5f)
        assertEquals(5f, result[0], 1e-3f)
        assertEquals(10f, result[1], 1e-3f)
        assertEquals(15f, result[2], 1e-3f)
    }

    @Test
    fun `linear clone shares content`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f, 1f)
        content.values = arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 20f, 30f))
        content.lerpModes = arrayOf(
            AnimationChannelContent.LerpMode.LINEAR,
            AnimationChannelContent.LerpMode.LINEAR
        )

        val linear = Linear()
        linear.compile(content)
        val cloned = linear.clone()
        assertNotNull(cloned)
        assertTrue(cloned is Linear)

        val mid = cloned.interpolate(0, 1, 0.5f)
        assertEquals(5f, mid[0], 1e-3f)
    }

    // ------ Step interpolator ------

    @Test
    fun `step holds value until alpha equals 1`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f, 1f)
        content.values = arrayOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(10f, 20f, 30f))
        content.lerpModes = arrayOf(
            AnimationChannelContent.LerpMode.LINEAR,
            AnimationChannelContent.LerpMode.LINEAR
        )

        val step = Step()
        step.compile(content)

        // alpha < 1 returns fromIndex value
        val mid = step.interpolate(0, 1, 0.5f)
        assertEquals(1f, mid[0], 1e-3f)
        assertEquals(2f, mid[1], 1e-3f)
        assertEquals(3f, mid[2], 1e-3f)

        // alpha == 1 returns toIndex value
        val end = step.interpolate(0, 1, 1f)
        assertEquals(10f, end[0], 1e-3f)
        assertEquals(20f, end[1], 1e-3f)
        assertEquals(30f, end[2], 1e-3f)
    }

    @Test
    fun `step same index always returns same value`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f)
        content.values = arrayOf(floatArrayOf(5f, 10f, 15f))
        content.lerpModes = arrayOf(AnimationChannelContent.LerpMode.LINEAR)

        val step = Step()
        step.compile(content)

        val result = step.interpolate(0, 0, 1f)
        assertEquals(5f, result[0], 1e-3f)
    }

    // ------ SLerp interpolator ------

    @Test
    fun `slerp interpolation identity quaternion stays identity`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f, 1f)
        // Two identity quaternions (x, y, z, w)
        content.values = arrayOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        content.lerpModes = arrayOf(
            AnimationChannelContent.LerpMode.SPHERICAL_LINEAR,
            AnimationChannelContent.LerpMode.SPHERICAL_LINEAR
        )

        val slerp = SLerp()
        slerp.compile(content)

        val mid = slerp.interpolate(0, 1, 0.5f)
        assertEquals(4, mid.size)
        // Result should still be identity
        assertEquals(0f, mid[0], 1e-3f)
        assertEquals(0f, mid[1], 1e-3f)
        assertEquals(0f, mid[2], 1e-3f)
        assertEquals(1f, mid[3], 1e-3f)
    }

    @Test
    fun `slerp interpolation between different quaternions midpoint is normalized`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f, 1f)
        // Identity and 90-degree rotation around Y
        val sinHalf = Math.sin(Math.PI / 4).toFloat()
        val cosHalf = Math.cos(Math.PI / 4).toFloat()
        content.values = arrayOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(0f, sinHalf, 0f, cosHalf)
        )
        content.lerpModes = arrayOf(
            AnimationChannelContent.LerpMode.SPHERICAL_LINEAR,
            AnimationChannelContent.LerpMode.SPHERICAL_LINEAR
        )

        val slerp = SLerp()
        slerp.compile(content)

        val mid = slerp.interpolate(0, 1, 0.5f)
        // Midpoint should be 45-degree rotation around Y
        val len = Math.sqrt((mid[0] * mid[0] + mid[1] * mid[1] + mid[2] * mid[2] + mid[3] * mid[3]).toDouble())
        assertEquals(1.0, len, 1e-3)
    }

    // ------ CustomInterpolator ------

    @Test
    fun `custom interpolator catmullrom with four keyframes`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f, 0.333f, 0.666f, 1f)
        content.values = arrayOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(5f, 0f, 0f),
            floatArrayOf(10f, 0f, 0f),
            floatArrayOf(15f, 0f, 0f)
        )
        content.lerpModes = arrayOf(
            AnimationChannelContent.LerpMode.CATMULLROM,
            AnimationChannelContent.LerpMode.CATMULLROM,
            AnimationChannelContent.LerpMode.CATMULLROM,
            AnimationChannelContent.LerpMode.CATMULLROM
        )

        val interp = CustomInterpolator()
        interp.compile(content)

        // At alpha=0 between index 1 and 2, result should be value at index 1
        val atFrom = interp.interpolate(1, 2, 0f)
        assertEquals(3, atFrom.size)
        assertEquals(5f, atFrom[0], 1e-2f)

        // At alpha=1 between index 1 and 2, result should be value at index 2
        val atTo = interp.interpolate(1, 2, 1f)
        assertEquals(10f, atTo[0], 1e-2f)

        // Midpoint should be somewhere between 5 and 10
        val mid = interp.interpolate(1, 2, 0.5f)
        assertTrue("midpoint x=${mid[0]} should be between 5 and 10", mid[0] in 5f..10f)
    }

    @Test
    fun `custom interpolator linear fallback with three axis values`() {
        val content = AnimationChannelContent()
        content.keyframeTimeS = floatArrayOf(0f, 1f)
        content.values = arrayOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(10f, 20f, 30f)
        )
        content.lerpModes = arrayOf(
            AnimationChannelContent.LerpMode.LINEAR,
            AnimationChannelContent.LerpMode.LINEAR
        )

        val interp = CustomInterpolator()
        interp.compile(content)

        val mid = interp.interpolate(0, 1, 0.5f)
        assertEquals(3, mid.size)
        assertEquals(5f, mid[0], 1e-3f)
        assertEquals(10f, mid[1], 1e-3f)
        assertEquals(15f, mid[2], 1e-3f)
    }

    // ------ AnimationController with listeners ------

    @Test
    fun `animation controller drives listener through update via bedrock animation`() {
        // Build a bedrock-style animation with translation keyframes,
        // then verify the controller drives listeners correctly
        val json = """
        {
          "format_version": "1.8.0",
          "animations": {
            "test_anim": {
              "loop": false,
              "animation_length": 0.5,
              "bones": {
                "test_bone": {
                  "position": {
                    "0.0": [0, 0, 0],
                    "0.5": [16, 0, 0]
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

        val animGson = com.google.gson.GsonBuilder()
            .registerTypeAdapter(
                com.tacz.legacy.client.resource.pojo.animation.bedrock.AnimationKeyframes::class.java,
                com.tacz.legacy.client.resource.serialize.AnimationKeyframesSerializer()
            )
            .registerTypeAdapter(
                com.tacz.legacy.client.resource.pojo.animation.bedrock.SoundEffectKeyframes::class.java,
                com.tacz.legacy.client.resource.serialize.SoundEffectKeyframesSerializer()
            )
            .create()

        val animFile = animGson.fromJson(json,
            com.tacz.legacy.client.resource.pojo.animation.bedrock.BedrockAnimationFile::class.java)

        // Track listener calls
        val receivedValues = mutableListOf<FloatArray>()
        val listener = object : AnimationListener {
            override fun update(v: FloatArray, blend: Boolean) {
                receivedValues.add(v.copyOf())
            }
            override fun initialValue() = floatArrayOf(0f, 0f, 0f)
            override fun getType() = ObjectAnimationChannel.ChannelType.TRANSLATION
        }
        val supplier = AnimationListenerSupplier { nodeName, type ->
            if (nodeName == "test_bone" && type == ObjectAnimationChannel.ChannelType.TRANSLATION) listener else null
        }

        val animations = Animations.createAnimationFromBedrock(animFile)
        assertFalse("animations should not be empty", animations.isEmpty())

        val controller = AnimationController(animations, supplier)
        controller.runAnimation(0, "test_anim", ObjectAnimation.PlayType.PLAY_ONCE_HOLD, 0f)
        controller.update()

        assertTrue("listener should receive updates, got ${receivedValues.size}", receivedValues.isNotEmpty())
    }

    // ------ AnimationStateMachine basic lifecycle ------

    @Test
    fun `state machine initialize and update calls through to controller`() {
        // Minimal setup: empty controller, track lifecycle
        val supplier = AnimationListenerSupplier { _, _ -> null }
        val controller = AnimationController(emptyList(), supplier)
        val sm = com.tacz.legacy.api.client.animation.statemachine.AnimationStateMachine<
            com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext>(controller)

        val context = com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext()
        sm.setContext(context)
        sm.initialize()
        assertTrue(sm.isInitialized)
        sm.update() // should not throw
        sm.exit()
        assertFalse(sm.isInitialized)
    }

    @Test
    fun `state machine trigger causes state transition`() {
        val supplier = AnimationListenerSupplier { _, _ -> null }
        val controller = AnimationController(emptyList(), supplier)
        val sm = com.tacz.legacy.api.client.animation.statemachine.AnimationStateMachine<
            com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext>(controller)

        val transitions = mutableListOf<String>()

        val stateB = object : com.tacz.legacy.api.client.animation.statemachine.AnimationState<
            com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext> {
            override fun update(context: com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext) {}
            override fun entryAction(context: com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext) {
                transitions.add("enter_B")
            }
            override fun exitAction(context: com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext) {
                transitions.add("exit_B")
            }
            override fun transition(
                context: com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext,
                condition: String
            ): com.tacz.legacy.api.client.animation.statemachine.AnimationState<
                com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext>? = null
        }

        val stateA = object : com.tacz.legacy.api.client.animation.statemachine.AnimationState<
            com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext> {
            override fun update(context: com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext) {}
            override fun entryAction(context: com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext) {
                transitions.add("enter_A")
            }
            override fun exitAction(context: com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext) {
                transitions.add("exit_A")
            }
            override fun transition(
                context: com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext,
                condition: String
            ): com.tacz.legacy.api.client.animation.statemachine.AnimationState<
                com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext>? {
                return if (condition == "go_B") stateB else null
            }
        }

        sm.setStatesSupplier { listOf(stateA) }
        val context = com.tacz.legacy.api.client.animation.statemachine.AnimationStateContext()
        sm.setContext(context)
        sm.initialize()

        assertEquals(listOf("enter_A"), transitions)

        sm.trigger("go_B")
        assertEquals(listOf("enter_A", "exit_A", "enter_B"), transitions)
    }
}
