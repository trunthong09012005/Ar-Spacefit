package com.example.ar

import org.json.JSONObject

/** Ratios describe a representative model, not a photogrammetry reconstruction. */
data class ModelParameters(
    val seatHeightRatio: Float = 0.46f,
    val hasArmrests: Boolean = false,
    val topThicknessRatio: Float = 0.07f,
    val doorCount: Int = 2
) {
    fun sanitized() = copy(
        seatHeightRatio = seatHeightRatio.finiteOr(0.46f).coerceIn(0.3f, 0.65f),
        topThicknessRatio = topThicknessRatio.finiteOr(0.07f).coerceIn(0.03f, 0.2f),
        doorCount = doorCount.coerceIn(1, 4)
    )

    fun toJson(): String = sanitized().let {
        JSONObject().put("seatHeightRatio", it.seatHeightRatio.toDouble())
            .put("hasArmrests", it.hasArmrests)
            .put("topThicknessRatio", it.topThicknessRatio.toDouble())
            .put("doorCount", it.doorCount).toString()
    }

    companion object {
        fun defaults(type: String) = ModelParameters(hasArmrests = type == "SOFA")

        /** Old inventory rows have no parameters; malformed local data uses a safe template. */
        fun fromJson(raw: String, type: String): ModelParameters {
            val defaults = defaults(type)
            if (raw.isBlank()) return defaults
            return runCatching {
                val json = JSONObject(raw)
                defaults.copy(
                    seatHeightRatio = json.optDouble("seatHeightRatio", defaults.seatHeightRatio.toDouble()).toFloat(),
                    hasArmrests = json.optBoolean("hasArmrests", defaults.hasArmrests),
                    topThicknessRatio = json.optDouble("topThicknessRatio", defaults.topThicknessRatio.toDouble()).toFloat(),
                    doorCount = json.optInt("doorCount", defaults.doorCount)
                ).sanitized()
            }.getOrDefault(defaults)
        }
    }
}

private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback

val modelTypeLabels = linkedMapOf(
    "SOFA" to "Sofa", "CHAIR" to "Ghế", "TABLE" to "Bàn", "BED" to "Giường",
    "WARDROBE" to "Tủ", "LAMP" to "Đèn", "PLANT" to "Cây cảnh", "BOX" to "Khối đơn giản"
)

/** Scope of this indoor-furniture prototype: each outer dimension is 1–1000 cm. */
fun parseDimensionCm(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()
    ?.takeIf { it.isFinite() && it in 1f..1000f }
