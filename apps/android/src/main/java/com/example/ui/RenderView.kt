package com.example.ui

import android.os.Build
import androidx.compose.runtime.Composable
import com.google.android.filament.Engine
import io.github.sceneview.SceneView
import io.github.sceneview.rememberView

/**
 * API 33 AVD's translated OpenGL post-processing returns blank frames (including
 * for SceneView's own CubeNode). Geometry renders correctly without that pass.
 * Keep tone mapping/gamma correction on physical devices; AVD colours are only
 * diagnostic and must not be used to assess scanned texture colour accuracy.
 */
internal fun usesEmulatorRenderFallback():Boolean =
    Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.startsWith("generic")

@Composable
internal fun rememberSpaceFitView(engine:Engine)=rememberView(engine){
    SceneView.createView(engine).apply {
        if(usesEmulatorRenderFallback())isPostProcessingEnabled=false
    }
}
