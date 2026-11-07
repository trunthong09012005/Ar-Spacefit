package com.example.reconstruction

import org.junit.Assert.*
import org.junit.Test

class ScanFailureTest {
    @Test fun confirmedOomIsActionableAndDoesNotExposeBackendDetails() {
        val message=scanFailureMessage("NATIVE_OUT_OF_MEMORY: private fixture detail")
        assertTrue(message.contains("Docker xác nhận"))
        assertTrue(message.contains("thiếu RAM"))
        assertTrue(message.contains("không tự chạy lại hoặc giảm chất lượng"))
        assertFalse(message.contains("private"))
    }
    @Test fun killAndLegacy137DoNotClaimConfirmedOom() {
        for (raw in listOf("NATIVE_KILLED: fixture", "refine failed (exit 137). Download the job log for details.")) {
            val message=scanFailureMessage(raw)
            assertTrue(message.contains("chưa xác nhận"))
            assertFalse(message.contains("Docker xác nhận"))
        }
    }
    @Test fun emptyOtherErrorsAndLookalikeMarkersArePreserved() {
        for (raw in listOf("", "refine failed (exit 1).", "prefix NATIVE_OUT_OF_MEMORY:", "NATIVE_OUT_OF_MEMORY", "137", "refine failed (exit 1370)."))
            assertEquals(raw,scanFailureMessage(raw))
    }
}
