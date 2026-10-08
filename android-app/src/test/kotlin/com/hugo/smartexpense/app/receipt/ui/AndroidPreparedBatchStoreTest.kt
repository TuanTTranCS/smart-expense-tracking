package com.hugo.smartexpense.app.receipt.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import com.hugo.smartexpense.extraction.ReceiptImage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPreparedBatchStoreTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private fun image():ReceiptImage {
        val bitmap=Bitmap.createBitmap(512,768,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray() }
        bitmap.recycle();return ReceiptImage("receipt.png",bytes,"image/png")
    }
    @Test fun roomRestoresDraftNotesReviewsIdentityAndCancelsInterruptedWorkWithoutInference()=runBlocking {
        context.deleteDatabase("receipt-batch.db")
        val store=AndroidPreparedBatchStore(context)
        val input=image();val uri=store.stage("recover",input)
        val review=ReceiptReviewState(merchantName="Corrected",receiptDate="2026-10-05",totalAmount="12.34",notes="edited notes",sourceImageUri=uri,exportExpenseId="immutable-export",exportJsonPreview="saved JSON",sourceType=ReceiptSourceType.IMAGE)
        store.save(listOf(BatchItemState("recover",7,"content://original",uri,"image/png",reduceOversizedImages=false,status=BatchItemStatus.PROCESSING,draft=ManualExpenseDraft(notes="original notes"),transactions=listOf(BatchTransaction("stable-tx",7,review)))))
        val restored=store.load().single()
        assertEquals("recover",restored.itemId);assertEquals(7L,restored.sourceRevision);assertFalse(restored.reduceOversizedImages)
        assertEquals(BatchItemStatus.CANCELLED,restored.status);assertEquals("original notes",restored.draft.notes)
        assertEquals("stable-tx",restored.transactions.single().id);assertEquals(review,restored.transactions.single().review)
        assertContentEquals(input.bytes,store.read(uri,"image/png").bytes)
        val thumb=store.thumbnail(uri,"image/png");assertTrue(thumb.bytes.size < input.bytes.size || thumb.mimeType=="image/jpeg")
        assertTrue(BitmapFactorySize(thumb.bytes)<=256)
        store.close()
    }
    @Test fun retryableExportProtectsSourceThroughRemovalAndCleanup()=runBlocking {
        context.deleteDatabase("receipt-batch.db")
        val store=AndroidPreparedBatchStore(context);val uri=store.stage("export-source",image())
        store.protect(uri);store.save(emptyList());store.release(uri);store.load()
        assertTrue(File(java.net.URI(uri)).isFile)
        store.unprotect(uri);store.release(uri);assertFalse(File(java.net.URI(uri)).exists());store.close()
    }

    @Test fun freeTextDraftAndNormalizedResultSurviveRoomReopenIndependently()=runBlocking {
        context.deleteDatabase("receipt-batch.db")
        val original=ManualExpenseDraft("", "15+50, which is 65 in total", "Canadian dollars", "Oct 7 2026", "  Walmrat\nnotes  ")
        val review=ReceiptReviewState(merchantName="Walmart",receiptDate="2026-10-07",totalAmount="65",currency="CAD",notes=original.notes,sourceType=ReceiptSourceType.TYPED,manualEntryRequired=false)
        val item=BatchItemState(itemId="raw-text",sourceRevision=9,noImage=true,status=BatchItemStatus.SUCCEEDED,draft=original,transactions=listOf(BatchTransaction("normalized",9,review)))
        AndroidPreparedBatchStore(context).let { store->store.save(listOf(item));store.close() }
        AndroidPreparedBatchStore(context).let { reopened->
            val restored=reopened.load().single()
            assertEquals(item,restored);assertEquals(original,restored.draft);assertEquals(review,restored.transactions.single().review)
            reopened.close()
        }
    }

    @Test fun invalidImageBytesAreRejectedBeforeStaging()=runBlocking {
        context.deleteDatabase("receipt-batch.db")
        val store=AndroidPreparedBatchStore(context)
        assertFailsWith<IllegalArgumentException> { store.stage("unreadable",ReceiptImage("bad.jpg",byteArrayOf(1,2,3),"image/jpeg")) }
        assertFalse(File(context.filesDir,"prepared-batch/unreadable.image").exists());store.close()
    }
    private fun BitmapFactorySize(bytes:ByteArray):Int {
        val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        return maxOf(options.outWidth,options.outHeight)
    }
}
