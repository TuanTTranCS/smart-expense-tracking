package com.hugo.smartexpense.app.receipt.ui

/**
 * Pure pixel-space transform calculations for the selected-receipt viewer.
 * Offsets are measured from the center of the viewport after fitting the image.
 */
internal data class ReceiptImageViewerTransform(
    val scale: Float = MIN_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun zoomBy(factor: Float, viewportWidth: Float, viewportHeight: Float, imageWidth: Float, imageHeight: Float): ReceiptImageViewerTransform =
        zoomAround(
            factor = factor,
            focalX = viewportWidth / 2f,
            focalY = viewportHeight / 2f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )

    fun zoomAround(
        factor: Float,
        focalX: Float,
        focalY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): ReceiptImageViewerTransform {
        if (viewportWidth <= 0f || viewportHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return this
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale == MIN_SCALE) return ReceiptImageViewerTransform()
        val imagePointX = (focalX - viewportWidth / 2f - offsetX) / scale
        val imagePointY = (focalY - viewportHeight / 2f - offsetY) / scale
        return ReceiptImageViewerTransform(
            scale = newScale,
            offsetX = focalX - viewportWidth / 2f - imagePointX * newScale,
            offsetY = focalY - viewportHeight / 2f - imagePointY * newScale,
        ).bounded(viewportWidth, viewportHeight, imageWidth, imageHeight)
    }

    fun panBy(
        deltaX: Float,
        deltaY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): ReceiptImageViewerTransform = copy(
        offsetX = offsetX + deltaX,
        offsetY = offsetY + deltaY,
    ).bounded(viewportWidth, viewportHeight, imageWidth, imageHeight)

    fun bounded(
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): ReceiptImageViewerTransform {
        val boundedScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (boundedScale == MIN_SCALE || viewportWidth <= 0f || viewportHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
            return ReceiptImageViewerTransform()
        }
        val fitScale = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
        val maxOffsetX = ((imageWidth * fitScale * boundedScale) - viewportWidth).coerceAtLeast(0f) / 2f
        val maxOffsetY = ((imageHeight * fitScale * boundedScale) - viewportHeight).coerceAtLeast(0f) / 2f
        return copy(
            scale = boundedScale,
            offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX),
            offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY),
        )
    }

    internal companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        const val BUTTON_SCALE_FACTOR = 1.5f
    }
}
