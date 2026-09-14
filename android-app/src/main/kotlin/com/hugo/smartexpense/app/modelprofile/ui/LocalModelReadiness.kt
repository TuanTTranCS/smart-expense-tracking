package com.hugo.smartexpense.app.modelprofile.ui

import android.content.Context
import com.hugo.smartexpense.extraction.GemmaModels
import java.io.File

sealed interface LocalModelReadinessResult {
    data class Ready(val modelPath: String, val sizeBytes: Long) : LocalModelReadinessResult
    data class NotReady(val reason: String) : LocalModelReadinessResult
}

fun interface LocalModelReadinessService {
    suspend fun check(): LocalModelReadinessResult
}

class AndroidLocalModelReadinessService(
    context: Context,
    private val modelFile: File = File(
        File(context.filesDir, "models"),
        GemmaModels.gemma4E2BItLiteRtLm.defaultLiteRtLmFileName,
    ),
) : LocalModelReadinessService {
    override suspend fun check(): LocalModelReadinessResult = when {
        !modelFile.isFile -> LocalModelReadinessResult.NotReady(
            "Local model is not installed. Expected ${modelFile.name} in the app models directory.",
        )
        !modelFile.canRead() -> LocalModelReadinessResult.NotReady("The installed local model cannot be read.")
        modelFile.length() <= 0L -> LocalModelReadinessResult.NotReady("The installed local model file is empty.")
        else -> LocalModelReadinessResult.Ready(modelFile.absolutePath, modelFile.length())
    }
}
