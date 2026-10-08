package com.hugo.smartexpense.app.receipt.ui

import android.content.Context
import androidx.room.*
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import com.hugo.smartexpense.extraction.ReceiptImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Entity(tableName = "batch_draft")
data class BatchDraftEntity(@PrimaryKey val id: Int = 1, val payload: String)
@Entity(tableName = "export_prepared_references")
data class ExportPreparedReference(@PrimaryKey val uri: String)
@Dao interface BatchDraftDao {
    @Query("SELECT uri FROM export_prepared_references") suspend fun protectedUris(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun protect(reference: ExportPreparedReference)
    @Query("DELETE FROM export_prepared_references WHERE uri = :uri") suspend fun unprotect(uri: String)
    @Query("SELECT * FROM batch_draft WHERE id = 1") suspend fun load(): BatchDraftEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: BatchDraftEntity)
}
@Database(entities = [BatchDraftEntity::class, ExportPreparedReference::class], version = 1, exportSchema = true)
abstract class BatchDraftDatabase : RoomDatabase() { abstract fun dao(): BatchDraftDao }
class AndroidPreparedBatchStore(context: Context) : PreparedBatchStore {
    private val directory = File(context.filesDir, "prepared-batch").apply { mkdirs() }
    private val database = Room.databaseBuilder(context.applicationContext, BatchDraftDatabase::class.java, "receipt-batch.db").build()
    internal fun close() { database.close() }
    override suspend fun stage(itemId: String, image: ReceiptImage): String {
        require(itemId.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid prepared image ID." }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image cannot be decoded. Remove and reselect it." }
        val file = File(directory, "$itemId.image")
        val temporary = File(directory, "$itemId.tmp")
        temporary.writeBytes(image.bytes)
        check(temporary.renameTo(file)) { "Unable to stage receipt image." }
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.let { bitmap ->
            File(directory, "$itemId.thumb.jpg").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
        } ?: run { file.delete(); error("This image cannot be decoded. Remove and reselect it.") }
        return file.toURI().toString()
    }
    override suspend fun read(uri: String, mimeType: String): ReceiptImage {
        val file = File(java.net.URI(uri))
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "Invalid prepared image reference." }
        return ReceiptImage(file.name, file.readBytes(), mimeType)
    }
    override suspend fun thumbnail(uri: String, mimeType: String): ReceiptImage {
        val source = File(java.net.URI(uri))
        require(source.canonicalFile.parentFile == directory.canonicalFile)
        val thumbnail = File(directory, source.nameWithoutExtension + ".thumb.jpg")
        return if (thumbnail.exists()) ReceiptImage(thumbnail.name, thumbnail.readBytes(), "image/jpeg") else read(uri, mimeType)
    }
    override suspend fun protect(uri: String) { database.dao().protect(ExportPreparedReference(uri)) }
    override suspend fun unprotect(uri: String) { database.dao().unprotect(uri) }
    override suspend fun release(uri: String) {
        if (uri in database.dao().protectedUris()) return
        val file = File(java.net.URI(uri))
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        file.delete()
        File(directory, file.nameWithoutExtension + ".thumb.jpg").delete()
    }
    override suspend fun save(items: List<BatchItemState>) {
        val payload = JSONArray()
        items.forEach { item -> payload.put(JSONObject().apply {
            put("id", item.itemId); put("revision", item.sourceRevision); put("uri", item.sourceUri); put("prepared", item.preparedUri)
            put("reduce", item.reduceOversizedImages); put("failureRaw", item.rawFailureOutput); put("mime", item.mimeType); put("typed", item.noImage); put("status", item.status.name); put("expanded", item.expanded); put("edited", item.edited)
            put("draft", JSONArray(listOf(item.draft.merchant, item.draft.amount, item.draft.currency, item.draft.date, item.draft.notes)))
            put("transactions", JSONArray().apply { item.transactions.forEach { tx -> put(JSONObject().apply {
                put("id", tx.id); put("revision", tx.revision)
                val r = tx.review
                put("review", JSONObject().apply {
                    put("date", r.receiptDate); put("merchant", r.merchantName); put("amount", r.totalAmount); put("currency", r.currency)
                    put("status", r.extractionStatus); put("confidence", r.confidence); put("location", r.merchantLocation); put("notes", r.notes)
                    put("raw", r.rawModelOutput); put("message", r.message); put("manual", r.manualEntryRequired); put("uri", r.sourceImageUri)
                    put("expense", r.exportExpenseId); put("complete", r.exportComplete); put("json", r.exportJsonPreview); put("source", r.sourceType.name)
                })
            }) } })
        }) }
        database.dao().save(BatchDraftEntity(payload = payload.toString()))
        // Keep unreferenced files during the Undo window. Deletion is deliberately deferred until
        // a later store-level reconciliation can also inspect retryable export ownership.
    }
    override suspend fun load(): List<BatchItemState> {
        val payload = database.dao().load()?.payload ?: "[]"
        val rows = JSONArray(payload)
        val restored = (0 until rows.length()).map { index ->
            val o = rows.getJSONObject(index); val d = o.getJSONArray("draft")
            val transactions = o.getJSONArray("transactions")
            val status = BatchItemStatus.valueOf(o.getString("status"))
            BatchItemState(itemId = o.getString("id"), sourceRevision = o.getLong("revision"), sourceUri = o.optional("uri"), preparedUri = o.optional("prepared"), mimeType = o.getString("mime"), reduceOversizedImages = o.optBoolean("reduce", true), noImage = o.getBoolean("typed"),
                draft = ManualExpenseDraft(d.getString(0),d.getString(1),d.getString(2),d.getString(3),d.getString(4)),
                status = if (status in listOf(BatchItemStatus.PROCESSING, BatchItemStatus.QUEUED)) BatchItemStatus.CANCELLED else status,
                rawFailureOutput = o.optString("failureRaw"), expanded = o.getBoolean("expanded"), edited = o.optBoolean("edited"), transactions = (0 until transactions.length()).map { t ->
                    val tx = transactions.getJSONObject(t); val r = tx.getJSONObject("review")
                    BatchTransaction(tx.getString("id"),tx.getLong("revision"),ReceiptReviewState(
                        receiptDate=r.getString("date"),merchantName=r.getString("merchant"),totalAmount=r.getString("amount"),currency=r.getString("currency"),
                        extractionStatus=r.getString("status"),confidence=r.getString("confidence"),merchantLocation=r.getString("location"),notes=r.optString("notes"),
                        rawModelOutput=r.getString("raw"),message=r.getString("message"),manualEntryRequired=r.getBoolean("manual"),sourceImageUri=r.getString("uri"),
                        exportExpenseId=r.optional("expense"),exportComplete=r.getBoolean("complete"),exportJsonPreview=r.optional("json"),sourceType=ReceiptSourceType.valueOf(r.getString("source")),
                    ))
                })
        }
        val referenced = (restored.mapNotNull { it.preparedUri } + database.dao().protectedUris()).map { File(java.net.URI(it)).nameWithoutExtension }.toSet()
        directory.listFiles()?.filter { it.name.substringBefore('.') !in referenced }?.forEach { it.delete() }
        return restored.map { item -> if (!item.noImage && item.preparedUri != null && !File(java.net.URI(item.preparedUri)).exists()) item.copy(status = BatchItemStatus.FAILED, error = "Prepared image is missing. Remove and reselect this image.") else item }
    }
}
private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
