package com.hugo.smartexpense.app.receiptexport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hugo.smartexpense.app.ReceiptSourceType
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationClient
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

fun interface ReceiptHistoryImageLoader {
    suspend fun load(record: ReceiptExportRecord, allowDownload: Boolean, thumbnail: Boolean): ReceiptHistoryImageResult
}

sealed interface ReceiptHistoryImageResult {
    data class Available(val bytes: ByteArray) : ReceiptHistoryImageResult
    data class Unavailable(val message: String, val retryable: Boolean = true) : ReceiptHistoryImageResult
    data object NoImage : ReceiptHistoryImageResult
}

/** Reads only the immutable normalized export image, never the picker original. */
class AndroidReceiptHistoryImageLoader(
    context: Context,
    private val imageReader: ReceiptExportImageReader,
    private val authenticationClient: GraphAuthenticationClient,
    private val transport: GraphContentTransport = AndroidGraphContentTransport(),
) : ReceiptHistoryImageLoader {
    private val cache = ReceiptHistoryImageCache(File(context.cacheDir, "receipt-history-images"))
    private val mutex = Mutex()

    override suspend fun load(record: ReceiptExportRecord, allowDownload: Boolean, thumbnail: Boolean): ReceiptHistoryImageResult =
        withContext(Dispatchers.IO) {
            if (record.sourceType == ReceiptSourceType.TYPED) return@withContext ReceiptHistoryImageResult.NoImage
            mutex.withLock {
                try {
                    val startingAccount = localAccountIdentity()
                    record.normalizedImageLocalPath?.let { path ->
                        val local = try { imageReader.read(path) } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        local?.let { decode(it, thumbnail) }?.let {
                            coroutineContext.ensureActive()
                            if (localAccountIdentity() != startingAccount)
                                return@withLock unavailable("OneDrive account changed. Open the receipt again.")
                            return@withLock ReceiptHistoryImageResult.Available(it)
                        }
                    }
                    val account = record.exportAccountId?.takeIf(String::isNotBlank)
                        ?: return@withLock unavailable("This older export has no verified OneDrive account. Its local receipt copy is unavailable.", false)
                    val drive = record.exportDriveId?.takeIf(String::isNotBlank)
                        ?: return@withLock unavailable("This older export has no verified OneDrive drive. Its local receipt copy is unavailable.", false)
                    val path = record.receiptImageRelativePath?.takeIf(::validImagePath)
                        ?: return@withLock unavailable("The saved receipt image reference is unavailable.", false)
                    checkAccount(account)
                    val identity = listOf(account, drive, path).joinToString("\u0000")
                    cache.read(identity)?.let { bytes ->
                        decode(bytes, thumbnail)?.let {
                            checkAccount(account)
                            coroutineContext.ensureActive()
                            return@withLock ReceiptHistoryImageResult.Available(it)
                        }
                    }
                    if (!allowDownload) return@withLock unavailable("Receipt is not cached. Open it to download from OneDrive.")
                    val token = authenticationClient.acquireAccessTokenSilently()
                    checkAccount(account)
                    val driveResponse = request("$ROOT/me/drive?\$select=id", token, false)
                    requireSuccess(driveResponse)
                    if (JSONObject(driveResponse.body).getString("id") != drive)
                        return@withLock unavailable("Reconnect the OneDrive drive used for this export.")
                    checkAccount(account)
                    var response = request("$ROOT/drives/${encode(drive)}/root:/${path.split('/').joinToString("/") { encode(it) }}:/content", token, true)
                    // Graph content redirects to an ephemeral signed URL. Never forward bearer credentials.
                    repeat(3) {
                        if (response.statusCode in listOf(301, 302, 303, 307, 308)) {
                            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
                                ?: throw ImageFailure("OneDrive did not provide a receipt download location.")
                            val uri = URI(location)
                            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null)
                                throw ImageFailure("OneDrive returned an unsafe receipt download location.")
                            checkAccount(account)
                            response = request(location, null, true)
                        }
                    }
                    requireSuccess(response)
                    checkAccount(account)
                    coroutineContext.ensureActive()
                    val bytes = response.bodyBytes ?: throw ImageFailure("OneDrive returned an invalid receipt image.")
                    val rendered = decode(bytes, thumbnail) ?: throw ImageFailure("The receipt image is invalid or too large. Retry to download again.")
                    cache.write(identity, bytes)
                    checkAccount(account)
                    coroutineContext.ensureActive()
                    ReceiptHistoryImageResult.Available(rendered)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: ImageFailure) { unavailable(failure.message.orEmpty()) }
                catch (_: GraphAuthenticationException.ConsentDeclined) { unavailable("OneDrive permission was denied. Reconnect and approve file access.") }
                catch (_: GraphAuthenticationException.NetworkUnavailable) { unavailable("Offline. Connect to the internet and retry.") }
                catch (_: GraphAuthenticationException) { unavailable("Reconnect OneDrive and retry.") }
                catch (_: Exception) { unavailable("Receipt download is unavailable. Check your connection and retry.") }
            }
        }

    /** Local exported bytes remain readable when Graph is offline or not configured. */
    private suspend fun localAccountIdentity(): String? {
        coroutineContext.ensureActive()
        val identity = try { authenticationClient.loadCurrentAccount()?.id }
            catch (_: GraphAuthenticationException) { null }
        coroutineContext.ensureActive()
        return identity
    }

    private suspend fun checkAccount(account: String) {
        coroutineContext.ensureActive()
        if (authenticationClient.loadCurrentAccount()?.id != account)
            throw ImageFailure("Reconnect the OneDrive account used for this export.")
    }

    private suspend fun request(url: String, token: String?, binary: Boolean): GraphContentResponse {
        coroutineContext.ensureActive()
        return transport.execute(GraphContentRequest("GET", url,
            if (token == null) emptyMap() else mapOf("Authorization" to "Bearer $token"),
            binaryResponse = binary, maxResponseBytes = if (binary) MAX_IMAGE_BYTES else 64 * 1024))
            .also { coroutineContext.ensureActive() }
    }

    private fun requireSuccess(response: GraphContentResponse) {
        if (response.statusCode !in 200..299) throw ImageFailure(when (response.statusCode) {
            401 -> "Reconnect OneDrive and retry."
            403 -> "OneDrive permission was denied. Reconnect and approve file access."
            404 -> "The receipt image was not found in OneDrive."
            429 -> "OneDrive is busy. Retry later."
            else -> "OneDrive could not download the receipt. Retry later."
        })
    }

    private fun unavailable(message: String, retryable: Boolean = true) = ReceiptHistoryImageResult.Unavailable(message, retryable)
    private class ImageFailure(message: String) : Exception(message)

    private companion object {
        const val ROOT = "https://graph.microsoft.com/v1.0"
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        fun validImagePath(path: String): Boolean = path.startsWith("$RECEIPT_IMAGE_FOLDER/") &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." || '\\' in it || '\u0000' in it }
    }
}

