package com.hugo.smartexpense.androidextraction

import com.hugo.smartexpense.extraction.GemmaModelSpec
import com.hugo.smartexpense.extraction.LiteRtLmModelPathResolver
import java.io.File

class AndroidGemmaModelPathResolver(
    private val modelDirectory: File,
) : LiteRtLmModelPathResolver {
    override fun resolve(modelSpec: GemmaModelSpec): String =
        File(modelDirectory, modelSpec.defaultLiteRtLmFileName).absolutePath
}
