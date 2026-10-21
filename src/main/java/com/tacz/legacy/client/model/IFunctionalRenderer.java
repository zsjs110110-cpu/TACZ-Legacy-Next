package com.tacz.legacy.client.model;

/**
 * Functional renderer interface for programmatic bone rendering in 1.12.2.
 * Replaces the upstream PoseStack/VertexConsumer variant with GL immediate mode calls.
 * Caller is responsible for GL state and texture binding.
 */
public interface IFunctionalRenderer {
    /**
     * @param light packed lightmap coordinates (OpenGlHelper format)
     */
    void render(int light);

    /**
     * Render only the emissive/bloom contribution for this functional node.
     * Default is no-op because many functional nodes (hands, muzzle flash, text)
     * should not participate in the bloom-only pass.
     *
     * @param light packed lightmap coordinates (OpenGlHelper format)
     */
    default void renderBloom(int light) {
        // no-op by default
    }
}
