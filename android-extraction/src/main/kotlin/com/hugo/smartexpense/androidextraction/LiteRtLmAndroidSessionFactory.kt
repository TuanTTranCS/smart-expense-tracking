package com.hugo.smartexpense.androidextraction

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.hugo.smartexpense.extraction.LiteRtLmBackend
import com.hugo.smartexpense.extraction.LiteRtLmSession
import com.hugo.smartexpense.extraction.LiteRtLmSessionConfig
import com.hugo.smartexpense.extraction.LiteRtLmSessionFactory

class LiteRtLmAndroidSessionFactory(
    private val context: Context,
) : LiteRtLmSessionFactory {
    override fun open(config: LiteRtLmSessionConfig): LiteRtLmSession {
        @OptIn(ExperimentalApi::class)
        if (config.enableSpeculativeDecoding) {
            ExperimentalFlags.enableSpeculativeDecoding = true
        }

        val engine = Engine(
            EngineConfig(
                modelPath = config.modelPath,
                backend = config.backend.toPlatformBackend(context),
                visionBackend = config.visionBackend?.toPlatformBackend(context),
                cacheDir = config.cacheDirPath,
            )
        )
        engine.initialize()
        val conversation = engine.createConversation()
        return EngineBackedLiteRtLmSession(engine, conversation)
    }
}

private class EngineBackedLiteRtLmSession(
    private val engine: Engine,
    private val conversation: Conversation,
) : LiteRtLmSession {
    override fun sendTextMessage(prompt: String): String =
        conversation
            .sendMessage(prompt)
            .toString()

    override fun sendMultimodalImageMessage(prompt: String, imagePath: String): String =
        conversation.sendMessage(
            Contents.of(
                Content.ImageFile(imagePath),
                Content.Text(prompt),
            )
        ).toString()

    override fun close() {
        conversation.close()
        engine.close()
    }
}

private fun LiteRtLmBackend.toPlatformBackend(context: Context): Backend = when (this) {
    LiteRtLmBackend.CPU -> Backend.CPU()
    LiteRtLmBackend.GPU -> Backend.GPU()
    LiteRtLmBackend.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
}
