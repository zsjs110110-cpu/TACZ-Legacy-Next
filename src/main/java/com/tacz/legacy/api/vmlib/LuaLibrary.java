package com.tacz.legacy.api.vmlib;

import org.luaj.vm2.LuaValue;

public interface LuaLibrary {
    void install(LuaValue chunk);
}
