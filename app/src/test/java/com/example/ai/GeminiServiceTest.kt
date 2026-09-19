package com.example.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiServiceTest {

    @Test
    fun `analysis is rejected until all five views are captured`() = runTest {
        try {
            GeminiService().analyzeItemFromImages(emptyList())
            fail("Expected multi-angle validation to reject an incomplete scan")
        } catch (error: GeminiServiceException) {
            assertTrue(error.message.orEmpty().contains("5 hướng"))
        }
    }
}
