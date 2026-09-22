package com.hugo.smartexpense.app.receipt.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceiptImageViewerTransformTest {
    private val viewportWidth = 1_000f
    private val viewportHeight = 800f
    private val imageWidth = 1_000f
    private val imageHeight = 2_000f

    @Test fun zoomIsClampedBetweenFitAndFourTimes() {
        val atMaximum = ReceiptImageViewerTransform().zoomBy(20f, viewportWidth, viewportHeight, imageWidth, imageHeight)
        val atMinimum = atMaximum.zoomBy(0.01f, viewportWidth, viewportHeight, imageWidth, imageHeight)

        assertEquals(4f, atMaximum.scale)
        assertEquals(1f, atMinimum.scale)
        assertEquals(0f, atMinimum.offsetX)
        assertEquals(0f, atMinimum.offsetY)
    }

    @Test fun panIsBoundedAndAxesThatFitAreCentered() {
        val result = ReceiptImageViewerTransform(scale = 2f).panBy(
            deltaX = 10_000f, deltaY = -10_000f,
            viewportWidth = viewportWidth, viewportHeight = viewportHeight,
            imageWidth = imageWidth, imageHeight = imageHeight,
        )

        // The fitted image is 400 x 800, so at 2x it still fits horizontally.
        assertEquals(0f, result.offsetX)
        assertEquals(-400f, result.offsetY)
    }

    @Test fun focalZoomKeepsPointUnderFingerWhenBoundsAllow() {
        val result = ReceiptImageViewerTransform().zoomAround(
            factor = 2f, focalX = 500f, focalY = 300f,
            viewportWidth = viewportWidth, viewportHeight = viewportHeight,
            imageWidth = imageWidth, imageHeight = imageHeight,
        )

        assertEquals(2f, result.scale)
        assertEquals(0f, result.offsetX)
        assertEquals(100f, result.offsetY)
    }

    @Test fun viewportChangeReclampsAndRecentersFittingAxis() {
        val result = ReceiptImageViewerTransform(scale = 4f, offsetX = 1_000f, offsetY = 1_000f).bounded(
            viewportWidth = 1_600f, viewportHeight = 800f,
            imageWidth = imageWidth, imageHeight = imageHeight,
        )

        assertEquals(0f, result.offsetX)
        assertTrue(result.offsetY <= 1_200f)
        assertTrue(result.offsetY >= -1_200f)
    }

    @Test fun resetAtFitScaleClearsTranslation() {
        val reset = ReceiptImageViewerTransform(scale = 1f, offsetX = 12f, offsetY = -18f).bounded(
            viewportWidth, viewportHeight, imageWidth, imageHeight,
        )

        assertEquals(ReceiptImageViewerTransform(), reset)
    }
}
