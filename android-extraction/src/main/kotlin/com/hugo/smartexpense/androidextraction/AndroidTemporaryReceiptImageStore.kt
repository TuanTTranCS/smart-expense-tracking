package com.hugo.smartexpense.androidextraction

import com.hugo.smartexpense.extraction.ReceiptImage
import com.hugo.smartexpense.extraction.StagedReceiptImage
import com.hugo.smartexpense.extraction.TemporaryReceiptImageStore
import java.io.File

class AndroidTemporaryReceiptImageStore(
    private val cacheDirectory: File,
) : TemporaryReceiptImageStore {
    override fun stage(receiptImage: ReceiptImage): StagedReceiptImage {
        val suffix = receiptImage.sourceName.substringAfterLast('.', ".bin")
            .let { if (it.startsWith('.')) it else ".$it" }
        val tempFile = File.createTempFile("receipt_", suffix, cacheDirectory)
        tempFile.writeBytes(receiptImage.bytes)
        return FileBackedStagedReceiptImage(tempFile)
    }
}

private class FileBackedStagedReceiptImage(
    private val file: File,
) : StagedReceiptImage {
    override val absolutePath: String
        get() = file.absolutePath

    override fun close() {
        file.delete()
    }
}
