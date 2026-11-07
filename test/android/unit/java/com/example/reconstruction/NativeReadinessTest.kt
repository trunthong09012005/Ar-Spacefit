package com.example.reconstruction

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeReadinessTest {
    private val names = listOf("colmap", "blender", "InterfaceCOLMAP", "DensifyPointCloud", "ReconstructMesh", "RefineMesh", "TextureMesh")

    private fun healthy(): JSONObject = JSONObject().put("ready", true)
        .put("tools", JSONObject().apply { names.forEach { put(it, true) } })
        .put("runtime", JSONObject().put("checking", false).put("checked", true)
            .put("tools", JSONObject().apply { names.forEach { put(it, JSONObject().put("status", "ready").put("reason", "ok")) } }))

    private fun failure(payload: JSONObject): String {
        try { requireNativeReady(payload); fail("Invalid readiness must prevent upload") }
        catch (error: IllegalArgumentException) { return error.message.orEmpty() }
        catch (error: IllegalStateException) { return error.message.orEmpty() }
        error("Expected a diagnostic error")
    }

    @Test fun allSevenToolsNeedSuccessfulStartup() { requireNativeReady(healthy()) }

    @Test fun legacyPathPresenceCannotAuthorizeAnUpload() {
        val payload = healthy().apply { remove("runtime") }
        assertTrue(failure(payload).contains("chưa hỗ trợ kiểm tra khởi động"))
    }

    @Test fun missingBinaryIsDifferentFromALoaderFailure() {
        val payload = healthy()
        payload.getJSONObject("tools").put("TextureMesh", false)
        assertTrue(failure(payload).contains("Laptop còn thiếu: TextureMesh"))
    }

    @Test fun backgroundProbeDoesNotPretendToBeReady() {
        val payload = healthy()
        payload.getJSONObject("runtime").put("checking", true).put("checked", false)
        assertTrue(failure(payload).contains("đang kiểm tra"))
    }

    @Test fun blockedDllReportsToolAndExitCodeWithoutAdvisingSecurityBypass() {
        val payload = healthy()
        payload.getJSONObject("runtime").getJSONObject("tools").put("DensifyPointCloud",
            JSONObject().put("status", "error").put("reason", "startup_failed").put("hex_exit", "0xc0e90002"))
        val message = failure(payload)
        assertTrue(message.contains("DensifyPointCloud (không khởi động được: 0xc0e90002)"))
        assertTrue(message.contains("không tắt bảo vệ Windows"))
    }

    @Test fun timeoutAndWrongExecutableAreNotMissingInstallation() {
        val payload = healthy()
        payload.getJSONObject("runtime").getJSONObject("tools").put("colmap", JSONObject().put("status", "error").put("reason", "timeout"))
        assertTrue(failure(payload).contains("colmap (quá thời gian)"))
        payload.getJSONObject("runtime").getJSONObject("tools").getJSONObject("colmap").put("reason", "invalid_help")
        assertTrue(failure(payload).contains("colmap (không đúng công cụ)"))
    }

    @Test fun dockerStartupFailureGivesContainerGuidanceWithoutPretendingReady() {
        val payload = healthy()
        payload.getJSONObject("runtime").getJSONObject("tools").put("TextureMesh",
            JSONObject().put("status", "error").put("reason", "docker_start_failed").put("hex_exit", "0x7d"))
        val message = failure(payload)
        assertTrue(message.contains("Docker không tạo được container: 0x7d"))
        assertTrue(message.contains("Docker Desktop"))
        assertTrue(message.contains("Lưu và kiểm tra"))
        assertFalse(message.contains("DLL"))
        payload.getJSONObject("runtime").getJSONObject("tools").put("colmap",
            JSONObject().put("status", "error").put("reason", "timeout"))
        assertTrue(failure(payload).contains("colmap (quá thời gian)"))
    }

    @Test fun malformedOrIncompleteReportFailsClosed() {
        assertTrue(failure(JSONObject()).contains("không hợp lệ"))
        val payload = healthy()
        payload.getJSONObject("tools").put("colmap", "true")
        assertTrue(failure(payload).contains("không đầy đủ"))
        payload.getJSONObject("tools").put("colmap", true)
        payload.getJSONObject("runtime").getJSONObject("tools").remove("colmap")
        assertTrue(failure(payload).contains("colmap"))
    }

    @Test fun uncheckedOrInconsistentReadyFieldCannotAuthorizeUpload() {
        val payload = healthy()
        payload.getJSONObject("runtime").put("checked", false)
        assertTrue(failure(payload).contains("chưa kiểm tra"))
        payload.getJSONObject("runtime").put("checked", true)
        payload.put("ready", false)
        assertTrue(failure(payload).contains("chưa sẵn sàng"))
        payload.put("ready", "true")
        assertTrue(failure(payload).contains("chưa sẵn sàng"))
    }
}
