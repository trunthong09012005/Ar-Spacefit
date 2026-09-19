package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ar.ModelParameters
import com.example.data.ItemEntity
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi", sdk = [36])
class FurnitureModelEditorTest {
    @get:Rule val compose = createComposeRule()
    private val item = ItemEntity(
        name = "Ghế của tôi", category = "FURNITURE", roomType = "OFFICE", widthCm = 52.5f,
        heightCm = 90f, depthCm = 60f, colorHex = "#267A75", material = "Gỗ", modelType3D = "CHAIR"
    )
    private var saved: ItemEntity? = null
    private var placed: ItemEntity? = null

    private fun show(initial: ItemEntity = item) {
        compose.setContent {
            MyApplicationTheme {
                FurnitureModelEditorContent(initial, { saved = it }, { placed = it }, {}, Modifier.fillMaxSize())
            }
        }
    }

    @Test fun requiresConfirmationPreservesDecimalsAndPreventsDuplicateSubmit() {
        show()
        compose.onNodeWithTag("save_scanned_item_button").assertIsNotEnabled()
        compose.onNodeWithTag("furniture_model_preview").assertExists()
        compose.onNodeWithTag("confirm_model_dimensions").performClick()
        compose.onNodeWithTag("save_scanned_item_button").performClick()
        assertEquals(52.5f, saved!!.widthCm, 0f)
        compose.onNodeWithTag("save_scanned_item_button").assertIsNotEnabled()
    }

    @Test fun editedShapeIsPassedToRoomPlacement() {
        show()
        compose.onNodeWithTag("model_armrests").performScrollTo().performClick()
        compose.onNodeWithTag("furniture_model_preview").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/reports/model-editor-shape-controls.png")
        compose.onNodeWithTag("confirm_model_dimensions").performClick()
        compose.onNodeWithTag("test_in_ar_room_button").performClick()
        assertTrue(ModelParameters.fromJson(placed!!.modelParametersJson, "CHAIR").hasArmrests)
    }

    @Test fun changedMeasurementRequiresConfirmationAgainAndInvalidDimensionsBlockPreview() {
        show()
        compose.onNodeWithTag("confirm_model_dimensions").performClick()
        compose.onNodeWithTag("model_width").performScrollTo().performTextReplacement("82,5")
        compose.onNodeWithTag("save_scanned_item_button").assertIsNotEnabled()
        compose.onNodeWithTag("model_width").performTextReplacement("Infinity")
        compose.onNodeWithTag("furniture_model_preview").assertDoesNotExist()
        compose.onNodeWithTag("confirm_model_dimensions").assertIsNotEnabled()
    }

    @Test fun missingAiDimensionsStayBlankAndEditorScreenshot() {
        show(item.copy(widthCm = 0f, heightCm = 0f, depthCm = 0f))
        compose.onNodeWithTag("furniture_model_preview").assertDoesNotExist()
        assertEquals("", compose.onNodeWithTag("model_width").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("test_in_ar_room_button").assertIsNotEnabled()
        compose.onRoot().captureRoboImage(filePath = "build/reports/model-editor-missing-dimensions.png")
    }

    @Test fun modelPreviewCanRotateAndEditorScreenshot() {
        show()
        compose.onRoot().captureRoboImage(filePath = "build/reports/model-editor.png")
        compose.onNodeWithTag("furniture_model_preview").performTouchInput { swipeLeft() }
        compose.onRoot().captureRoboImage(filePath = "build/reports/model-editor-rotated.png")
    }
}
