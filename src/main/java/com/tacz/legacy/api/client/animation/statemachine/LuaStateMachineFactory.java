package com.tacz.legacy.api.client.animation.statemachine;

import com.tacz.legacy.TACZLegacy;
import com.tacz.legacy.api.client.animation.AnimationController;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.LinkedList;
import java.util.function.Supplier;

public class LuaStateMachineFactory<T extends AnimationStateContext> {
    private AnimationController controller;
    private LuaFunction initializeFunc;
    private LuaFunction exitFunc;
    private LuaFunction statesFunc;
    private LuaTable table;

    public LuaAnimationStateMachine<T> build() {
        checkNullPointer();
        LuaAnimationStateMachine<T> stateMachine = new LuaAnimationStateMachine<>(controller);
        final LuaTable t = this.table;
        final LuaFunction initF = this.initializeFunc;
        final LuaFunction exitF = this.exitFunc;
        stateMachine.initializeFunc = (context) -> {
            if (initF != null) {
                try {
                    initF.call(t, CoerceJavaToLua.coerce(context));
                } catch (LuaError e) {
                    TACZLegacy.logger.error("Gun animation state machine script failed in 'initialize'", e);
                }
            }
        };
        stateMachine.exitFunc = (context) -> {
            if (exitF != null) {
                try {
                    exitF.call(t, CoerceJavaToLua.coerce(context));
                } catch (LuaError e) {
                    TACZLegacy.logger.error("Gun animation state machine script failed in 'exit'", e);
                }
            }
        };
        stateMachine.setStatesSupplier(getStatesSupplier());
        return stateMachine;
    }

    public LuaStateMachineFactory<T> setController(AnimationController controller) {
        this.controller = controller;
        return this;
    }

    public LuaStateMachineFactory<T> setLuaScripts(LuaTable table) {
        if (table == null) {
            return this;
        }
        this.table = table;
        this.initializeFunc = checkFunction("initialize", table);
        this.exitFunc = checkFunction("exit", table);
        this.statesFunc = checkFunction("states", table);
        return this;
    }

    private LuaFunction checkFunction(String funcName, LuaTable table) {
        LuaValue value = table.get(funcName);
        if (value.isnil()) {
            return null;
        }
        return value.checkfunction();
    }

    private void checkNullPointer() {
        if (controller == null) {
            throw new IllegalStateException("controller must not be null before build");
        }
    }

    private Supplier<Iterable<? extends AnimationState<T>>> getStatesSupplier() {
        if (statesFunc == null) {
            return null;
        }
        final LuaFunction sf = this.statesFunc;
        final LuaTable t = this.table;
        return () -> {
            LuaTable statesTable = sf.call(t).checktable();
            LinkedList<LuaAnimationState<T>> states = new LinkedList<>();
            for (int f = 1; f <= statesTable.length(); f++) {
                LuaTable stateTable = statesTable.get(f).checktable();
                states.add(new LuaAnimationState<>(stateTable, t));
            }
            return states;
        };
    }
}
