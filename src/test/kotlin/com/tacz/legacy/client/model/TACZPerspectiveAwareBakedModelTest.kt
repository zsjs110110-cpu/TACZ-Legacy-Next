package com.tacz.legacy.client.model

import net.minecraft.client.renderer.block.model.ItemCameraTransforms
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType
import org.junit.Assert.*
import org.junit.Test

class TACZPerspectiveAwareBakedModelTest {

    @Test
    fun `GUI is item presentation context`() {
        assertTrue(TACZPerspectiveAwareBakedModel.isItemPresentationContext(TransformType.GUI))
    }

    @Test
    fun `GROUND is no longer an item presentation context`() {
        assertFalse(TACZPerspectiveAwareBakedModel.isItemPresentationContext(ItemCameraTransforms.TransformType.GROUND))
    }

    @Test
    fun `FIXED is item presentation context`() {
        assertTrue(TACZPerspectiveAwareBakedModel.isItemPresentationContext(TransformType.FIXED))
    }

    @Test
    fun `HEAD is item presentation context`() {
        assertTrue(TACZPerspectiveAwareBakedModel.isItemPresentationContext(TransformType.HEAD))
    }

    @Test
    fun `NONE is item presentation context`() {
        assertTrue(TACZPerspectiveAwareBakedModel.isItemPresentationContext(TransformType.NONE))
    }

    @Test
    fun `FIRST_PERSON_RIGHT_HAND is NOT item presentation context`() {
        assertFalse(TACZPerspectiveAwareBakedModel.isItemPresentationContext(TransformType.FIRST_PERSON_RIGHT_HAND))
    }

    @Test
    fun `FIRST_PERSON_LEFT_HAND is NOT item presentation context`() {
        assertFalse(TACZPerspectiveAwareBakedModel.isItemPresentationContext(TransformType.FIRST_PERSON_LEFT_HAND))
    }

    @Test
    fun `THIRD_PERSON_RIGHT_HAND is NOT item presentation context`() {
        assertFalse(TACZPerspectiveAwareBakedModel.isItemPresentationContext(TransformType.THIRD_PERSON_RIGHT_HAND))
    }

    @Test
    fun `THIRD_PERSON_LEFT_HAND is NOT item presentation context`() {
        assertFalse(TACZPerspectiveAwareBakedModel.isItemPresentationContext(TransformType.THIRD_PERSON_LEFT_HAND))
    }

    @Test
    fun `default transform type is NONE`() {
        assertEquals(TransformType.NONE, TACZPerspectiveAwareBakedModel.getCurrentTransformType())
    }
}
