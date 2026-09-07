package com.hugo.smartexpense.androidextraction

import android.content.Context
import com.hugo.smartexpense.extraction.AndroidLiteRtLmReceiptModelClient
import com.hugo.smartexpense.extraction.GemmaModelSpec
import com.hugo.smartexpense.extraction.GemmaModels
import java.io.File

class AndroidLiteRtLmReceiptModelClientFactory(
    private val context: Context,
    private val modelDirectory: File,
) {
    fun create(
        modelSpec: GemmaModelSpec = GemmaModels.gemma4E2BItLiteRtLm,
    ): AndroidLiteRtLmReceiptModelClient =
        AndroidLiteRtLmReceiptModelClient(
            modelSpec = modelSpec,
            modelPathResolver = AndroidGemmaModelPathResolver(modelDirectory),
            sessionFactory = LiteRtLmAndroidSessionFactory(context),
            imageStore = AndroidTemporaryReceiptImageStore(context.cacheDir),
            cacheDirPath = context.cacheDir.absolutePath,
        )
}
