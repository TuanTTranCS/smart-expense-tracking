package com.hugo.smartexpense.app.receiptexport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hugo.smartexpense.app.ReceiptImageContentSource
import com.hugo.smartexpense.app.ReceiptImageLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidReceiptExportImageStoreTest {
    @Test fun previewReadsOnlyBoundedPrivateExportsAndCacheEvictionPreservesThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidReceiptExportImageStore(context, ReceiptImageLoader(object : ReceiptImageContentSource {
            override fun displayName(uri: String): String? = error("Preview must not read the picker")
            override fun mimeType(uri: String): String? = error("Preview must not read the picker")
            override fun readBytes(uri: String): ByteArray = error("Preview must not read the picker")
        }))
        val exported = File(context.filesDir, "receipt-exports/retained-preview-test.jpg")
        val foreign = File(context.filesDir, "foreign-preview-test.jpg")
        val cacheDirectory = File(context.cacheDir, "isolated-preview-test").apply { mkdirs() }
        exported.parentFile!!.mkdirs()
        try {
            val bytes = byteArrayOf(1, 2, 3)
            exported.writeBytes(bytes)
            foreign.writeBytes(bytes)
            assertContentEquals(bytes, store.read(exported.absolutePath))
            assertFailsWith<IllegalArgumentException> { store.read(foreign.absolutePath) }
            assertFailsWith<IllegalArgumentException> { store.read(File(exported.parentFile, "missing.jpg").absolutePath) }
            val cache = ReceiptHistoryImageCache(cacheDirectory, maxBytes = 4)
            cache.write("first", byteArrayOf(4, 5, 6))
            cache.write("second", byteArrayOf(7, 8, 9))
            assertContentEquals(bytes, store.read(exported.absolutePath))
            assertTrue(foreign.isFile)
            exported.writeBytes(ByteArray(200 * 1024))
            assertFailsWith<IllegalArgumentException> { store.read(exported.absolutePath) }
            exported.writeBytes(byteArrayOf())
            assertFailsWith<IllegalArgumentException> { store.read(exported.absolutePath) }
        } finally {
            exported.delete()
            foreign.delete()
            cacheDirectory.listFiles().orEmpty().forEach { it.delete() }
            cacheDirectory.delete()
        }
    }
}
