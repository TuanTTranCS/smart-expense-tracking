package com.hugo.smartexpense.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLiteRtLmReceiptModelClientTest {
    private val sampleImage = ReceiptImage(
        sourceName = "receipt.jpg",
        bytes = byteArrayOf(1, 2, 3),
        mimeType = "image/jpeg",
    )

    @Test
    fun directImagePathUsesPinnedGemma4E2bModelAndVisionBackend() {
        val modelPathResolver = FakeModelPathResolver("D:/models/gemma-4-E2B-it.litertlm")
        val sessionFactory = FakeSessionFactory(
            textResponse = """{"receiptDate":"2026-06-26","merchantName":"Text Shop","totalAmount":42.35,"currency":"CAD","extractionStatus":"confirmed"}""",
            imageResponse = """{"receiptDate":"2026-06-26","merchantName":"Image Shop","totalAmount":42.35,"currency":"CAD","extractionStatus":"confirmed"}""",
        )
        val imageStore = FakeTemporaryReceiptImageStore("D:/tmp/receipt.jpg")
        val client = AndroidLiteRtLmReceiptModelClient(
            modelSpec = GemmaModels.gemma4E2BItLiteRtLm,
            modelPathResolver = modelPathResolver,
            sessionFactory = sessionFactory,
            imageStore = imageStore,
            cacheDirPath = "D:/tmp/cache",
        )

        val response = client.extractFromReceiptImage(sampleImage)

        assertTrue(client.supportsDirectImageInput())
        assertTrue(response.contains("Image Shop"))
        assertEquals(1, modelPathResolver.calls)
        assertEquals(1, imageStore.stageCalls)
        assertTrue(imageStore.closed)
        assertEquals("D:/models/gemma-4-E2B-it.litertlm", sessionFactory.lastConfig?.modelPath)
        assertEquals(LiteRtLmBackend.GPU, sessionFactory.lastConfig?.backend)
        assertEquals(LiteRtLmBackend.GPU, sessionFactory.lastConfig?.visionBackend)
        assertEquals("D:/tmp/cache", sessionFactory.lastConfig?.cacheDirPath)
        assertTrue(sessionFactory.lastConfig?.enableSpeculativeDecoding == true)
        assertEquals("D:/tmp/receipt.jpg", sessionFactory.lastImagePath)
    }

    @Test
    fun textFallbackDoesNotRequestVisionBackend() {
        val sessionFactory = FakeSessionFactory(
            textResponse = """{"receiptDate":"2026-06-26","merchantName":"OCR Shop","totalAmount":42.35,"currency":"CAD","extractionStatus":"confirmed"}""",
            imageResponse = """{"receiptDate":"2026-06-26","merchantName":"Image Shop","totalAmount":42.35,"currency":"CAD","extractionStatus":"confirmed"}""",
        )
        val client = AndroidLiteRtLmReceiptModelClient(
            modelSpec = GemmaModels.gemma4E2BItLiteRtLm,
            modelPathResolver = FakeModelPathResolver("D:/models/gemma-4-E2B-it.litertlm"),
            sessionFactory = sessionFactory,
            imageStore = FakeTemporaryReceiptImageStore("D:/tmp/unused.jpg"),
            cacheDirPath = "D:/tmp/cache",
        )

        val response = client.extractFromReceiptText("TOTAL 42.35 CAD")

        assertTrue(response.contains("OCR Shop"))
        assertEquals(LiteRtLmBackend.GPU, sessionFactory.lastConfig?.backend)
        assertEquals(null, sessionFactory.lastConfig?.visionBackend)
        assertTrue(sessionFactory.lastTextPrompt.orEmpty().contains("Receipt text:"))
        assertTrue(sessionFactory.lastTextPrompt.orEmpty().contains("TOTAL 42.35 CAD"))
    }

    @Test
    fun directImageSupportTracksModelSpec() {
        val nonVisionSpec = GemmaModels.gemma4E2BItLiteRtLm.copy(supportsDirectImageInput = false)
        val client = AndroidLiteRtLmReceiptModelClient(
            modelSpec = nonVisionSpec,
            modelPathResolver = FakeModelPathResolver("D:/models/model.litertlm"),
            sessionFactory = FakeSessionFactory("{}", "{}"),
            imageStore = FakeTemporaryReceiptImageStore("D:/tmp/receipt.jpg"),
            cacheDirPath = "D:/tmp/cache",
        )

        assertFalse(client.supportsDirectImageInput())
    }
}

private class FakeModelPathResolver(
    private val resolvedPath: String,
) : LiteRtLmModelPathResolver {
    var calls: Int = 0
        private set

    override fun resolve(modelSpec: GemmaModelSpec): String {
        calls += 1
        return resolvedPath
    }
}

private class FakeSessionFactory(
    private val textResponse: String,
    private val imageResponse: String,
) : LiteRtLmSessionFactory {
    var lastConfig: LiteRtLmSessionConfig? = null
        private set

    var lastTextPrompt: String? = null
        private set

    var lastImagePath: String? = null
        private set

    override fun open(config: LiteRtLmSessionConfig): LiteRtLmSession {
        lastConfig = config
        return object : LiteRtLmSession {
            override fun sendTextMessage(prompt: String): String {
                lastTextPrompt = prompt
                return textResponse
            }

            override fun sendMultimodalImageMessage(prompt: String, imagePath: String): String {
                lastTextPrompt = prompt
                lastImagePath = imagePath
                return imageResponse
            }

            override fun close() = Unit
        }
    }
}

private class FakeTemporaryReceiptImageStore(
    private val path: String,
) : TemporaryReceiptImageStore {
    var stageCalls: Int = 0
        private set

    var closed: Boolean = false
        private set

    override fun stage(receiptImage: ReceiptImage): StagedReceiptImage {
        stageCalls += 1
        return object : StagedReceiptImage {
            override val absolutePath: String = path

            override fun close() {
                closed = true
            }
        }
    }
}
