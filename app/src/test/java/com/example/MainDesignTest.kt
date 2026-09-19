package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class MainDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test fun studioHasOneClearToolbarAndNavigation() {
        compose.setContent { MyApplicationTheme { MainAppContainer() } }
        compose.onNodeWithText("SpaceFit Studio").assertExists()
        compose.onNodeWithTag("launch_camera_scanner_button").assertExists()
        compose.onNodeWithTag("nav_tab_inventory").assertExists()
        compose.onRoot().captureRoboImage(filePath = "build/reports/spacefit-studio-polished.png")
    }
}
