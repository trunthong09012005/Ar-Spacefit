package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi", sdk = [36])
class MainDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test fun studioHasOneClearToolbarAndNavigation() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val original=Coil.imageLoader(app)
        val loaded=AtomicBoolean(false)
        val loadedData=AtomicReference<Any?>(null)
        val failure=AtomicReference<Throwable?>(null)
        val loader=ImageLoader.Builder(app).eventListener(object:EventListener{
            override fun onSuccess(request:ImageRequest,result:SuccessResult){
                loadedData.set(request.data);loaded.set(true)
            }
            override fun onError(request:ImageRequest,result:ErrorResult){
                failure.set(result.throwable)
            }
        }).build()
        Coil.setImageLoader(loader)
        try{
            compose.setContent { MyApplicationTheme { MainAppContainer() } }
            compose.onNodeWithText("SpaceFit Studio").assertExists()
            compose.onNodeWithTag("launch_camera_scanner_button").assertExists()
            compose.onNodeWithTag("nav_tab_inventory").assertExists()
            // Decode the real bundled room image, not a mocked success. Teardown
            // must not interrupt libjpeg halfway through a background request.
            compose.waitUntil(15000){compose.waitForIdle();loaded.get() || failure.get()!=null}
            assertNull("Bundled room image failed to decode",failure.get())
            assertEquals(R.drawable.bg_sample_room,loadedData.get())
            compose.onNodeWithContentDescription("Sample Room").assertExists()
            compose.onRoot().captureRoboImage(filePath = "build/reports/spacefit-studio-polished.png")
        }finally{loader.shutdown();Coil.setImageLoader(original)}
    }
}
