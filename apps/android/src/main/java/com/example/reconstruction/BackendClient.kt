package com.example.reconstruction

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BackendClient(baseUrl: String, private val token: String) {
    private val base = validatedBackendUrl(baseUrl)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()

    private fun request(path: String) = Request.Builder().url(base + path)
        .header("Authorization", "Bearer $token")

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() }
                else response.close()
            }
        })
    }

    private suspend fun json(request: Request): JSONObject = withContext(Dispatchers.IO) {
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(errorMessage(response.code, text))
            JSONObject(text)
        }
    }

    suspend fun capabilities() = json(request("capabilities").build())
    suspend fun job(id: String) = json(request("jobs/${UUID.fromString(id)}").build())
    suspend fun action(id: String, action: String, body: JSONObject = JSONObject()): JSONObject {
        require(action in setOf("cancel", "retry", "calibrate"))
        return json(request("jobs/${UUID.fromString(id)}/$action")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
    }

    suspend fun upload(id: String, metadata: JSONObject, files: List<File>, onProgress: (Int) -> Unit): JSONObject {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", metadata.toString())
        files.forEach { file ->
            val mime = if (file.extension == "mp4") "video/mp4" else "image/jpeg"
            multipart.addFormDataPart("files", file.name, file.asRequestBody(mime.toMediaType()))
        }
        val body = multipart.build()
        val counted = object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun writeTo(sink: BufferedSink) {
                var sent = 0L
                var previous = -1
                val forwarding = object : ForwardingSink(sink) {
                    override fun write(source: okio.Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        sent += byteCount
                        val percent = (sent * 100 / contentLength().coerceAtLeast(1)).toInt().coerceIn(0, 100)
                        if (percent != previous) { previous = percent; onProgress(percent) }
                    }
                }.buffer()
                body.writeTo(forwarding)
                forwarding.flush()
            }
        }
        return json(request("jobs").header("X-Request-ID", UUID.fromString(id).toString()).post(counted).build())
    }

    suspend fun download(id: String, artifact: String, destination: File): File = withContext(Dispatchers.IO) {
        require(artifact in setOf("model", "preview", "thumbnail", "log"))
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        try {
            execute(request("jobs/${UUID.fromString(id)}/artifacts/$artifact").build()).use { response ->
                if (!response.isSuccessful) throw IOException(errorMessage(response.code, response.body?.string().orEmpty()))
                val body = response.body ?: throw IOException("Máy chủ trả về dữ liệu trống")
                require(body.contentLength() <= 150L * 1024 * 1024) { "Model vượt quá 150 MB; cần tối ưu thêm trên laptop." }
                var size = 0L
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= 150L * 1024 * 1024) { "File quá lớn" }
                        output.write(buffer, 0, count)
                    }
                } }
                if (body.contentLength() >= 0 && size != body.contentLength()) throw IOException("Tải chưa đủ dữ liệu; thử lại")
            }
            if (artifact in setOf("model", "preview")) {
                validateDownloadedGlb(partial)
            }
            check(partial.renameTo(destination)) { "Không thể lưu file vào bộ nhớ ứng dụng" }
            destination
        } finally { partial.delete() }
    }
}

fun validatedBackendUrl(value: String): String {
    val url = value.trim().trimEnd('/').toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "URL không được chứa tài khoản hoặc tham số" }
    require(url.encodedPath == "/") { "Nhập URL gốc, ví dụ http://127.0.0.1:8000" }
    if (!url.isHttps) {
        val host = url.host
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        val privateIpv4 = octets.size == 4 && octets.all { it in 0..255 } &&
            (octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31))
        require(privateIpv4 || host == "localhost" || host == "::1") { "HTTP chỉ dùng cho USB/LAN; máy chủ Internet cần HTTPS" }
    }
    return url.toString()
}

private fun errorMessage(code: Int, body: String): String {
    val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
    return when (code) {
        401 -> "Mã ghép nối không đúng. Kiểm tra cấu hình laptop."
        413 -> "Bộ ảnh/video vượt giới hạn dung lượng của máy chủ."
        else -> "Máy chủ ($code): ${detail.ifBlank { "Không thể xử lý yêu cầu" }.take(800)}"
    }
}
