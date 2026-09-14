package com.hugo.smartexpense.app.modelprofile.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalModelReadinessTest {
    @Test fun missingModelIsNotReady() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "missing-model.litertlm").also { it.delete() }
        val result = AndroidLocalModelReadinessService(context, file).run { kotlinx.coroutines.runBlocking { check() } }
        assertIs<LocalModelReadinessResult.NotReady>(result)
        assertTrue(result.reason.contains("not installed"))
    }

    @Test fun nonEmptyReadableModelIsReady() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "ready-model.litertlm").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val result = AndroidLocalModelReadinessService(context, file).run { kotlinx.coroutines.runBlocking { check() } }
        assertIs<LocalModelReadinessResult.Ready>(result)
        assertTrue(result.sizeBytes == 3L)
        file.delete()
    }
}
