package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ar.ModelParameters
import com.example.data.ItemEntity
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi", sdk = [36])
class InventoryDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inventoryUsesClearHierarchyAndPrimaryAction() {
        val testOnlyItem = ItemEntity(
            id = 99, name = "Ghế làm việc", category = "FURNITURE", roomType = "OFFICE",
            widthCm = 62f, heightCm = 112f, depthCm = 64f,
            colorHex = "#34D399", material = "Lưới và kim loại", modelType3D = "CHAIR",
            modelParametersJson = ModelParameters(hasArmrests = true).toJson()
        )
        compose.setContent {
            MyApplicationTheme {
                InventoryScreen(
                    items = listOf(testOnlyItem), onTestIn3D = {}, onDeleteItem = {},
                    onAddNewItem = {}, onUpdateItem = {}, modifier = Modifier.fillMaxSize()
                )
            }
        }
        compose.onNodeWithText("Kho nội thất").assertExists()
        compose.onNodeWithTag("edit_model_99").assertExists()
        compose.onNodeWithText("Đặt vào phòng").assertExists()
        compose.onRoot().captureRoboImage(filePath = "build/reports/inventory-polished.png")
    }
}
