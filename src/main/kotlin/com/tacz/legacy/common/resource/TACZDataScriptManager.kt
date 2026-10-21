package com.tacz.legacy.common.resource

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.entity.ReloadState
import com.tacz.legacy.api.item.gun.FireMode
import net.minecraft.util.ResourceLocation
import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.jse.*
import java.io.StringReader

/**
 * 管理服务端数据脚本的加载与缓存。
 * 对应上游 TACZ 的 CommonAssetsManager.ScriptManager。
 */
internal object TACZDataScriptManager {
    private val scriptCache = HashMap<ResourceLocation, LuaTable?>()
    @Volatile private var globals: Globals? = null

    fun reload() {
        scriptCache.clear()
        globals = null
    }

    fun getScript(scriptId: ResourceLocation): LuaTable? {
        // X-44：缺失/编译失败的脚本也要负缓存，否则每次调用都会重新解析并重复报错
        if (scriptCache.containsKey(scriptId)) {
            return scriptCache[scriptId]
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val source = snapshot.dataScripts[scriptId] ?: run {
            scriptCache[scriptId] = null
            return null
        }
        val g = ensureGlobals(snapshot)
        // X-44：编译/执行期抛错的脚本同样要负缓存。这里必须捕获，否则异常逃出 getScript 后
        // 写入不会执行，每发子弹/每次拉栓都会重新解析一遍同一份坏脚本（日志靠去重看不出重复）。
        val table = try {
            val result = g.load(StringReader(source), scriptId.toString()).call()
            if (result is LuaTable) result else null
        } catch (e: LuaError) {
            reportLoadFailure(scriptId, e)
            null
        } catch (e: RuntimeException) {
            reportLoadFailure(scriptId, e)
            null
        }
        scriptCache[scriptId] = table
        return table
    }

    /**
     * 坏脚本只报一次：调用点按 scriptId 去重的日志在异常被吞掉后就收不到了，
     * 所以改在这里用负缓存自身保证「每个脚本一条」。
     * 注意要用 error(String, Throwable) 这个重载，log4j 的 (String, Object, Object)
     * 不会把最后一个 Throwable 当异常打印，堆栈会丢。
     */
    private fun reportLoadFailure(scriptId: ResourceLocation, error: Throwable) {
        TACZLegacy.logger.error(
            "Data script $scriptId failed to load, the gun will fall back to default logic",
            error,
        )
    }

    private fun ensureGlobals(snapshot: TACZRuntimeSnapshot): Globals {
        globals?.let { return it }
        val g = Globals()
        g.load(JseBaseLib())
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())
        LoadState.install(g)
        LuaC.install(g)
        // 建立沙盒：枪包脚本只能通过 require 引用其它枪包脚本，不允许读取/执行本地文件
        g.set("dofile", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)

        // 注入全局常量：ReloadState.StateType 枚举
        for (stateType in ReloadState.StateType.entries) {
            g.set(stateType.name, LuaValue.valueOf(stateType.ordinal))
        }
        // 注入全局常量：FireMode 枚举
        for (fireMode in FireMode.entries) {
            g.set(fireMode.name, LuaValue.valueOf(fireMode.ordinal))
        }

        // 预注册所有脚本到 package.preload 以支持 require
        snapshot.dataScripts.forEach { (id, src) ->
            val moduleName = "${id.namespace}_${id.path}"
            g.get("package").get("preload").set(moduleName, object : LuaFunction() {
                private var loaded: LuaTable? = null
                override fun call(modname: LuaValue, env: LuaValue): LuaValue {
                    loaded?.let { return it }
                    val chunk = g.load(StringReader(src), moduleName)
                    val result = chunk.call()
                    val table = result.checktable(1)
                    loaded = table
                    return table
                }
            })
        }
        globals = g
        return g
    }
}
