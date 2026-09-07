package com.hugo.smartexpense.app

import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import com.hugo.smartexpense.extraction.ReceiptImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES: Int = 200 * 1024

interface ReceiptImageContentSource {
    fun displayName(uri: String): String?
    fun mimeType(uri: String): String?
    fun readBytes(uri: String): ByteArray
}

data class ReducedReceiptImage(
    val bytes: ByteArray,
    val mimeType: String,
)

fun interface ReceiptImageSizeReducer {
    fun reduce(bytes: ByteArray, maxBytesExclusive: Int): ReducedReceiptImage
}

class ReceiptImageLoader(
    private val source: ReceiptImageContentSource,
    private val sizeReducer: ReceiptImageSizeReducer? = null,
) {
    fun normalizeForExport(uri: String): ReceiptImage {
        val bytes = source.readBytes(uri)
        require(bytes.isNotEmpty()) { "The selected receipt image is empty." }
        val reducer = checkNotNull(sizeReducer) { "Image normalization is not available." }
        val reduced = reducer.reduce(bytes, RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES)
        require(reduced.bytes.isNotEmpty()) { "Image normalization produced an empty image." }
        require(reduced.mimeType == "image/jpeg") { "The exported receipt image must be JPEG." }
        require(reduced.bytes.size < RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES) {
            "The exported receipt image could not be reduced below 200 KB."
        }
        return ReceiptImage(
            sourceName = source.displayName(uri)?.takeIf(String::isNotBlank) ?: "receipt-image.jpg",
            bytes = reduced.bytes,
            mimeType = reduced.mimeType,
        )
    }

    fun load(uri: String, reduceIfOversized: Boolean = true): ReceiptImage {
        val bytes = source.readBytes(uri)
        require(bytes.isNotEmpty()) { "The selected receipt image is empty." }

        val sourceMimeType = source.mimeType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val reduced = if (reduceIfOversized && bytes.size > RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES) {
            val reducer = checkNotNull(sizeReducer) { "Image size reduction is not available." }
            reducer.reduce(bytes, RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES).also {
                require(it.bytes.isNotEmpty()) { "Image size reduction produced an empty image." }
                require(it.bytes.size < RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES) {
                    "The selected receipt image could not be reduced below 200 KB."
                }
            }
        } else {
            ReducedReceiptImage(bytes, sourceMimeType)
        }

        return ReceiptImage(
            sourceName = source.displayName(uri)?.takeIf(String::isNotBlank) ?: "receipt-image",
            bytes = reduced.bytes,
            mimeType = reduced.mimeType,
        )
    }
}

class AndroidReceiptImageSizeReducer : ReceiptImageSizeReducer {
    override fun reduce(bytes: ByteArray, maxBytesExclusive: Int): ReducedReceiptImage {
        require(bytes.isNotEmpty()) { "The selected receipt image is empty." }
        require(maxBytesExclusive > 0) { "The image size limit must be positive." }

        var bitmap = decodeDownsampled(bytes, maxBytesExclusive)
        val output = ByteArrayOutputStream()

        try {
            while (true) {
                for (quality in JPEG_QUALITIES) {
                    output.reset()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        "The selected receipt image could not be encoded."
                    }
                    if (output.size() < maxBytesExclusive) {
                        return ReducedReceiptImage(output.toByteArray(), "image/jpeg")
                    }
                }

                if (bitmap.width == 1 && bitmap.height == 1) break

                val nextWidth = reducedDimension(bitmap.width)
                val nextHeight = reducedDimension(bitmap.height)
                val scaled = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
        } finally {
            bitmap.recycle()
            output.close()
        }

        throw IOException("The selected receipt image could not be reduced below 200 KB.")
    }

    private fun decodeDownsampled(bytes: ByteArray, maxBytesExclusive: Int): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val initialScale = sqrt(
                (maxBytesExclusive * INITIAL_SIZE_HEADROOM) / bytes.size.toDouble(),
            ).coerceAtMost(1.0)
            if (initialScale < 1.0) {
                decoder.setTargetSize(
                    max(1, (info.size.width * initialScale).roundToInt()),
                    max(1, (info.size.height * initialScale).roundToInt()),
                )
            }
        }
    }

    private fun reducedDimension(current: Int): Int =
        max(1, (current * DIMENSION_REDUCTION_FACTOR).roundToInt().coerceAtMost(current - 1))

    private companion object {
        val JPEG_QUALITIES = 90 downTo 30 step 10
        const val INITIAL_SIZE_HEADROOM = 0.85
        const val DIMENSION_REDUCTION_FACTOR = 0.8
    }
}

class AndroidReceiptImageContentSource(
    private val contentResolver: ContentResolver,
) : ReceiptImageContentSource {
    override fun displayName(uri: String): String? {
        val parsedUri = Uri.parse(uri)
        val cursor: Cursor = contentResolver.query(
            parsedUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return parsedUri.lastPathSegment

        return cursor.use {
            if (!it.moveToFirst()) return@use parsedUri.lastPathSegment
            val columnIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex >= 0) it.getString(columnIndex) else parsedUri.lastPathSegment
        }
    }

    override fun mimeType(uri: String): String? = contentResolver.getType(Uri.parse(uri))

    override fun readBytes(uri: String): ByteArray = contentResolver.openInputStream(Uri.parse(uri))?.use {
        it.readBytes()
    } ?: throw IOException("The selected receipt image could not be opened.")
}
