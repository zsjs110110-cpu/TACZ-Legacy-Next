package com.tacz.legacy.api.vmlib;

import com.google.common.collect.Maps;
import com.tacz.legacy.api.client.animation.ObjectAnimation;
import com.tacz.legacy.api.client.animation.statemachine.AnimationConstant;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

public class LuaAnimationConstant implements LuaLibrary {
    private final Map<String, Object> constantMap = Maps.newHashMap();

    public LuaAnimationConstant() {
        Field[] fields = AnimationConstant.class.getFields();
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

        for (ObjectAnimation.PlayType playType : ObjectAnimation.PlayType.values()) {
            constantMap.put(playType.name(), playType.ordinal());
        }
    }

    @Override
    public void install(LuaValue chunk) {
        for (Map.Entry<String, Object> entry : constantMap.entrySet()) {
            chunk.set(entry.getKey(), CoerceJavaToLua.coerce(entry.getValue()));
        }
    }
}
