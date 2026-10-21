package com.tacz.legacy.client.resource.pojo.display.gun;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import org.joml.Vector3f;

import java.lang.reflect.Type;

/**
 * 副手/快捷栏枪械显示的位置、旋转、缩放（display JSON 的 {@code offhand_show}
 * 与 {@code hotbar_show} 中的值）。默认值与上游 TACZ LayerGunShow 一致。
 * <p>
 * 上游这两个字段用于把枪模型作为 HUD 图层绘制（基岩版风格的坐标/角度）。
 * 1.12.2 没有对应的 HUD 3D 图层渲染路径，因此本类只保留解析结果；
 * 其中 {@code offhand_show} 额外支持布尔简写，用于关闭副手枪械渲染（见 {@link #isVisible()}）。
 */
@JsonAdapter(LayerGunShow.Deserializer.class)
public class LayerGunShow {
    @SerializedName("pos")
    private Vector3f pos = new Vector3f(-2, 20, 4);

    @SerializedName("rotate")
    private Vector3f rotate = new Vector3f(0, 0, -30);

    @SerializedName("scale")
    private Vector3f scale = new Vector3f(0.6f, 0.6f, 0.6f);

    /**
     * 布尔简写（{@code "offhand_show": false}）表示关闭显示。
     * 上游标准写法是对象，这里仅作兼容扩展，不参与序列化。
     */
    private transient boolean hidden = false;

    public Vector3f getPos() {
        return pos;
    }

    public Vector3f getRotate() {
        return rotate;
    }

    public Vector3f getScale() {
        return scale;
    }

    /**
     * 该图层是否显示。缺省（未写字段）或写成对象时为 true；
     * 布尔简写 {@code false} 时为 false。
     */
    public boolean isVisible() {
        return !hidden;
    }

    /**
     * 同时接受上游的 {@code {pos, rotate, scale}} 对象写法与布尔简写：
     * 布尔 {@code false} 等价于“不显示”，布尔 {@code true} 等价于默认显示。
     */
    public static class Deserializer implements JsonDeserializer<LayerGunShow> {
        @Override
        public LayerGunShow deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            LayerGunShow show = new LayerGunShow();
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) {
                show.hidden = !json.getAsBoolean();
                return show;
            }
            if (!json.isJsonObject()) {
                throw new JsonParseException("Expected LayerGunShow object or boolean but found: " + json);
            }
            JsonObject object = json.getAsJsonObject();
            JsonElement pos = object.get("pos");
            if (pos != null && !pos.isJsonNull()) {
                show.pos = context.deserialize(pos, Vector3f.class);
            }
            JsonElement rotate = object.get("rotate");
            if (rotate != null && !rotate.isJsonNull()) {
                show.rotate = context.deserialize(rotate, Vector3f.class);
            }
            JsonElement scale = object.get("scale");
            if (scale != null && !scale.isJsonNull()) {
                show.scale = context.deserialize(scale, Vector3f.class);
            }
            return show;
        }
    }
}
