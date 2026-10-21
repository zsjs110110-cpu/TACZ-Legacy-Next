package com.tacz.legacy.client.model.blockbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal Blockbench item-model renderer for 1.12.2 (elements + UV faces).
 */
@SideOnly(Side.CLIENT)
public class BlockbenchItemModel {
    private static final Logger LOGGER = LogManager.getLogger("tacz/blockbench");
    // 用 Optional 缓存失败结果，避免加载失败的模型每帧重复读取/解析并刷异常日志
    private static final Map<ResourceLocation, Optional<BlockbenchItemModel>> CACHE = new HashMap<>();
    private static volatile boolean reloadListenerRegistered = false;

    @Nullable
    public static BlockbenchItemModel getOrLoad(ResourceLocation modelLocation) {
        ensureReloadListenerRegistered();
        Optional<BlockbenchItemModel> cached = CACHE.get(modelLocation);
        if (cached != null) {
            return cached.orElse(null);
        }
        BlockbenchItemModel loaded = load(modelLocation);
        CACHE.put(modelLocation, Optional.ofNullable(loaded));
        return loaded;
    }

    /** 资源重载时清空缓存，避免继续使用旧几何/UV/纹理 */
    private static synchronized void clearCache() {
        CACHE.clear();
    }

    private static void ensureReloadListenerRegistered() {
        if (reloadListenerRegistered) {
            return;
        }
        IResourceManager manager = Minecraft.getMinecraft().getResourceManager();
        if (manager instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) manager).registerReloadListener(new IResourceManagerReloadListener() {
                @Override
                public void onResourceManagerReload(IResourceManager resourceManager) {
                    clearCache();
                }
            });
        }
        reloadListenerRegistered = true;
    }

    private final int textureWidth;
    private final int textureHeight;
    private final Map<String, ResourceLocation> textures;
    private final Element[] elements;

    private BlockbenchItemModel(int textureWidth, int textureHeight, Map<String, ResourceLocation> textures, Element[] elements) {
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.textures = textures;
        this.elements = elements;
    }

    @Nullable
    private static BlockbenchItemModel load(ResourceLocation modelLocation) {
        ResourceLocation jsonLoc = new ResourceLocation(modelLocation.getNamespace(), "models/" + modelLocation.getPath() + ".json");
        try (IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(jsonLoc);
             Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            int[] textureSize = readIntArray(root.get("texture_size"), 16, 16);
            // 拒绝非正纹理尺寸：否则后续作为 UV 除数会产生 Infinity/NaN 或异常映射
            if (textureSize[0] <= 0 || textureSize[1] <= 0) {
                throw new JsonParseException("texture_size must be positive, got [" + textureSize[0] + ", " + textureSize[1] + "]");
            }
            Map<String, ResourceLocation> textures = new HashMap<>();
            JsonObject textureObj = root.getAsJsonObject("textures");
            if (textureObj != null) {
                for (Map.Entry<String, JsonElement> entry : textureObj.entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    String ref = entry.getValue().getAsString();
                    if (ref.startsWith("#")) {
                        continue;
                    }
                    textures.put(entry.getKey(), toTextureLocation(ref));
                }
            }
            JsonArray elementArray = root.getAsJsonArray("elements");
            if (elementArray == null) {
                return null;
            }
            Element[] elements = new Element[elementArray.size()];
            for (int i = 0; i < elementArray.size(); i++) {
                elements[i] = parseElement(elementArray.get(i).getAsJsonObject());
            }
            return new BlockbenchItemModel(textureSize[0], textureSize[1], textures, elements);
        } catch (Exception ex) {
            LOGGER.warn("Failed to load Blockbench item model {}", modelLocation, ex);
            return null;
        }
    }

    public void render(int tintColor) {
        float scale = 1.0f / 16.0f;
        for (Element element : elements) {
            element.render(this, tintColor, scale);
        }
    }

    private static Element parseElement(JsonObject obj) {
        float[] from = readFloatArray(obj.get("from"), 0f, 0f, 0f);
        float[] to = readFloatArray(obj.get("to"), 0f, 0f, 0f);
        Rotation rotation = null;
        if (obj.has("rotation")) {
            JsonObject rot = obj.getAsJsonObject("rotation");
            rotation = new Rotation(
                    rot.get("angle").getAsFloat(),
                    rot.get("axis").getAsString(),
                    readFloatArray(rot.get("origin"), 8f, 8f, 8f)
            );
        }
        EnumMap<EnumFacing, Face> faces = new EnumMap<>(EnumFacing.class);
        JsonObject faceObj = obj.getAsJsonObject("faces");
        if (faceObj != null) {
            for (Map.Entry<String, JsonElement> entry : faceObj.entrySet()) {
                EnumFacing facing = parseFacing(entry.getKey());
                if (facing == null) {
                    continue;
                }
                JsonObject faceJson = entry.getValue().getAsJsonObject();
                float[] uv = readFloatArray(faceJson.get("uv"), 0f, 0f, 16f, 16f);
                String texture = faceJson.has("texture") ? faceJson.get("texture").getAsString() : "#particle";
                if (texture.startsWith("#")) {
                    texture = texture.substring(1);
                }
                faces.put(facing, new Face(uv, texture));
            }
        }
        return new Element(from, to, rotation, faces);
    }

    @Nullable
    private static EnumFacing parseFacing(String name) {
        switch (name) {
            case "north": return EnumFacing.NORTH;
            case "south": return EnumFacing.SOUTH;
            case "east": return EnumFacing.EAST;
            case "west": return EnumFacing.WEST;
            case "up": return EnumFacing.UP;
            case "down": return EnumFacing.DOWN;
            default: return null;
        }
    }

    private static ResourceLocation toTextureLocation(String ref) {
        if (ref.contains(":")) {
            String[] parts = ref.split(":", 2);
            return new ResourceLocation(parts[0], "textures/" + parts[1] + ".png");
        }
        return new ResourceLocation("tacz", "textures/" + ref + ".png");
    }

    private static int[] readIntArray(@Nullable JsonElement element, int dx, int dy) {
        if (element == null || !element.isJsonArray()) {
            return new int[]{dx, dy};
        }
        JsonArray array = element.getAsJsonArray();
        return new int[]{
                array.size() > 0 ? array.get(0).getAsInt() : dx,
                array.size() > 1 ? array.get(1).getAsInt() : dy,
        };
    }

    private static float[] readFloatArray(@Nullable JsonElement element, float a, float b, float c) {
        if (element == null || !element.isJsonArray()) {
            return new float[]{a, b, c};
        }
        JsonArray array = element.getAsJsonArray();
        return new float[]{
                array.size() > 0 ? array.get(0).getAsFloat() : a,
                array.size() > 1 ? array.get(1).getAsFloat() : b,
                array.size() > 2 ? array.get(2).getAsFloat() : c,
        };
    }

    private static float[] readFloatArray(@Nullable JsonElement element, float a, float b, float c, float d) {
        if (element == null || !element.isJsonArray()) {
            return new float[]{a, b, c, d};
        }
        JsonArray array = element.getAsJsonArray();
        return new float[]{
                array.size() > 0 ? array.get(0).getAsFloat() : a,
                array.size() > 1 ? array.get(1).getAsFloat() : b,
                array.size() > 2 ? array.get(2).getAsFloat() : c,
                array.size() > 3 ? array.get(3).getAsFloat() : d,
        };
    }

    private static final class Rotation {
        private final float angle;
        private final String axis;
        private final float originX;
        private final float originY;
        private final float originZ;

        private Rotation(float angle, String axis, float[] origin) {
            this.angle = angle;
            this.axis = axis;
            this.originX = origin[0];
            this.originY = origin[1];
            this.originZ = origin[2];
        }

        private void apply(float scale) {
            GlStateManager.translate(originX * scale, originY * scale, originZ * scale);
            switch (axis) {
                case "x":
                    GlStateManager.rotate(angle, 1f, 0f, 0f);
                    break;
                case "y":
                    GlStateManager.rotate(angle, 0f, 1f, 0f);
                    break;
                case "z":
                    GlStateManager.rotate(angle, 0f, 0f, 1f);
                    break;
                default:
                    break;
            }
            GlStateManager.translate(-originX * scale, -originY * scale, -originZ * scale);
        }
    }

    private static final class Face {
        private final float u1;
        private final float v1;
        private final float u2;
        private final float v2;
        private final String textureKey;

        private Face(float[] uv, String textureKey) {
            this.u1 = uv[0];
            this.v1 = uv[1];
            this.u2 = uv[2];
            this.v2 = uv[3];
            this.textureKey = textureKey;
        }
    }

    private static final class Element {
        private final float x1;
        private final float y1;
        private final float z1;
        private final float x2;
        private final float y2;
        private final float z2;
        @Nullable
        private final Rotation rotation;
        private final EnumMap<EnumFacing, Face> faces;

        private Element(float[] from, float[] to, @Nullable Rotation rotation, EnumMap<EnumFacing, Face> faces) {
            this.x1 = from[0];
            this.y1 = from[1];
            this.z1 = from[2];
            this.x2 = to[0];
            this.y2 = to[1];
            this.z2 = to[2];
            this.rotation = rotation;
            this.faces = faces;
        }

        private void render(BlockbenchItemModel model, int tintColor, float scale) {
            GlStateManager.pushMatrix();
            if (rotation != null) {
                rotation.apply(scale);
            }
            for (Map.Entry<EnumFacing, Face> entry : faces.entrySet()) {
                Face face = entry.getValue();
                ResourceLocation texture = model.textures.get(face.textureKey);
                if (texture == null) {
                    continue;
                }
                Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
                drawFace(entry.getKey(), face, model.textureWidth, model.textureHeight, tintColor, scale);
            }
            GlStateManager.popMatrix();
        }

        private void drawFace(EnumFacing facing, Face face, int texWidth, int texHeight, int tintColor, float scale) {
            // 保留 UV 端点顺序：直接用 u1/u2、v1/v2 参与映射，使反向端点携带的镜像信息不被 min/max 消除。
            // 对普通面（u1<u2、v1<v2）与旧的 min/max 结果完全一致。
            float minX = face.u1 / texWidth;
            float maxX = face.u2 / texWidth;
            float minV = 1.0f - face.v2 / texHeight;
            float maxV = 1.0f - face.v1 / texHeight;

            float bx1 = x1 * scale;
            float by1 = y1 * scale;
            float bz1 = z1 * scale;
            float bx2 = x2 * scale;
            float by2 = y2 * scale;
            float bz2 = z2 * scale;

            float r = ((tintColor >> 16) & 0xFF) / 255.0f;
            float g = ((tintColor >> 8) & 0xFF) / 255.0f;
            float b = (tintColor & 0xFF) / 255.0f;

            Tessellator tessellator = Tessellator.getInstance();
            tessellator.getBuffer().begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);

            switch (facing) {
                case DOWN:
                    addVertex(tessellator, bx2, by1, bz1, maxX, maxV, r, g, b);
                    addVertex(tessellator, bx1, by1, bz1, minX, maxV, r, g, b);
                    addVertex(tessellator, bx1, by1, bz2, minX, minV, r, g, b);
                    addVertex(tessellator, bx2, by1, bz2, maxX, minV, r, g, b);
                    break;
                case UP:
                    addVertex(tessellator, bx1, by2, bz1, minX, maxV, r, g, b);
                    addVertex(tessellator, bx2, by2, bz1, maxX, maxV, r, g, b);
                    addVertex(tessellator, bx2, by2, bz2, maxX, minV, r, g, b);
                    addVertex(tessellator, bx1, by2, bz2, minX, minV, r, g, b);
                    break;
                case NORTH:
                    addVertex(tessellator, bx2, by2, bz1, maxX, minV, r, g, b);
                    addVertex(tessellator, bx1, by2, bz1, minX, minV, r, g, b);
                    addVertex(tessellator, bx1, by1, bz1, minX, maxV, r, g, b);
                    addVertex(tessellator, bx2, by1, bz1, maxX, maxV, r, g, b);
                    break;
                case SOUTH:
                    addVertex(tessellator, bx1, by2, bz2, minX, minV, r, g, b);
                    addVertex(tessellator, bx2, by2, bz2, maxX, minV, r, g, b);
                    addVertex(tessellator, bx2, by1, bz2, maxX, maxV, r, g, b);
                    addVertex(tessellator, bx1, by1, bz2, minX, maxV, r, g, b);
                    break;
                case WEST:
                    addVertex(tessellator, bx1, by2, bz1, maxX, minV, r, g, b);
                    addVertex(tessellator, bx1, by2, bz2, minX, minV, r, g, b);
                    addVertex(tessellator, bx1, by1, bz2, minX, maxV, r, g, b);
                    addVertex(tessellator, bx1, by1, bz1, maxX, maxV, r, g, b);
                    break;
                case EAST:
                    addVertex(tessellator, bx2, by2, bz2, maxX, minV, r, g, b);
                    addVertex(tessellator, bx2, by2, bz1, minX, minV, r, g, b);
                    addVertex(tessellator, bx2, by1, bz1, minX, maxV, r, g, b);
                    addVertex(tessellator, bx2, by1, bz2, maxX, maxV, r, g, b);
                    break;
                default:
                    break;
            }
            tessellator.draw();
        }

        private static void addVertex(Tessellator tessellator, float x, float y, float z, float u, float v, float r, float g, float b) {
            tessellator.getBuffer().pos(x, y, z).tex(u, v).color(r, g, b, 1.0f).endVertex();
        }
    }
}