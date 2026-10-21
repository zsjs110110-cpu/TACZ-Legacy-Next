package com.tacz.legacy

import com.tacz.legacy.api.vmlib.LuaAnimationConstant
import org.junit.Test
import org.luaj.vm2.lib.jse.JsePlatform

class LuaConstantsTest {
    @Test
    fun testLuaAnimationConstant() {
        val globals = JsePlatform.standardGlobals()
        LuaAnimationConstant().install(globals)
        val chunk = globals.load("""
            return PLAY_ONCE_HOLD
        """.trimIndent())
        val result = chunk.call()
        println("TEST LUA CONSTANT PLAY_ONCE_HOLD = " + result)
    }
}
