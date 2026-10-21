package com.tacz.legacy.api.vmlib;

import com.google.common.collect.Maps;
import com.tacz.legacy.api.entity.ReloadState;
import com.tacz.legacy.api.item.gun.FireMode;
import com.tacz.legacy.client.animation.statemachine.GunAnimationConstant;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

public class LuaGunAnimationConstant implements LuaLibrary {
    private final Map<String, Object> constantMap = Maps.newHashMap();

    public LuaGunAnimationConstant() {
        Field[] fields = GunAnimationConstant.class.getFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
                try {
                    String name = field.getName();
                    Object value = field.get(null);
                    constantMap.put(name, value);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }

        for (ReloadState.StateType stateType : ReloadState.StateType.values()) {
            constantMap.put(stateType.name(), stateType.ordinal());
        }

        for (FireMode fireMode : FireMode.values()) {
            constantMap.put(fireMode.name(), fireMode.ordinal());
        }
    }

    @Override
    public void install(LuaValue chunk) {
        for (Map.Entry<String, Object> entry : constantMap.entrySet()) {
            chunk.set(entry.getKey(), CoerceJavaToLua.coerce(entry.getValue()));
        }
    }
}
