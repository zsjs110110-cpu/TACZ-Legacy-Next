package com.tacz.legacy.client.resource.serialize;

import com.google.gson.*;
import com.tacz.legacy.client.resource.pojo.animation.bedrock.SoundEffectKeyframes;
import it.unimi.dsi.fastutil.doubles.Double2ObjectRBTreeMap;
import net.minecraft.util.ResourceLocation;

import java.lang.reflect.Type;
import java.util.Map;

@SuppressWarnings("ALL")
public class SoundEffectKeyframesSerializer implements JsonDeserializer<SoundEffectKeyframes> {
    @Override
    public SoundEffectKeyframes deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
        Double2ObjectRBTreeMap<ResourceLocation> keyframes = new Double2ObjectRBTreeMap<>();
        // 如果是对象
        if (json.isJsonObject()) {
            JsonObject jsonObject = json.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entrySet : jsonObject.entrySet()) {
                double time = Double.parseDouble(entrySet.getKey());
                JsonElement value = entrySet.getValue();
                if (value.isJsonObject()) {
                    JsonObject valueObject = value.getAsJsonObject();
                    if (valueObject.has("effect")) {
                        String soundId = valueObject.get("effect").getAsString();
                        ResourceLocation soundLocation = new ResourceLocation(soundId);
                        keyframes.put(time, soundLocation);
                    }
                }
            }
            return new SoundEffectKeyframes(keyframes);
        }
        return new SoundEffectKeyframes(keyframes);
    }
}
