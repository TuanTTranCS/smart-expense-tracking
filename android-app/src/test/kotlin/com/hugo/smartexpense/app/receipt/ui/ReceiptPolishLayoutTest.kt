package com.hugo.smartexpense.app.receipt.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.hugo.smartexpense.app.ui.SmartExpenseTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationUiState
import com.hugo.smartexpense.app.graphauth.OneDrivePresentation
import com.hugo.smartexpense.app.graphauth.OneDriveRecoveryAction
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.extraction.ReceiptImage
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptPolishLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w280dp-h640dp")
    fun tallReceiptViewerKeepsToolbarOutsideImageAtLargeTextScale() {
        val bitmap = Bitmap.createBitmap(100, 1000, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                SmartExpenseTheme { SelectedReceiptImageReview(ReceiptImage("tall", bytes, "image/png")) }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Review selected receipt image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Review selected receipt image").performClick()
        val image = compose.onNodeWithContentDescription("Selected receipt image").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("Close selected receipt image").assertIsDisplayed()
        val zoom = compose.onNodeWithContentDescription("Zoom in").assertIsDisplayed()
        assertTrue(image.width > 0 && image.height > 0)
        assertTrue(image.top >= close.fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(image.top >= zoom.fetchSemanticsNode().boundsInRoot.bottom)
        zoom.performClick()
        compose.onNodeWithContentDescription("Zoom out").assertIsEnabled().performClick()
        close.performClick()
        compose.onNodeWithContentDescription("Selected receipt image").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w280dp-h640dp")
    fun narrowLargeTextMainWrapsInputActionsAndDispatchesCallbacks() {
        var images = 0
        var added = 0
        var settings = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                SmartExpenseTheme {
                    Box(Modifier.fillMaxSize().testTag("viewport")) {
                        ReceiptWorkflowScreen(ModelProfilesUiState(), ReceiptWorkflowUiState(), GraphAuthenticationUiState(),
                            OneDrivePresentation("OneDrive is ready", "Ready to export", true, OneDriveRecoveryAction.NONE),
                            { settings++ }, {}, {}, {}, {}, { images++ }, {}, { _, _ -> }, {}, onAddItem = { added++ })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Open settings").performScrollTo().assertIsDisplayed().performClick()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        for (label in listOf("Images", "New item")) {
            val action = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            action.performClick()
        }
        compose.runOnIdle { assertEquals(1, settings); assertEquals(1, images); assertEquals(1, added) }
    }

    @Test fun narrowLargeTextReviewKeepsFieldsAndExportWithinViewport() {
        val review = mutableStateOf(ReceiptReviewState(receiptDate = "2026-10-12", merchantName = "Shop", totalAmount = "65.25"))
        var exports = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                SmartExpenseTheme {
                    Column(Modifier.requiredSize(280.dp, 600.dp).testTag("viewport").verticalScroll(rememberScrollState())) {
                        ReceiptReviewEditor(review.value, false, true, { review.value = it }, { exports++ }, false)
                    }
                }
            }
        }
        compose.onNodeWithText("CAD 65.25").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Merchant").performScrollTo().performTextReplacement("Updated shop")
        compose.onNodeWithText("Extraction status: manual").performScrollTo().assertIsDisplayed()
        val action = compose.onNodeWithText("Confirm and export").performScrollTo().assertIsEnabled()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(actionBounds.left >= viewport.left && actionBounds.right <= viewport.right)
        assertTrue(actionBounds.top >= viewport.top && actionBounds.bottom <= viewport.bottom)
        action.performClick()
        compose.runOnIdle { assertEquals("Updated shop", review.value.merchantName); assertEquals(1, exports) }
    }

    @Test fun narrowLargeTextBatchShowsChangingStatusAndPreservesItemActions() {
        val item = mutableStateOf(BatchItemState(itemId = "stable-item", noImage = true, expanded = false, status = BatchItemStatus.FAILED))
        val expanded = mutableListOf<String>()
        val removed = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                SmartExpenseTheme {
                    Column(Modifier.requiredSize(280.dp, 600.dp).testTag("viewport").verticalScroll(rememberScrollState())) {
                        BatchInputGroup(item.value, 1, false, false, false, false,
                            {}, { removed += it }, { expanded += it }, { _, _, _ -> }, { _, _ -> },
                            { _, _, _ -> }, { _, _ -> }, {}, { null }, { null })
                    }
                }
            }
        }
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("Expand Typed expense 1").performClick()
        val remove = compose.onNodeWithContentDescription("Remove item 1").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val bounds = remove.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        remove.performClick()
        compose.runOnIdle { item.value = item.value.copy(status = BatchItemStatus.SUCCEEDED) }
        compose.onNodeWithText("Succeeded").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("stable-item"), expanded); assertEquals(listOf("stable-item"), removed) }
    }
}
