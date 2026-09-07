package com.hugo.smartexpense.app.receiptexport

import android.content.Context
import com.hugo.smartexpense.app.ReceiptImageLoader
import java.io.File

class AndroidReceiptExportImageStore(
    context: Context,
    private val imageLoader: ReceiptImageLoader,
) : ReceiptExportImagePreparer, ReceiptExportImageReader {
    private val exportDirectory = File(context.filesDir, "receipt-exports")

    override fun prepare(sourceUri: String, stableFileName: String): PreparedReceiptImage {
        val image = imageLoader.normalizeForExport(sourceUri)
        check(exportDirectory.exists() || exportDirectory.mkdirs()) {
            "Local receipt export storage could not be created."
        }
        val destination = File(exportDirectory, stableFileName)
        destination.outputStream().use { it.write(image.bytes) }
        return PreparedReceiptImage(destination.absolutePath, image.bytes.size)
    }

    override fun read(localPath: String): ByteArray {
        val file = File(localPath).canonicalFile
        require(file.parentFile == exportDirectory.canonicalFile) { "Invalid local receipt export path." }
        return file.readBytes()
    }
}
