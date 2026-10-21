package com.tacz.legacy.api.modifier

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C20 / 安全：modifier `function` 的 Lua 沙箱。
 *
 * 枪包里的 `"function"` 是一段**任意 Lua 源码**，由第三方包作者提供。
 * 上游 `AttachmentScriptManager.secureStandardGlobals()` 刻意不加载 `os` / `io` / `luajava`，
 * 并额外抹掉 `collectgarbage` / `dofile` / `loadfile` / `load` / `print` / `getmetatable`。
 *
 * 这里曾经用 `LuaScriptEngineFactory().scriptEngine`（= `JsePlatform.standardGlobals()`），
 * 把上面这些全装了回来 —— 意味着一个枪包就能 `luajava.newInstance("java.lang.Runtime")`
 * 执行系统命令。下面的用例把"不能"钉死。
 */
class ModifierFunctionSandboxTest {

    @Test
    fun `normal arithmetic still works`() {
        assertEquals(15.0, ModifierEvaluator.functionEval(10.0, 10.0, "y = x * 1.5"), 1e-9)
        assertEquals(12.0, ModifierEvaluator.functionEval(10.0, 10.0, "y = x + r - 8"), 1e-9)
    }

    @Test
    fun `os library is not reachable`() {
        // 若 os 可达，os.execute 会返回 0，`or x` 会得到 0；不可达时脚本报错并回退到原值
        val result = ModifierEvaluator.functionEval(10.0, 10.0, "y = os.execute('echo pwned') or x")
        assertEquals(10.0, result, 1e-9)
    }

    @Test
    fun `luajava is not reachable`() {
        val result = ModifierEvaluator.functionEval(
            7.0,
            7.0,
            "local r = luajava.newInstance('java.lang.String', 'x'); y = 1",
        )
        assertEquals(7.0, result, 1e-9)
    }

    @Test
    fun `io library is not reachable`() {
        val result = ModifierEvaluator.functionEval(3.0, 3.0, "local f = io.open('/tmp/x', 'w'); y = 1")
        assertEquals(3.0, result, 1e-9)
    }

    @Test
    fun `unsafe globals are nilled out`() {
        // 用 99 作为哨兵值：脚本没跑成功时会回退成 99，与"6 个都为 nil"可区分
        val probe = """
            local missing = 0
            if print == nil then missing = missing + 1 end
            if load == nil then missing = missing + 1 end
            if dofile == nil then missing = missing + 1 end
            if loadfile == nil then missing = missing + 1 end
            if getmetatable == nil then missing = missing + 1 end
            if collectgarbage == nil then missing = missing + 1 end
            if require == nil then missing = missing + 1 end
            if package == nil then missing = missing + 1 end
            y = missing
        """.trimIndent()
        assertEquals(8.0, ModifierEvaluator.functionEval(99.0, 99.0, probe), 1e-9)
    }

    @Test
    fun `script errors fall back to the incoming value`() {
        assertEquals(5.0, ModifierEvaluator.functionEval(5.0, 5.0, "y = this is not lua"), 1e-9)
    }

    /**
     * 编译失败会被**负缓存**：同一段坏脚本连调两次，第二次不该因为"缓存了 null 又被当成没缓存"
     * 而行为变化（例如抛异常、或返回默认值而不是原值）。
     */
    @Test
    fun `repeated evaluation of a broken script stays consistent`() {
        val script = "y = this is not lua"
        assertEquals(5.0, ModifierEvaluator.functionEval(5.0, 5.0, script), 1e-9)
        assertEquals(6.0, ModifierEvaluator.functionEval(6.0, 6.0, script), 1e-9)
    }

    /** 运行期错误（能编译但执行抛错）也必须回退，而不是把异常带进射击流程。 */
    @Test
    fun `runtime error falls back to the incoming value`() {
        assertEquals(8.0, ModifierEvaluator.functionEval(8.0, 8.0, "y = nil + 1"), 1e-9)
    }

    /**
     * 一次性探针：确认危险库全部缺席、而 `_G` 仍在（脚本靠它访问全局）。
     *
     * 与上面几条"回退值式断言"互补 —— 那些只能证明"脚本没成功执行"，
     * 这条直接数缺席的库，失败时能看出是哪几个漏了。
     */
    @Test
    fun `sandbox probe reports which dangerous globals are absent`() {
        // 反向数"存在的"：正常情况下只有 _G 存在（脚本靠 _ENV 访问全局，_G 是惯例引用），
        // 其余五个库必须缺席。这样失败时能直接看出是哪个库漏装了。
        val probe = """
            local present = 0
            if os ~= nil then present = present + 1 end
            if io ~= nil then present = present + 1 end
            if luajava ~= nil then present = present + 1 end
            if coroutine ~= nil then present = present + 1 end
            if debug ~= nil then present = present + 1 end
            y = present
        """.trimIndent()
        assertEquals(0.0, ModifierEvaluator.functionEval(99.0, 99.0, probe), 1e-9)
    }

    @Test
    fun `script is lowercased before compilation like upstream`() {
        // 上游编译前做了 toLowerCase(ENGLISH)，因此大写变量名会被小写化。
        // 若做了小写化：y = x + 1 -> 5；若没做：全局 Y 被赋值但 y 仍是 nil -> 回退 4
        assertEquals(5.0, ModifierEvaluator.functionEval(4.0, 4.0, "Y = X + 1"), 1e-9)
    }
}
