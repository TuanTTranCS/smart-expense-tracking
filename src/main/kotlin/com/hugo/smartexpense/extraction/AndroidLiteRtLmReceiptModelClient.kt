package com.hugo.smartexpense.extraction

class AndroidLiteRtLmReceiptModelClient(
    private val modelSpec: GemmaModelSpec,
    private val modelPathResolver: LiteRtLmModelPathResolver,
    private val sessionFactory: LiteRtLmSessionFactory,
    private val imageStore: TemporaryReceiptImageStore,
    private val cacheDirPath: String,
    private val textBackend: LiteRtLmBackend = LiteRtLmBackend.GPU,
    private val multimodalBackend: LiteRtLmBackend = LiteRtLmBackend.GPU,
    private val visionBackend: LiteRtLmBackend = LiteRtLmBackend.GPU,
    private val enableSpeculativeDecoding: Boolean = true,
) : ReceiptModelClient {
    override fun supportsDirectImageInput(): Boolean = modelSpec.supportsDirectImageInput

    override fun extractFromReceiptImage(
        receiptImage: ReceiptImage,
        prompt: String,
    ): String {
        require(supportsDirectImageInput()) {
            "${modelSpec.family} ${modelSpec.variant} is not configured for direct image input."
        }

        imageStore.stage(receiptImage).use { stagedImage ->
            openSession(includeVisionBackend = true).use { session ->
                return session.sendMultimodalImageMessage(
                    prompt = prompt,
                    imagePath = stagedImage.absolutePath,
                )
            }
        }
    }

    override fun extractFromReceiptText(receiptText: String, prompt: String): String =
        openSession(includeVisionBackend = false).use { session ->
            session.sendTextMessage(
                prompt = buildReceiptTextPrompt(prompt, receiptText),
            )
        }

    private fun openSession(includeVisionBackend: Boolean): LiteRtLmSession {
        val config = LiteRtLmSessionConfig(
            modelPath = modelPathResolver.resolve(modelSpec),
            backend = if (includeVisionBackend) multimodalBackend else textBackend,
            visionBackend = if (includeVisionBackend) visionBackend else null,
            cacheDirPath = cacheDirPath,
            enableSpeculativeDecoding = enableSpeculativeDecoding,
        )
        return sessionFactory.open(config)
    }

    private fun buildReceiptTextPrompt(prompt: String, receiptText: String): String = """
        $prompt

        Receipt text:
        $receiptText
    """.trimIndent()
}

data class LiteRtLmSessionConfig(
    val modelPath: String,
    val backend: LiteRtLmBackend,
    val visionBackend: LiteRtLmBackend?,
    val cacheDirPath: String,
    val enableSpeculativeDecoding: Boolean,
)

enum class LiteRtLmBackend {
    CPU,
    GPU,
    NPU,
}

interface LiteRtLmModelPathResolver {
    fun resolve(modelSpec: GemmaModelSpec): String
}

interface LiteRtLmSessionFactory {
    fun open(config: LiteRtLmSessionConfig): LiteRtLmSession
}

interface LiteRtLmSession : AutoCloseable {
    fun sendTextMessage(prompt: String): String

    fun sendMultimodalImageMessage(prompt: String, imagePath: String): String
}

interface TemporaryReceiptImageStore {
    fun stage(receiptImage: ReceiptImage): StagedReceiptImage
}

interface StagedReceiptImage : AutoCloseable {
    val absolutePath: String
}
