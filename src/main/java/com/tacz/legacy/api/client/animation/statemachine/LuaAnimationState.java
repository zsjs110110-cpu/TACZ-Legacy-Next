package com.tacz.legacy.api.client.animation.statemachine;

import com.tacz.legacy.TACZLegacy;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LuaAnimationState<T extends AnimationStateContext> implements AnimationState<T> {
    private static final Set<String> REPORTED_ERRORS = ConcurrentHashMap.newKeySet();
    private final @Nonnull LuaTable stateTable;
    private final @Nonnull LuaTable scriptTable;
    private final @Nullable LuaFunction updateFunction;
    private final @Nullable LuaFunction enterFunction;
    private final @Nullable LuaFunction exitFunction;
    private final @Nullable LuaFunction transitionFunction;

    LuaAnimationState(@Nonnull LuaTable stateTable, @Nonnull LuaTable scriptTable) {
        this.stateTable = stateTable;
        this.scriptTable = scriptTable;
        this.updateFunction = checkLuaFunction("update");
        this.enterFunction = checkLuaFunction("entry");
        this.exitFunction = checkLuaFunction("exit");
        this.transitionFunction = checkLuaFunction("transition");
    }

    @Override
    public void update(T context) {
        if (updateFunction != null) {
            try {
                updateFunction.call(scriptTable, CoerceJavaToLua.coerce(context));
            } catch (LuaError e) {
                reportError("update", e);
            }
        }
    }

    @Override
    public void entryAction(T context) {
        if (enterFunction != null) {
            try {
                enterFunction.call(scriptTable, CoerceJavaToLua.coerce(context));
            } catch (LuaError e) {
                reportError("entry", e);
            }
        }
    }

    @Override
    public void exitAction(T context) {
        if (exitFunction != null) {
            try {
                exitFunction.call(scriptTable, CoerceJavaToLua.coerce(context));
            } catch (LuaError e) {
                reportError("exit", e);
            }
        }
    }

    @Override
    public AnimationState<T> transition(T context, String condition) {
        if (transitionFunction != null) {
            LuaString conditionToLua = LuaString.valueOf(condition);
            LuaValue nextStateTable;
            try {
                nextStateTable = transitionFunction.call(scriptTable, CoerceJavaToLua.coerce(context), conditionToLua);
            } catch (LuaError e) {
                reportError("transition", e);
                return null;
            }
            if (nextStateTable.istable()) {
                return new LuaAnimationState<>((LuaTable) nextStateTable, scriptTable);
            } else if (nextStateTable.isnil()) {
                return null;
            }
            reportError("transition", new LuaError("the return of function 'transition' must be table or nil"));
        }
        return null;
    }

    /**
     * 枪包状态机脚本出错时只记录一次日志并跳过本次调用。
     * 原实现直接抛出 LuaError，会沿渲染/输入流程向上传播导致客户端崩溃。
     */
    private void reportError(String functionName, LuaError error) {
        String key = System.identityHashCode(scriptTable) + "#" + functionName + "#" + error.getMessage();
        if (REPORTED_ERRORS.add(key)) {
            TACZLegacy.logger.error("Gun animation state machine script failed in '{}'", functionName, error);
        }
    }

    private LuaFunction checkLuaFunction(String funcName) {
        LuaValue value = stateTable.get(funcName);
        if (value.isfunction()) {
            return (LuaFunction) value;
        } else if (value.isnil()) {
            return null;
        }
        throw new LuaError("the type of field '" + funcName + "' must be function or nil");
    }
}
