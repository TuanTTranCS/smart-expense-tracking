package com.hugo.smartexpense.app.receiptexport

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.hugo.smartexpense.app.graphauth.GraphAccount
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationClient
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptHistoryImagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var account: String? = "account"
    private var local: ByteArray? = null
    private var reads = 0
    private var authenticationFailure: Exception? = null
    private var accountLoadFailure: Exception? = null
    private var tokenCalls = 0
    private val requests = mutableListOf<GraphContentRequest>()
    private val record = sampleRecord().copy(exportAccountId = "account", exportDriveId = "drive")
    private val auth = object : GraphAuthenticationClient {
        override suspend fun loadCurrentAccount(): GraphAccount? {
            accountLoadFailure?.let { throw it }
            return account?.let { GraphAccount(it, it) }
        }
        override suspend fun acquireAccessTokenSilently(): String {
            tokenCalls++
            authenticationFailure?.let { throw it }
            return "private-token"
        }
        override suspend fun signIn(activity: Activity) = error("unused")
        override suspend fun signOut() { account = null }
    }
    private fun jpeg(width: Int = 30, height: Int = 60): ByteArray = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).let {
            it.compress(Bitmap.CompressFormat.JPEG, 90, out); it.recycle()
        }
        out.toByteArray()
    }
    private fun loader(reply: (GraphContentRequest) -> GraphContentResponse = {
        if (it.url.contains("?")) GraphContentResponse(200, "{\"id\":\"drive\"}")
        else GraphContentResponse(200, bodyBytes = jpeg())
    }) = AndroidReceiptHistoryImageLoader(context, ReceiptExportImageReader {
        reads++; local ?: error("missing")
    }, auth, GraphContentTransport { requests += it; reply(it) })

    @Before fun clear() {
        File(context.cacheDir, "receipt-history-images").deleteRecursively()
    }
    @Test fun typedExpensesPerformZeroImageOrNetworkOperations() = runTest {
        assertEquals(ReceiptHistoryImageResult.NoImage, loader().load(sampleTypedRecord(), true, true))
        assertEquals(0, reads); assertTrue(requests.isEmpty())
    }
    @Test fun localNormalizedExportWorksOfflineEvenWithoutLegacyBindings() = runTest {
        local = jpeg(); account = null
        val result = loader().load(record.copy(exportAccountId = null, exportDriveId = null), false, false)
        assertContentEquals(local, assertIs<ReceiptHistoryImageResult.Available>(result, result.toString()).bytes)
        assertTrue(requests.isEmpty())
    }
    @Test fun localExportRemainsReadableWhenAccountLoadingIsUnavailable() = runTest {
        local = jpeg()
        for (failure in listOf(GraphAuthenticationException.NotConfigured(),
            GraphAuthenticationException.NetworkUnavailable(), GraphAuthenticationException.InteractionRequired())) {
            accountLoadFailure = failure
            val result = loader().load(record, true, false)
            assertContentEquals(local, assertIs<ReceiptHistoryImageResult.Available>(result, result.toString()).bytes)
            assertIs<ReceiptHistoryImageResult.Available>(loader().load(record, false, true))
        }
        assertEquals(0, tokenCalls)
        assertTrue(requests.isEmpty())
    }
    @Test fun unavailableAccountLoadingDoesNotBypassStrictRemoteRecoveryChecks() = runTest {
        accountLoadFailure = GraphAuthenticationException.NetworkUnavailable()
        assertIs<ReceiptHistoryImageResult.Unavailable>(loader().load(record, true, false))
        assertEquals(0, tokenCalls)
        assertTrue(requests.isEmpty())
    }
    @Test fun cancellationDuringLocalAccountCaptureStillPropagates() = runTest {
        local = jpeg()
        accountLoadFailure = CancellationException("viewer closed")
        assertFailsWith<CancellationException> { loader().load(record, false, false) }
        assertEquals(0, reads)
        assertEquals(0, tokenCalls)
        assertTrue(requests.isEmpty())
    }
    @Test fun thumbnailsAreBoundedAndEveryMemberUsesItsOwnImmutableReference() = runTest {
        val paths = mutableListOf<String>()
        val sources = mapOf("first.jpg" to jpeg(800, 1600), "second.jpg" to jpeg(1600, 800))
        val resolver = AndroidReceiptHistoryImageLoader(context, ReceiptExportImageReader { path ->
            paths += path; sources.getValue(path)
        }, auth, GraphContentTransport { error("local viewing must not contact Graph") })
        val members = listOf(record.copy(normalizedImageLocalPath = "first.jpg"),
            record.copy(expenseId = "second-transaction", normalizedImageLocalPath = "second.jpg", batchId = "batch"),
            record.copy(expenseId = "shared-source-transaction", normalizedImageLocalPath = "first.jpg", batchId = "batch"))
        members.forEach { member ->
            val result = assertIs<ReceiptHistoryImageResult.Available>(resolver.load(member, false, true))
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size, bounds)
            assertTrue(bounds.outWidth in 1..192 && bounds.outHeight in 1..192)
        }
        assertEquals(listOf("first.jpg", "second.jpg", "first.jpg"), paths)
        val full = assertIs<ReceiptHistoryImageResult.Available>(resolver.load(members.first(), false, false))
        assertContentEquals(sources.getValue("first.jpg"), full.bytes)
    }
    @Test fun missingAndCorruptLocalRemainLazyThenExplicitRetryDownloadsAndSurvivesLoaderRestart() = runTest {
        for (bytes in listOf(null, byteArrayOf(1, 2))) {
            local = bytes
            assertIs<ReceiptHistoryImageResult.Unavailable>(loader().load(record, false, true))
            assertTrue(requests.isEmpty())
        }
        assertIs<ReceiptHistoryImageResult.Available>(loader().load(record, true, false))
        assertEquals(2, requests.size)
        requests.clear()
        assertIs<ReceiptHistoryImageResult.Available>(loader().load(record.copy(expenseId = "batch-member"), false, true))
        assertTrue(requests.isEmpty())
    }
    @Test fun unknownAccountDriveAndAccountMismatchNeverDownload() = runTest {
        for (entry in listOf(record.copy(exportAccountId = null), record.copy(exportDriveId = null), record.copy(exportAccountId = "other"))) {
            assertIs<ReceiptHistoryImageResult.Unavailable>(loader().load(entry, true, false))
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun mismatchingDriveStopsBeforeContentDownload() = runTest {
        assertIs<ReceiptHistoryImageResult.Unavailable>(loader { GraphContentResponse(200, "{\"id\":\"other\"}") }.load(record, true, false))
        assertEquals(1, requests.size)
    }
    @Test fun accountSwitchDuringNetworkDiscardsResultAndDoesNotCache() = runTest {
        val first = loader {
            if (it.url.contains("?")) GraphContentResponse(200, "{\"id\":\"drive\"}")
            else { account = "other"; GraphContentResponse(200, bodyBytes = jpeg()) }
        }.load(record, true, false)
        assertIs<ReceiptHistoryImageResult.Unavailable>(first)
        account = "account"
        assertIs<ReceiptHistoryImageResult.Unavailable>(loader().load(record, false, false))
    }
    @Test fun accountSwitchDuringLocalReadDiscardsStaleCompletion() = runTest {
        val resolver = AndroidReceiptHistoryImageLoader(context, ReceiptExportImageReader {
            account = "other"; jpeg()
        }, auth, GraphContentTransport { error("must not download") })
        assertIs<ReceiptHistoryImageResult.Unavailable>(resolver.load(record, false, false))
    }
    @Test fun failuresRemainRetryableAndNeverPoisonCache() = runTest {
        for (status in listOf(401, 403, 404, 429, 500)) {
            val result = loader { if (it.url.contains("?")) GraphContentResponse(200, "{\"id\":\"drive\"}") else GraphContentResponse(status) }.load(record, true, false)
            assertTrue(assertIs<ReceiptHistoryImageResult.Unavailable>(result).retryable)
        }
        assertIs<ReceiptHistoryImageResult.Unavailable>(loader { if (it.url.contains("?")) GraphContentResponse(200, "{\"id\":\"drive\"}") else GraphContentResponse(200, bodyBytes = byteArrayOf(1)) }.load(record, true, false))
        assertIs<ReceiptHistoryImageResult.Available>(loader().load(record, true, false))
    }
    @Test fun authenticationAndOfflineFailuresExposeActionsAndExplicitRetryCanRecover() = runTest {
        for ((failure, expected) in listOf(
            GraphAuthenticationException.NetworkUnavailable() to "internet",
            GraphAuthenticationException.InteractionRequired() to "Reconnect",
            GraphAuthenticationException.ConsentDeclined() to "permission",
        )) {
            authenticationFailure = failure
            val result = assertIs<ReceiptHistoryImageResult.Unavailable>(loader().load(record, true, false))
            assertTrue(result.retryable)
            assertTrue(result.message.contains(expected), result.message)
            assertTrue(requests.isEmpty())
        }
        authenticationFailure = null
        val offline = assertIs<ReceiptHistoryImageResult.Unavailable>(loader {
            throw java.io.IOException("private server diagnostics")
        }.load(record, true, false))
        assertTrue(offline.message.contains("connection"))
        assertFalse(offline.message.contains("private server diagnostics"))
        assertIs<ReceiptHistoryImageResult.Available>(loader().load(record, true, false))
    }
    @Test fun redirectsNeverForwardBearerAndUnsafeRedirectsFail() = runTest {
        val result = loader {
            when {
                it.url.contains("?") -> GraphContentResponse(200, "{\"id\":\"drive\"}")
                it.url.contains("graph.microsoft") -> GraphContentResponse(302, headers = mapOf("Location" to "https://download.example/image"))
                else -> { assertTrue(it.headers.isEmpty()); GraphContentResponse(200, bodyBytes = jpeg()) }
            }
        }.load(record, true, false)
        assertIs<ReceiptHistoryImageResult.Available>(result)
        clear()
        assertIs<ReceiptHistoryImageResult.Unavailable>(loader {
            if (it.url.contains("?")) GraphContentResponse(200, "{\"id\":\"drive\"}")
            else GraphContentResponse(302, headers = mapOf("Location" to "http://unsafe.example/image"))
        }.load(record, true, false))
    }
    @Test fun cancellationPropagatesWithoutCaching() = runTest {
        assertFailsWith<CancellationException> { loader { throw CancellationException("closed") }.load(record, true, false) }
        assertIs<ReceiptHistoryImageResult.Unavailable>(loader().load(record, false, false))
    }
    @Test fun traversalReferenceCannotReachNetwork() = runTest {
        assertIs<ReceiptHistoryImageResult.Unavailable>(loader().load(record.copy(receiptImageRelativePath = "$RECEIPT_IMAGE_FOLDER/../other.jpg"), true, false))
        assertTrue(requests.isEmpty())
    }
    @Test fun cacheIsBoundedIsolatedAndEvictsOnlyItsOwnEntries() {
        val directory = File(context.cacheDir, "cache-unit").apply { deleteRecursively(); mkdirs() }
        try {
            val foreign = File(directory, "retained-export.jpg").apply { writeBytes(byteArrayOf(9)) }
            val stale = File(directory, "download-old.tmp").apply { writeText("partial"); setLastModified(1) }
            val recent = File(directory, "download-active.tmp").apply { writeText("active") }
            val cache = ReceiptHistoryImageCache(directory, 4)
            cache.write("account-drive-first", byteArrayOf(1, 2, 3))
            cache.write("account-drive-second", byteArrayOf(4, 5, 6))
            assertNull(cache.read("account-drive-first"))
            assertContentEquals(byteArrayOf(4, 5, 6), cache.read("account-drive-second"))
            assertNull(cache.read("other-account-drive-second"))
            assertTrue(foreign.exists())
            assertFalse(stale.exists())
            assertTrue(recent.exists())
            assertFails { cache.write("large", ByteArray(5)) }
            assertContentEquals(byteArrayOf(4, 5, 6), cache.read("account-drive-second"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun cacheWriteFailurePreservesExistingFiles() {
        val blocker = File(context.cacheDir, "cache-blocker").apply { writeText("retained") }
        try {
            assertFails { ReceiptHistoryImageCache(blocker).write("image", byteArrayOf(1)) }
            assertEquals("retained", blocker.readText())
        } finally { blocker.delete() }
    }
    @Test fun atomicReplacementFailureRetainsPreviousEntryAndCleansPartialWrite() {
        val directory = File(context.cacheDir, "atomic-cache-unit").apply { deleteRecursively() }
        try {
            val healthy = ReceiptHistoryImageCache(directory)
            healthy.write("same-image", byteArrayOf(1, 2))
            val failing = ReceiptHistoryImageCache(directory, replaceAtomically = { source, _ ->
                assertContentEquals(byteArrayOf(3, 4), source.readBytes())
                throw java.io.IOException("simulated storage failure before atomic replacement")
            })
            assertFailsWith<java.io.IOException> { failing.write("same-image", byteArrayOf(3, 4)) }
            assertContentEquals(byteArrayOf(1, 2), healthy.read("same-image"))
            assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
        } finally { directory.deleteRecursively() }
    }
}
