package com.tacz.legacy.client.resource.pojo.display.gun;

import com.google.common.collect.Maps;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import com.tacz.legacy.api.item.gun.FireMode;

import java.lang.reflect.Type;
import java.util.EnumMap;
import java.util.Map;

/**
 * 手柄震动参数（display JSON 的 {@code controllable} 字段，按开火模式分别配置）。
 * 与上游 TACZ ControllableData 保持一致，默认值也相同。
 */
public class ControllableData {
    @SerializedName("low_frequency")
    private float lowFrequency = 0.25f;

    @SerializedName("high_frequency")
    private float highFrequency = 0.5f;

    @SerializedName("time")
    private int timeInMs = 80;

    public float getLowFrequency() {
        return lowFrequency;
    }

    public float getHighFrequency() {
        return highFrequency;
    }

    public int getTimeInMs() {
        return timeInMs;
    }

    /**
     * {@code controllable} 是一个以开火模式为键的 EnumMap。
     * <p>
     * Gson 2.8.x 的 ConstructorConstructor 不支持反序列化 EnumMap（会退化成 LinkedTreeMap，
     * 写回 EnumMap 字段时抛类型错误），而本模组的运行环境正是 Forge 1.12.2 自带的 Gson 2.8.x，
     * 因此这里显式用 JsonAdapter 构造 EnumMap，并顺带跳过无法识别的开火模式键，
     * 避免一个未知键让整份 display 解析失败（进而少加载一把枪）。
     */
    public static class MapDeserializer implements JsonDeserializer<EnumMap<FireMode, ControllableData>> {
        @Override
        public EnumMap<FireMode, ControllableData> deserialize(
                JsonElement json,
                Type typeOfT,
                JsonDeserializationContext context
        ) throws JsonParseException {
            EnumMap<FireMode, ControllableData> result = Maps.newEnumMap(FireMode.class);
            if (!json.isJsonObject()) {
                return result;
            }
            JsonObject object = json.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                FireMode mode = context.deserialize(new JsonPrimitive(entry.getKey()), FireMode.class);
                if (mode == null) {
                    continue;
                }
                ControllableData data = context.deserialize(entry.getValue(), ControllableData.class);
                if (data != null) {
                    result.put(mode, data);
                }
            }
            return result;
        }
    }
}
