package com.tacz.legacy.client.resource.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Minimal glTF 2.0 animation parser for Blockbench-exported gun animation
 * assets. It decodes inline/sidecar buffers, accessor float data, and converts
 * animation channels into the reduced runtime structure used by Legacy.
 */
public final class GltfAnimationParser {
    private static final int GL_FLOAT = 5126;

    private GltfAnimationParser() {
    }

    public interface ExternalBufferResolver {
        @Nullable byte[] resolve(String uri) throws IOException;
    }

    public static GltfAnimationData parse(String json, @Nullable ExternalBufferResolver resolver) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        JsonArray nodesArray = getArray(root, "nodes");
        JsonArray buffersArray = getArray(root, "buffers");
        JsonArray bufferViewsArray = getArray(root, "bufferViews");
        JsonArray accessorsArray = getArray(root, "accessors");
        JsonArray animationsArray = getArray(root, "animations");

        List<String> nodeNames = new ArrayList<>(nodesArray.size());
        List<float[]> nodeTranslations = new ArrayList<>(nodesArray.size());
        List<float[]> nodeRotations = new ArrayList<>(nodesArray.size());
        List<float[]> nodeScales = new ArrayList<>(nodesArray.size());
        for (int i = 0; i < nodesArray.size(); i++) {
            JsonObject node = nodesArray.get(i).getAsJsonObject();
            nodeNames.add(optionalString(node, "name", "node_" + i));
            nodeTranslations.add(readFloatArray(node.getAsJsonArray("translation"), 3, new float[]{0f, 0f, 0f}));
            nodeRotations.add(readFloatArray(node.getAsJsonArray("rotation"), 4, new float[]{0f, 0f, 0f, 1f}));
            nodeScales.add(readFloatArray(node.getAsJsonArray("scale"), 3, new float[]{1f, 1f, 1f}));
        }

        List<byte[]> buffers = new ArrayList<>(buffersArray.size());
        for (JsonElement element : buffersArray) {
            buffers.add(decodeBuffer(element.getAsJsonObject(), resolver));
        }

        List<GltfAnimationData.Animation> animations = new ArrayList<>(animationsArray.size());
        for (JsonElement element : animationsArray) {
            JsonObject animationObject = element.getAsJsonObject();
            JsonArray samplersArray = getArray(animationObject, "samplers");
            JsonArray channelsArray = getArray(animationObject, "channels");
            List<SamplerData> samplers = new ArrayList<>(samplersArray.size());
            for (JsonElement samplerElement : samplersArray) {
                JsonObject samplerObject = samplerElement.getAsJsonObject();
                float[] input = readAccessorFloats(accessorsArray, bufferViewsArray, buffers, samplerObject.get("input").getAsInt());
                float[] output = readAccessorFloats(accessorsArray, bufferViewsArray, buffers, samplerObject.get("output").getAsInt());
                if (input.length == 0) {
                    throw new IllegalArgumentException("glTF animation sampler has no keyframes");
                }
                if (output.length % input.length != 0) {
                    throw new IllegalArgumentException("glTF animation sampler output/value count mismatch");
                }
                samplers.add(new SamplerData(input, output, mapInterpolation(optionalString(samplerObject, "interpolation", "LINEAR"))));
            }

            List<GltfAnimationData.Channel> channels = new ArrayList<>(channelsArray.size());
            for (JsonElement channelElement : channelsArray) {
                JsonObject channelObject = channelElement.getAsJsonObject();
                JsonObject targetObject = channelObject.getAsJsonObject("target");
                if (targetObject == null) {
                    continue;
                }
                int samplerIndex = channelObject.get("sampler").getAsInt();
                if (samplerIndex < 0 || samplerIndex >= samplers.size()) {
                    continue;
                }
                SamplerData sampler = samplers.get(samplerIndex);
                int nodeIndex = targetObject.get("node").getAsInt();
                if (nodeIndex < 0 || nodeIndex >= nodeNames.size()) {
                    continue;
                }
                String pathName = optionalString(targetObject, "path", "");
                GltfAnimationData.Path path;
                try {
                    path = GltfAnimationData.Path.valueOf(pathName.toUpperCase(Locale.ENGLISH));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                int valueSize = sampler.output.length / sampler.input.length;
                float[][] values = new float[sampler.input.length][valueSize];
                for (int i = 0; i < sampler.input.length; i++) {
                    System.arraycopy(sampler.output, i * valueSize, values[i], 0, valueSize);
                }
                float[] keyframeTimes = new float[sampler.input.length];
                System.arraycopy(sampler.input, 0, keyframeTimes, 0, sampler.input.length);
                float[] defaultValue;
                switch (path) {
                    case TRANSLATION:
                        defaultValue = nodeTranslations.get(nodeIndex);
                        break;
                    case ROTATION:
                        defaultValue = nodeRotations.get(nodeIndex);
                        break;
                    case SCALE:
                        defaultValue = nodeScales.get(nodeIndex);
                        break;
                    default:
                        defaultValue = new float[0];
                        break;
                }
                channels.add(new GltfAnimationData.Channel(nodeNames.get(nodeIndex), path, sampler.interpolation, keyframeTimes, values, defaultValue));
            }

            String name = optionalString(animationObject, "name", "animation_" + animations.size());
            animations.add(new GltfAnimationData.Animation(name, channels));
        }
        return new GltfAnimationData(animations);
    }