private const val MAX_IMAGE_BYTES = 200 * 1024

/** Bounded decode and output keep oversized dimensions from allocating a full camera bitmap. */
private fun decode(bytes: ByteArray, thumbnail: Boolean): ByteArray? {
    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
        bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L || bounds.outMimeType != "image/jpeg") return null
    val target = if (thumbnail) 192 else 2048
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > target) options.inSampleSize *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return try {
        if (!thumbnail) return bytes
        ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) null else out.toByteArray()
        }
    } finally { bitmap.recycle() }
}

/** Owns only hashed .jpg cache entries; eviction cannot touch retained export files. */
internal class ReceiptHistoryImageCache(
    private val directory: File,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val replaceAtomically: (File, File) -> Unit = { source, destination ->
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    private fun file(identity: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.jpg")
    }
    @Synchronized fun read(identity: String): ByteArray? = try {
        val entry = file(identity)
        if (!entry.isFile || entry.length() !in 1..MAX_IMAGE_BYTES) null
        else entry.readBytes().also { entry.setLastModified(System.currentTimeMillis()) }
    } catch (_: Exception) { null }

    @Synchronized fun write(identity: String, bytes: ByteArray) {
        require(bytes.size in 1..MAX_IMAGE_BYTES && bytes.size <= maxBytes)
        check(directory.isDirectory || directory.mkdirs())
        // A crash may leave a partial write. Restrict cleanup to our temporary names,
        // and leave recent writes alone in case another loader is still using them.
        val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000
        directory.listFiles().orEmpty().filter {
            it.isFile && it.name.startsWith("download-") && it.name.endsWith(".tmp") && it.lastModified() < staleBefore
        }.forEach { it.delete() }
        val entry = file(identity)
        val temporary = File.createTempFile("download-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            replaceAtomically(temporary, entry)
            val entries = directory.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.jpg")) }
            var size = entries.sumOf(File::length)
            entries.sortedBy(File::lastModified).forEach { candidate ->
                if (size > maxBytes && candidate != entry) {
                    val length = candidate.length()
                    if (candidate.delete()) size -= length
                }
            }
        } finally { temporary.delete() }
    }
}
