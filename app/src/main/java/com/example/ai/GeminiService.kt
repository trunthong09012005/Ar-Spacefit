package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.core.graphics.scale
import com.example.BuildConfig
import com.example.ar.ModelParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class RecognizedItemResult(
    val name: String,
    val category: String, // FURNITURE
    val roomType: String, // LIVING_ROOM, BEDROOM, OFFICE, KITCHEN, WARDROBE
    val widthCm: Float,
    val heightCm: Float,
    val depthCm: Float,
    val colorHex: String,
    val material: String,
    val modelType3D: String, // SOFA, CHAIR, TABLE, BED, LAMP, WARDROBE, PLANT, BOX
    val advice: String,
    val estimatedPrice: String = "",
    val modelParameters: ModelParameters = ModelParameters.defaults(modelType3D)
)

class GeminiServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GeminiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val modelName = "gemini-3.5-flash"

    suspend fun analyzeItemFromImages(bitmaps: List<Bitmap>): RecognizedItemResult = withContext(Dispatchers.IO) {
        if (bitmaps.size < 5) {
            throw GeminiServiceException("Cần đủ 5 hướng quét trước khi phân tích nội thất.")
        }
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            throw GeminiServiceException("Chưa cấu hình GEMINI_API_KEY. Hãy kiểm tra tệp .env.")
        }

        try {
            val prompt = """
                Bạn là chuyên gia nhận diện sản phẩm nội thất.
                Năm ảnh đính kèm lần lượt là tổng thể, bên trái, bên phải, từ trên xuống và từ dưới lên của CÙNG MỘT món nội thất.
                Ảnh đã được ứng dụng cắt gần theo vùng vật thể được theo dõi.
                Hãy đối chiếu cả năm hướng để nhận diện hình dáng, màu sắc, vật liệu và tỷ lệ của món đồ.
                Kiểm tra ảnh có cùng một món đồ và có đủ thông tin để nhận diện không; không mặc nhiên tin nhãn góc chụp.
                Nếu không phải nội thất, trả isFurniture=false. Nếu ảnh khác vật hoặc quá mờ để đối chiếu, trả sameObject=false.
                Khi một trong hai giá trị là false, chỉ cần trả thêm advice giải thích cách quét lại; không tạo thông tin sản phẩm.
                Trả về kết quả định dạng JSON chuẩn duy nhất (không bọc trong markdown codeblock):
                {
                   "isFurniture": true,
                   "sameObject": true,
                   "name": "Tên đồ vật cụ thể bằng tiếng Việt",
                   "category": "FURNITURE",
                   "roomType": "LIVING_ROOM hoặc BEDROOM hoặc OFFICE hoặc KITCHEN",
                   "widthCm": số float kích thước tham khảo theo cm,
                   "heightCm": số float kích thước tham khảo theo cm,
                   "depthCm": số float kích thước tham khảo theo cm,
                   "colorHex": "mã màu hex đại diện chính ví dụ #264653",
                   "material": "Chất liệu chính ví dụ Vải nỉ, Gỗ, Kim loại hoặc Da",
                   "modelType3D": "SOFA hoặc CHAIR hoặc TABLE hoặc BED hoặc LAMP hoặc WARDROBE hoặc PLANT hoặc BOX",
                   "modelParameters": {
                      "seatHeightRatio": tỷ lệ mặt ghế so với chiều cao toàn bộ từ 0.3 đến 0.65 (chỉ SOFA hoặc CHAIR),
                      "hasArmrests": true hoặc false (chỉ SOFA hoặc CHAIR),
                      "topThicknessRatio": tỷ lệ độ dày mặt bàn so với chiều cao từ 0.03 đến 0.2 (chỉ TABLE),
                      "doorCount": số cánh tủ từ 1 đến 4 (chỉ WARDROBE)
                   },
                   "advice": "Một lời khuyên bố trí ngắn gọn bằng tiếng Việt"
                }
                Chỉ điền các tham số hình dáng áp dụng được và nhìn rõ; bỏ trường chưa biết. Đây là mô hình 3D tham số đại diện, không phải bản sao.
                Không bịa số đo chính xác: không có vật chuẩn thì không thể đo cm từ ảnh. Cho kích thước null nếu không có cơ sở ước lượng; người dùng sẽ đo và nhập lại.
                Nếu không rõ chất liệu, cho material chuỗi rỗng. Nếu ảnh lặp góc hoặc chi tiết bị che, nói rõ hạn chế trong advice.
            """.trimIndent()

            val angleLabels = listOf(
                "Tổng thể",
                "Bên trái",
                "Bên phải",
                "Từ trên xuống",
                "Từ dưới lên"
            )

            val requestJson = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                            bitmaps.take(5).forEachIndexed { index, bitmap ->
                                put(JSONObject().apply {
                                    put("text", "Ảnh ${index + 1}: ${angleLabels[index]}")
                                })
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", bitmapToBase64(bitmap))
                                    })
                                })
                            }
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
                .post(requestJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e("GeminiService", "API error: ${response.code}")
                    throw GeminiServiceException("Gemini API trả lỗi ${response.code}. Vui lòng thử lại.")
                }

                val parsedJson = JSONObject(responseBody)
                val candidates = parsedJson.optJSONArray("candidates")
                val candidate = candidates?.optJSONObject(0)
                val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                val text = (0 until (parts?.length() ?: 0)).mapNotNull { index ->
                    parts?.optJSONObject(index)?.takeUnless { it.optBoolean("thought", false) }?.optString("text")
                }.joinToString("\n")
                RecognitionParser.parse(text)
            }
        } catch (e: GeminiServiceException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Do not log exception URLs, API keys or the user's image payload.
            Log.e("GeminiService", "Analysis failed: ${e.javaClass.simpleName}")
            throw GeminiServiceException("Không thể phân tích ảnh. Kiểm tra mạng rồi thử lại.", e)
        }
    }


    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        // Scale down if oversized to avoid payload limits
        val maxDim = 1024
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val targetW = if (ratio > 1) maxDim else (maxDim * ratio).toInt()
            val targetH = if (ratio > 1) (maxDim / ratio).toInt() else maxDim
            bitmap.scale(targetW, targetH)
        } else {
            bitmap
        }
        return try {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            stream.close()
        }
    }
}
