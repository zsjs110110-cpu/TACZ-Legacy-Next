package com.tacz.legacy.client.resource

import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertTrue
import org.junit.Test

class TACZClientLuaScriptEnvironmentTest {
    @Test
    fun `lua script functions can read animation constants from globals`() {
        val method = TACZClientAssetManager::class.java.getDeclaredMethod(
            "loadScriptFromSource",
            ResourceLocation::class.java,
            String::class.java,
        )
        method.isAccessible = true

        val id = ResourceLocation("tacz_test", "lua_env_constants_test")
        val source =
            """
            return {
              check = function(this)
                return INPUT_DRAW == "draw"
                                    and INPUT_RELOAD == "reload"
                                    and INPUT_PUT_AWAY == "put_away"
                  and type(LOOP) == "number"
                  and type(PLAY_ONCE_STOP) == "number"
                                    and type(PLAY_ONCE_HOLD) == "number"
                                    and type(SEMI) == "number"
                                    and type(BURST) == "number"
                  and type(AUTO) == "number"
              end
            }
            """.trimIndent()

        method.invoke(TACZClientAssetManager, id, source)

        val script = TACZClientAssetManager.getScript(id)
            ?: error("expected compiled script to be cached")
        val result = script.get("check").call(script)

        assertTrue(
            "lua function should see TACZ animation constants as globals",
            result.toboolean(),
        )
    }
}