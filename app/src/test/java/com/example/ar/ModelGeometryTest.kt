package com.example.ar

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ModelGeometryTest {
    @Test fun everyTemplateReflectsSelectedColor() {
        for (type in modelTypeLabels.keys) {
            assertNotEquals("$type must apply selected color",
                ModelGeometryFactory.createModel(type, Color.Red), ModelGeometryFactory.createModel(type, Color.Blue))
        }
    }

    @Test fun everyTemplateHasUnitOuterBoundsIncludingCustomDetails() {
        for (type in modelTypeLabels.keys) {
            for (parameters in listOf(ModelParameters.defaults(type), ModelParameters(0.65f, true, 0.2f, 4))) {
                val vertices = ModelGeometryFactory.createModel(type, Color.Blue, parameters).faces.flatMap { it.vertices }
                assertEquals("$type width", 1f, vertices.maxOf { it.x } - vertices.minOf { it.x }, 0.0001f)
                assertEquals("$type height", 1f, vertices.maxOf { it.y }, 0.0001f)
                assertEquals("$type floor", 0f, vertices.minOf { it.y }, 0.0001f)
                assertEquals("$type depth", 1f, vertices.maxOf { it.z } - vertices.minOf { it.z }, 0.0001f)
                assertEquals("$type centered X", -0.5f, vertices.minOf { it.x }, 0.0001f)
                assertEquals("$type centered Z", -0.5f, vertices.minOf { it.z }, 0.0001f)
            }
        }
    }

    @Test fun allModelsUseMeasuredDimensionsAndRotation() {
        for (type in modelTypeLabels.keys) {
            val item = PlacedFurniture(1, "Measured", type, 2f, 0.1f, 3f, 0f, 123.5f, 82.4f, 65.2f, Color.Blue)
            val vertices = item.getTransformedMesh().faces.flatMap { it.vertices }
            assertEquals(1.235f, vertices.maxOf { it.x } - vertices.minOf { it.x }, 0.0001f)
            assertEquals(0.824f, vertices.maxOf { it.y } - vertices.minOf { it.y }, 0.0001f)
            assertEquals(0.652f, vertices.maxOf { it.z } - vertices.minOf { it.z }, 0.0001f)
            val rotated = item.copy(rotationY = 90f).getTransformedMesh().faces.flatMap { it.vertices }
            assertEquals(0.652f, rotated.maxOf { it.x } - rotated.minOf { it.x }, 0.0001f)
            assertEquals(1.235f, rotated.maxOf { it.z } - rotated.minOf { it.z }, 0.0001f)
        }
    }

    @Test fun editingParametersActuallyChangesMesh() {
        for (type in listOf("SOFA", "CHAIR", "TABLE", "WARDROBE")) {
            val first = ModelGeometryFactory.createModel(type, Color.Blue, ModelParameters(0.3f, false, 0.03f, 1))
            val second = ModelGeometryFactory.createModel(type, Color.Blue, ModelParameters(0.65f, true, 0.2f, 4))
            assertNotEquals("$type mesh must reflect parameters", first, second)
        }
    }

    @Test fun corruptedParametersCannotCreateNonFiniteVertices() {
        val parameters = ModelParameters(Float.NaN, true, Float.POSITIVE_INFINITY, -10)
        for (type in modelTypeLabels.keys) {
            val mesh = ModelGeometryFactory.createModel(type, Color.Blue, parameters)
            assertTrue(mesh.faces.flatMap { it.vertices }.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        }
    }

    @Test fun dimensionInputAcceptsVietnameseDecimalsButRejectsInvalidOrUnboundedValues() {
        assertEquals(82.5f, parseDimensionCm(" 82,5 ")!!, 0f)
        assertEquals(82.5f, parseDimensionCm("82.5")!!, 0f)
        listOf("", "NaN", "Infinity", "-1", "0", "0.1", "1001", "1e99", "a").forEach {
            assertNull("must reject $it", parseDimensionCm(it))
        }
    }
}
