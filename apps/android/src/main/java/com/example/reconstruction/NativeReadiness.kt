package com.example.reconstruction

import org.json.JSONObject

private val nativeToolNames = listOf("colmap", "blender", "InterfaceCOLMAP", "DensifyPointCloud",
    "ReconstructMesh", "RefineMesh", "TextureMesh")

/** Fail closed: finding binaries alone (older backend) is not a startup check. */
internal fun requireNativeReady(capabilities: JSONObject) {
    val tools = capabilities.optJSONObject("tools")
        ?: error("Backend trả thông tin công cụ không hợp lệ. Cập nhật backend trên laptop.")
    require(nativeToolNames.all { tools.opt(it) is Boolean }) {
        "Backend trả thông tin công cụ không đầy đủ. Cập nhật backend trên laptop."
    }
    val missing = nativeToolNames.filter { tools.opt(it) == false }
    if (missing.isNotEmpty()) error("Laptop còn thiếu: ${missing.joinToString()}. Xem backend/README.md để cài đặt.")
    val runtime = capabilities.optJSONObject("runtime")
        ?: error("Backend chưa hỗ trợ kiểm tra khởi động native. Cập nhật backend trên laptop; tìm thấy file chưa đủ để quét.")
    if (runtime.opt("checking") == true) error("Laptop đang kiểm tra khởi động COLMAP/OpenMVS/Blender. Đợi khoảng một phút rồi bấm Generate lại.")
    if (runtime.opt("checked") != true) error("Laptop chưa kiểm tra được công cụ native. Thử lại và xem docs/NATIVE_RUNTIME.md.")
    val details = runtime.optJSONObject("tools")
        ?: error("Backend thiếu kết quả kiểm tra native. Thử lại hoặc cập nhật backend.")
    val failed = nativeToolNames.filter { details.optJSONObject(it)?.optString("status") != "ready" }
    if (failed.isNotEmpty()) {
        val explanation = failed.joinToString { name ->
            val result = details.optJSONObject(name)
            val reason = when (result?.optString("reason")) {
                "timeout" -> "quá thời gian"
                "invalid_help" -> "không đúng công cụ"
                "missing" -> "không tìm thấy"
                "docker_start_failed" -> "Docker không tạo được container"
                else -> "không khởi động được"
            }
            val code = result?.optString("hex_exit").orEmpty().takeIf { it.matches(Regex("0x[0-9a-fA-F]{1,8}")) }
            "$name ($reason${code?.let { ": $it" }.orEmpty()})"
        }
        val dockerHint = if (failed.any { details.optJSONObject(it)?.optString("reason") == "docker_start_failed" })
            " Kiểm tra Docker Desktop và quyền truy cập thư mục project trên laptop, rồi bấm Lưu và kiểm tra."
        else ""
        error("Công cụ native trên laptop bị lỗi: $explanation.$dockerHint Xem docs/NATIVE_RUNTIME.md; không tắt bảo vệ Windows.")
    }
    check(capabilities.opt("ready") == true) { "Laptop chưa sẵn sàng để quét. Thử lại và xem docs/NATIVE_RUNTIME.md." }
}
