package com.example.ai

import com.example.ar.ModelParameters
import com.example.ar.modelTypeLabels
import org.json.JSONObject

internal object RecognitionParser {
    fun parse(rawText: String): RecognizedItemResult {
        try {
            val cleaned = rawText.trim().removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JSONObject(cleaned)
            // Fail closed: a malformed/rejected analysis must not become an invented inventory item.
            if (!json.has("isFurniture") || !json.has("sameObject")) {
                throw GeminiServiceException("AI chưa xác nhận được món đồ. Hãy thử phân tích lại năm ảnh.")
            }
            if (!json.getBoolean("isFurniture") || !json.getBoolean("sameObject")) {
                throw GeminiServiceException(
                    "Chưa thể tạo mô hình: hãy quét rõ cùng một món nội thất ở năm hướng. " +
                        json.optString("advice", "").take(500)
                )
            }
            val name = json.optString("name").trim()
            val type = json.optString("modelType3D").uppercase()
            if (name.isBlank() || name == "null" || type !in modelTypeLabels) {
                throw GeminiServiceException("AI trả thiếu tên hoặc loại nội thất hợp lệ. Hãy thử phân tích lại.")
            }
            fun dimension(key: String): Float = json.optDouble(key, Double.NaN).toFloat()
                .takeIf { it.isFinite() && it in 1f..1000f } ?: 0f
            fun optionalText(key: String) = if (json.isNull(key)) "" else json.optString(key, "").trim()
            return RecognizedItemResult(
                name = name,
                category = "FURNITURE",
                roomType = json.optString("roomType").takeIf {
                    it in setOf("LIVING_ROOM", "BEDROOM", "OFFICE", "KITCHEN", "WARDROBE")
                } ?: "LIVING_ROOM",
                widthCm = dimension("widthCm"), heightCm = dimension("heightCm"), depthCm = dimension("depthCm"),
                colorHex = optionalText("colorHex").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#808080",
                material = optionalText("material"),
                modelType3D = type,
                advice = optionalText("advice"),
                modelParameters = ModelParameters.fromJson(json.optJSONObject("modelParameters")?.toString().orEmpty(), type)
            )
        } catch (e: GeminiServiceException) {
            throw e
        } catch (e: Exception) {
            throw GeminiServiceException("Gemini trả về dữ liệu không đúng định dạng. Hãy thử phân tích lại.", e)
        }
    }
}
