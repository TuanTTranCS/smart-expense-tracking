package com.hugo.smartexpense.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptImageLoaderTest {
    @Test
    fun convertsSelectedUriContentToReceiptImage() {
        val source = FakeReceiptImageContentSource(
            name = "receipt.png",
            mimeType = "image/png",
            bytes = byteArrayOf(1, 2, 3),
        )

        val image = ReceiptImageLoader(source).load("content://receipts/42")

        assertEquals("content://receipts/42", source.lastUri)
        assertEquals("receipt.png", image.sourceName)
        assertEquals("image/png", image.mimeType)
        assertContentEquals(byteArrayOf(1, 2, 3), image.bytes)
    }

    @Test
    fun rejectsAnEmptySelectedImage() {
        val source = FakeReceiptImageContentSource(bytes = byteArrayOf())

        assertFailsWith<IllegalArgumentException> {
            ReceiptImageLoader(source).load("content://receipts/empty")
        }
    }

    @Test
    fun reducesAnOversizedImageBelowTheThresholdWhenEnabled() {
        val original = ByteArray(RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES + 1) { 1 }
        val reduced = ByteArray(RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES - 1) { 2 }
        val reducer = RecordingImageSizeReducer(reduced)
        val loader = ReceiptImageLoader(
            source = FakeReceiptImageContentSource(bytes = original, mimeType = "image/png"),
            sizeReducer = reducer,
        )

        val image = loader.load("content://receipts/oversized", reduceIfOversized = true)

        assertTrue(reducer.called)
        assertEquals(RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES, reducer.maxBytesExclusive)
        assertContentEquals(reduced, image.bytes)
        assertEquals("image/jpeg", image.mimeType)
        assertTrue(image.bytes.size < RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES)
    }

    @Test
    fun preservesAnOversizedImageWhenReductionIsDisabled() {
        val original = ByteArray(RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES + 1) { 1 }
        val reducer = RecordingImageSizeReducer(ByteArray(10))
        val loader = ReceiptImageLoader(
            source = FakeReceiptImageContentSource(bytes = original, mimeType = "image/png"),
            sizeReducer = reducer,
        )

        val image = loader.load("content://receipts/original", reduceIfOversized = false)

        assertFalse(reducer.called)
        assertContentEquals(original, image.bytes)
        assertEquals("image/png", image.mimeType)
    }

    @Test
    fun doesNotReduceAnImageAtTheThreshold() {
        val original = ByteArray(RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES) { 1 }
        val reducer = RecordingImageSizeReducer(ByteArray(10))
        val loader = ReceiptImageLoader(
            source = FakeReceiptImageContentSource(bytes = original),
            sizeReducer = reducer,
        )

        val image = loader.load("content://receipts/at-threshold")

        assertFalse(reducer.called)
        assertContentEquals(original, image.bytes)
    }

    @Test
    fun rejectsReducerOutputThatIsNotStrictlyBelowTheThreshold() {
        val loader = ReceiptImageLoader(
            source = FakeReceiptImageContentSource(
                bytes = ByteArray(RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES + 1),
            ),
            sizeReducer = RecordingImageSizeReducer(ByteArray(RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES)),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            loader.load("content://receipts/still-too-large")
        }

        assertTrue(error.message.orEmpty().contains("below 200 KB"))
    }

    @Test
    fun exportNormalizationAlwaysProducesJpegEvenForSmallInputs() {
        val reducer = RecordingImageSizeReducer(byteArrayOf(9, 8, 7))
        val loader = ReceiptImageLoader(
            source = FakeReceiptImageContentSource(bytes = byteArrayOf(1), mimeType = "image/png"),
            sizeReducer = reducer,
        )

        val image = loader.normalizeForExport("content://receipts/small")

        assertTrue(reducer.called)
        assertEquals("image/jpeg", image.mimeType)
        assertContentEquals(byteArrayOf(9, 8, 7), image.bytes)
    }
}

private class RecordingImageSizeReducer(
    private val reducedBytes: ByteArray,
) : ReceiptImageSizeReducer {
    var called = false
    var maxBytesExclusive: Int? = null

    override fun reduce(bytes: ByteArray, maxBytesExclusive: Int): ReducedReceiptImage {
        called = true
        this.maxBytesExclusive = maxBytesExclusive
        return ReducedReceiptImage(reducedBytes, "image/jpeg")
    }
}

private class FakeReceiptImageContentSource(
    private val name: String? = null,
    private val mimeType: String? = null,
    private val bytes: ByteArray,
) : ReceiptImageContentSource {
    var lastUri: String? = null

    override fun displayName(uri: String): String? = name.also { lastUri = uri }
    override fun mimeType(uri: String): String? = mimeType.also { lastUri = uri }
    override fun readBytes(uri: String): ByteArray = bytes.also { lastUri = uri }
}