    private static byte[] decodeBuffer(JsonObject bufferObject, @Nullable ExternalBufferResolver resolver) {
        String uri = optionalString(bufferObject, "uri", "");
        if (uri.startsWith("data:")) {
            int commaIndex = uri.indexOf(',');
            if (commaIndex < 0) {
                throw new IllegalArgumentException("Invalid data URI in glTF buffer");
            }
            String metadata = uri.substring(0, commaIndex);
            String payload = uri.substring(commaIndex + 1);
            if (metadata.contains(";base64")) {
                return Base64.getDecoder().decode(payload);
            }
            return payload.getBytes(StandardCharsets.UTF_8);
        }
        if (resolver != null) {
            try {
                byte[] resolved = resolver.resolve(uri);
                if (resolved != null) {
                    return resolved;
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to resolve glTF buffer URI: " + uri, e);
            }
        }
        throw new IllegalArgumentException("Unsupported glTF buffer URI: " + uri);
    }

    private static float[] readAccessorFloats(JsonArray accessorsArray, JsonArray bufferViewsArray, List<byte[]> buffers, int accessorIndex) {
        JsonObject accessorObject = accessorsArray.get(accessorIndex).getAsJsonObject();
        if (accessorObject.has("sparse")) {
            throw new IllegalArgumentException("Sparse glTF animation accessors are not supported yet");
        }
        int componentType = accessorObject.get("componentType").getAsInt();
        if (componentType != GL_FLOAT) {
            throw new IllegalArgumentException("Unsupported glTF animation component type: " + componentType);
        }
        int count = accessorObject.get("count").getAsInt();
        int componentCount = componentCount(optionalString(accessorObject, "type", "SCALAR"));
        if (!accessorObject.has("bufferView")) {
            return new float[count * componentCount];
        }

        JsonObject bufferViewObject = bufferViewsArray.get(accessorObject.get("bufferView").getAsInt()).getAsJsonObject();
        int bufferIndex = bufferViewObject.get("buffer").getAsInt();
        byte[] buffer = buffers.get(bufferIndex);
        int accessorOffset = optionalInt(accessorObject, "byteOffset", 0);
        int bufferViewOffset = optionalInt(bufferViewObject, "byteOffset", 0);
        int stride = optionalInt(bufferViewObject, "byteStride", componentCount * 4);
        int elementSize = componentCount * 4;
        if (stride < elementSize) {
            stride = elementSize;
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[count * componentCount];
        for (int elementIndex = 0; elementIndex < count; elementIndex++) {
            int elementBase = bufferViewOffset + accessorOffset + elementIndex * stride;
            for (int componentIndex = 0; componentIndex < componentCount; componentIndex++) {
                result[elementIndex * componentCount + componentIndex] = byteBuffer.getFloat(elementBase + componentIndex * 4);
            }
        }
        return result;
    }

    private static JsonArray getArray(JsonObject object, String key) {
        JsonArray array = object.getAsJsonArray(key);
        return array == null ? new JsonArray() : array;
    }

    private static String optionalString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static int optionalInt(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    private static float[] readFloatArray(@Nullable JsonArray array, int expectedSize, float[] fallback) {
        float[] result = new float[expectedSize];
        for (int i = 0; i < expectedSize; i++) {
            result[i] = i < fallback.length ? fallback[i] : 0f;
        }
        if (array == null) {
            return result;
        }
        for (int i = 0; i < expectedSize && i < array.size(); i++) {
            result[i] = array.get(i).getAsFloat();
        }
        return result;
    }

    private static int componentCount(String type) {
        switch (type) {
            case "SCALAR":
                return 1;
            case "VEC2":
                return 2;
            case "VEC3":
                return 3;
            case "VEC4":
                return 4;
            default:
                throw new IllegalArgumentException("Unsupported glTF animation accessor type: " + type);
        }
    }

    private static GltfAnimationData.Interpolation mapInterpolation(String interpolation) {
        String normalized = interpolation.toUpperCase(Locale.ENGLISH);
        if ("STEP".equals(normalized)) {
            return GltfAnimationData.Interpolation.STEP;
        }
        if ("CUBICSPLINE".equals(normalized) || "SPLINE".equals(normalized)) {
            return GltfAnimationData.Interpolation.SPLINE;
        }
        return GltfAnimationData.Interpolation.LINEAR;
    }

    private static final class SamplerData {
        private final float[] input;
        private final float[] output;
        private final GltfAnimationData.Interpolation interpolation;

        private SamplerData(float[] input, float[] output, GltfAnimationData.Interpolation interpolation) {
            this.input = input;
            this.output = output;
            this.interpolation = interpolation;
        }
    }
}
