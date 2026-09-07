package com.hugo.smartexpense.extraction

data class GemmaModelSpec(
    val family: String,
    val variant: String,
    val huggingFaceRepo: String,
    val defaultLiteRtLmFileName: String,
    val webLiteRtLmFileName: String,
    val supportsDirectImageInput: Boolean,
)

object GemmaModels {
    val gemma4E2BItLiteRtLm = GemmaModelSpec(
        family = "Gemma 4",
        variant = "E2B-it",
        huggingFaceRepo = "litert-community/gemma-4-E2B-it-litert-lm",
        defaultLiteRtLmFileName = "gemma-4-E2B-it.litertlm",
        webLiteRtLmFileName = "gemma-4-E2B-it-web.litertlm",
        supportsDirectImageInput = true,
    )
}
