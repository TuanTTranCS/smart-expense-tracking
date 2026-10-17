package com.hugo.smartexpense.extraction

import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import kotlin.test.*

class ExtractionDateContextTest {
    private val reference = LocalDate.of(2026, 10, 16)
    private val image = ReceiptImage("receipt.jpg", byteArrayOf(1, 2, 3), "image/jpeg")
    private val draft = TypedExpenseDraft(notes = " Shop, 10/15/26, 12 CAD\n", currency = "")
    private val valid = """{"receipts":[{"receiptDate":"2026-10-15","merchantName":"Shop","totalAmount":12,"currency":"CAD","extractionStatus":"low_confidence","confidence":0.8,"merchantLocation":null,"issues":[],"suggestedCorrections":["10/15/26 -> 2026-10-15"]}]}"""

    @Test fun formatsEnglishReferenceAndRetainsDateSafetyGuidanceInAllPrompts() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val context = ExtractionDateContext(LocalDate.of(2026, 1, 2))
            listOf(ReceiptExtractionPrompt.multimodal(context), ReceiptExtractionPrompt.text(context),
                TypedExpenseReviewPrompt.text(context)).forEach { prompt ->
                assertTrue(prompt.startsWith("Current reference date: Jan 2, 2026."))
                assertContains(prompt, "10/15/26")
                assertContains(prompt, "Preserve clearly stated dates, including older dates")
                assertContains(prompt, "missing date")
                assertContains(prompt, "impossible date")
                assertContains(prompt, "low_confidence")
            }
            assertContains(TypedExpenseReviewPrompt.text(context), "inferences alone do not")
        } finally { Locale.setDefault(original) }
    }

    @Test fun captureResolvesCurrentDeviceTimezoneInsteadOfCachingIt() {
        val original = TimeZone.getDefault()
        try {
            listOf("Pacific/Kiritimati", "Pacific/Pago_Pago").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val before = LocalDate.now()
                val captured = ExtractionDateContext.capture().referenceDate
                assertTrue(captured == before || captured == LocalDate.now())
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun imageAndOcrFallbackShareOneSnapshotAndExplicitRetryRefreshesIt() {
        var reads = 0
        val prompts = mutableListOf<String>()
        val client = object : ReceiptModelClient {
            override fun supportsDirectImageInput() = true
            override fun extractFromReceiptImage(receiptImage: ReceiptImage, prompt: String): String {
                prompts += prompt
                return "invalid"
            }
            override fun extractFromReceiptText(receiptText: String, prompt: String): String {
                assertEquals("original OCR 10/15/26", receiptText)
                prompts += prompt
                return valid
            }
        }
        val pipeline = ReceiptExtractionPipeline(client, ocrClient = object : OcrClient {
            override fun extractText(receiptImage: ReceiptImage) = "original OCR 10/15/26"
        }, dateProvider = { reference.plusDays(reads++.toLong()) })
        repeat(2) { assertIs<ReceiptExtractionPipelineResult.Success>(pipeline.extract(image)) }
        assertEquals(2, reads)
        assertTrue(prompts.take(2).all { it.startsWith("Current reference date: Oct 16, 2026.") })
        assertTrue(prompts.drop(2).all { it.startsWith("Current reference date: Oct 17, 2026.") })
    }

    @Test fun typedSnapshotKeepsOriginalSourceAndInferencesReviewableWhileBlockingInvalidDates() {
        var reads = 0
        var output = valid
        val client = object : ReceiptModelClient {
            override fun supportsDirectImageInput() = false
            override fun extractFromReceiptImage(receiptImage: ReceiptImage, prompt: String): String = error("No image")
            override fun extractFromReceiptText(receiptText: String, prompt: String): String {
                assertEquals(draft.modelInput(), receiptText)
                assertContains(prompt, "Current reference date: Oct ${15 + reads}, 2026.")
                return output
            }
        }
        val pipeline = ReceiptExtractionPipeline(client, dateProvider = { reference.plusDays(reads++.toLong()) })
        val result = assertIs<ReceiptExtractionPipelineResult.Success>(pipeline.reviewTypedExpense(draft))
        assertEquals(draft.notes, result.result.notes)
        assertEquals(ExtractionStatus.LOW_CONFIDENCE, result.result.extractionStatus)
        assertEquals(listOf("10/15/26 -> 2026-10-15"), result.result.suggestedCorrections)
        listOf("2026-02-30", "10/15/26", "", "2026-13-01").forEach { invalid ->
            output = valid.replace("\"receiptDate\":\"2026-10-15\"", "\"receiptDate\":\"$invalid\"")
            assertIs<ReceiptExtractionPipelineResult.Failed>(pipeline.reviewTypedExpense(draft))
        }
        output = valid.replace("\"issues\":[]", "\"issues\":[\"Conflicting date evidence\"]")
        assertIs<ReceiptExtractionPipelineResult.Failed>(pipeline.reviewTypedExpense(draft))
        assertEquals(6, reads)
    }

    @Test fun allRemoteOutputModesAndSchemaRecoveryPreserveTypedAndImageOcrDateContext() {
        RemoteStructuredOutputFormat.entries.forEach { format ->
            listOf("typed", "image", "ocr").forEach { route ->
                var reads = 0
                val requests = mutableListOf<OpenAiCompatibleApiRequest>()
                val client = OpenAiCompatibleReceiptModelClient(
                    OpenAiCompatibleProviderOption("remote", "Remote", "https://example.com/v1", "model",
                        if (route == "ocr") RemoteInputMode.OCR_TEXT else RemoteInputMode.DIRECT_IMAGE,
                        "key", structuredOutputFormat = format),
                    ApiKeyStore { "secret" }, object : OpenAiCompatibleApiTransport {
                        override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                            requests += request
                            if (format == RemoteStructuredOutputFormat.JSON_SCHEMA && requests.size == 1)
                                return OpenAiCompatibleApiResponse(400, """{"error":{"code":"invalid_json"}}""")
                            return OpenAiCompatibleApiResponse(200, JSONObject().put("choices", listOf(
                                mapOf("message" to mapOf("role" to "assistant", "content" to valid)))).toString())
                        }
                    })
                val pipeline = ReceiptExtractionPipeline(client, ocrClient = object : OcrClient {
                    override fun extractText(receiptImage: ReceiptImage) = "exact OCR source"
                }, dateProvider = { reads++; reference })
                assertIs<ReceiptExtractionPipelineResult.Success>(
                    if (route == "typed") pipeline.reviewTypedExpense(draft) else pipeline.extract(image))
                assertEquals(1, reads)
                assertEquals(if (format == RemoteStructuredOutputFormat.JSON_SCHEMA) 2 else 1, requests.size)
                requests.forEach { request ->
                    assertContains(request.body, "Current reference date: Oct 16, 2026.")
                    if (route == "typed") assertContains(request.body, "10/15/26")
                    if (route == "ocr") assertContains(request.body, "exact OCR source")
                }
                if (requests.size == 2) {
                    assertEquals(JSONObject(requests.first().body).getJSONArray("messages").toString(),
                        JSONObject(requests.last().body).getJSONArray("messages").toString())
                    assertFalse(JSONObject(requests.last().body).has("response_format"))
                }
            }
        }
    }

    @Test fun builtInClientPassesCapturedPromptsToImageOcrAndTypedSessions() {
        val prompts = mutableListOf<String>()
        var reads = 0
        val client = AndroidLiteRtLmReceiptModelClient(
            GemmaModels.gemma4E2BItLiteRtLm,
            object : LiteRtLmModelPathResolver { override fun resolve(modelSpec: GemmaModelSpec) = "model.litertlm" },
            object : LiteRtLmSessionFactory {
                override fun open(config: LiteRtLmSessionConfig) = object : LiteRtLmSession {
                    override fun sendTextMessage(prompt: String): String { prompts += prompt; return valid }
                    override fun sendMultimodalImageMessage(prompt: String, imagePath: String): String {
                        prompts += prompt
                        return "invalid output triggers OCR"
                    }
                    override fun close() = Unit
                }
            },
            object : TemporaryReceiptImageStore {
                override fun stage(receiptImage: ReceiptImage) = object : StagedReceiptImage {
                    override val absolutePath = "receipt.jpg"
                    override fun close() = Unit
                }
            }, "cache",
        )
        val pipeline = ReceiptExtractionPipeline(client, ocrClient = object : OcrClient {
            override fun extractText(receiptImage: ReceiptImage) = "original OCR"
        }, dateProvider = { reads++; reference })
        assertIs<ReceiptExtractionPipelineResult.Success>(pipeline.extract(image))
        assertIs<ReceiptExtractionPipelineResult.Success>(pipeline.reviewTypedExpense(draft))
        assertEquals(2, reads)
        assertEquals(3, prompts.size)
        prompts.forEach { assertContains(it, "Current reference date: Oct 16, 2026.") }
        assertContains(prompts[1], "original OCR")
        assertContains(prompts[2], draft.modelInput())
    }

    @Test fun samePhonePreparedImageAndTypedRequestsReceiveCapturedDateWithoutProtocolChanges() {
        val instance = "11111111-1111-4111-8111-111111111111"
        val id = "22222222-2222-4222-8222-222222222222"
        val requests = mutableListOf<OpenAiCompatibleApiRequest>()
        var reads = 0
        val client = OpenAiCompatibleReceiptModelClient(
            OpenAiCompatibleProviderOption("phone", "Phone", "http://127.0.0.1:8080/v1", "vision",
                RemoteInputMode.DIRECT_IMAGE, "key", RemoteStructuredOutputFormat.PROMPT_ONLY_JSON,
                category = ApiProfileCategory.SAME_PHONE_SERVER),
            ApiKeyStore { null }, object : OpenAiCompatibleApiTransport {
                override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                    requests += request
                    return if (request.url.endsWith("/requests")) OpenAiCompatibleApiResponse(201,
                        """{"instanceId":"$instance","requestId":"$id","runtimeState":"available"}""")
                    else OpenAiCompatibleApiResponse(200, JSONObject().put("choices", listOf(
                        mapOf("message" to mapOf("role" to "assistant", "content" to valid)))).toString(),
                        mapOf("X-Companion-Instance" to instance, "X-Request-Id" to id))
                }
            })
        val pipeline = ReceiptExtractionPipeline(client, dateProvider = { reads++; reference })
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + ByteArray(8) +
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1)
        assertIs<ReceiptExtractionPipelineResult.Success>(pipeline.extract(ReceiptImage("receipt.png", png, "image/png")))
        assertIs<ReceiptExtractionPipelineResult.Success>(pipeline.reviewTypedExpense(draft))
        assertEquals(2, reads)
        assertEquals(4, requests.size)
        assertTrue(requests.filter { it.url.endsWith("/requests") }.all { it.body == "{}" })
        val chats = requests.filter { it.url.endsWith("/chat/completions") }
        assertEquals(2, chats.size)
        chats.forEach {
            assertContains(it.body, "Current reference date: Oct 16, 2026.")
            assertFalse(JSONObject(it.body).has("response_format"))
        }
    }
}
