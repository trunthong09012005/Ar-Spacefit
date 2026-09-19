package com.example.ai

import com.example.ar.ModelParameters
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecognitionParserTest {
    private val valid = """{"isFurniture":true,"sameObject":true,"name":"Ghế thật","modelType3D":"CHAIR",
        "widthCm":52.5,"heightCm":85.2,"depthCm":60,"colorHex":"#123ABC","material":"Gỗ",
        "modelParameters":{"seatHeightRatio":0.55,"hasArmrests":true}}"""

    @Test fun parsesMultiViewResultAndShapeWithoutRoundingDimensions() {
        val result = RecognitionParser.parse("```json\n$valid\n```")
        assertEquals(52.5f, result.widthCm, 0f)
        assertEquals("Gỗ", result.material)
        assertTrue(result.modelParameters.hasArmrests)
        assertEquals(0.55f, result.modelParameters.seatHeightRatio, 0.0001f)
    }

    @Test fun missingMeasurementsAndMaterialsAreNotInvented() {
        val result = RecognitionParser.parse("""{"isFurniture":true,"sameObject":true,"name":"Bàn","modelType3D":"TABLE"}""")
        assertEquals(0f, result.widthCm, 0f)
        assertEquals(0f, result.heightCm, 0f)
        assertEquals(0f, result.depthCm, 0f)
        assertEquals("", result.material)
        assertEquals("", result.advice)
    }

    @Test fun rejectsDifferentObjectsNonFurnitureAndUnconfirmedAnalysis() {
        for (raw in listOf(valid.replace("\"sameObject\":true", "\"sameObject\":false"),
            valid.replace("\"isFurniture\":true", "\"isFurniture\":false"),
            valid.replace("\"sameObject\":true,", ""))) {
            assertThrows(GeminiServiceException::class.java) { RecognitionParser.parse(raw) }
        }
    }

    @Test fun rejectsBrokenResponseInsteadOfMakingDemoItem() {
        for (raw in listOf("{}", "not json", valid.replace("CHAIR", "ALIEN"), valid.replace("Ghế thật", ""))) {
            assertThrows(GeminiServiceException::class.java) { RecognitionParser.parse(raw) }
        }
    }

    @Test fun parametersRoundTripAndLegacyDefaults() {
        val original = ModelParameters(0.6f, true, 0.15f, 3)
        assertEquals(original, ModelParameters.fromJson(original.toJson(), "WARDROBE"))
        assertEquals(ModelParameters.defaults("SOFA"), ModelParameters.fromJson("", "SOFA"))
        assertEquals(ModelParameters.defaults("TABLE"), ModelParameters.fromJson("bad", "TABLE"))
    }
}
