package com.example.reconstruction

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.camera.decodeSampledBitmap
import com.example.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

// backendUrl is persisted BEFORE the first POST, including an upload whose response is lost.
// The idempotency key can only be reused with the same capture and the same backend.
val ScanSessionEntity.canEditCapture: Boolean get() = backendUrl.isBlank() && jobId.isBlank()
private const val FROZEN_CAPTURE_MESSAGE = "Phiên đã gửi lên laptop. Hãy tạo phiên mới để đổi ảnh/video; Generate chỉ gửi lại dữ liệu cũ."

class ScanViewModel internal constructor(
    application: Application,
    private val db: AppDatabase,
    private val createClient: (String, String) -> BackendClient
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AppDatabase.getInstance(application), ::BackendClient)
    private val dao = db.projectDao()
    val settings = BackendSettings(application)
    val scans = dao.scans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val message = MutableStateFlow("")
    val uploadProgress = MutableStateFlow(0)
    private val operationLock = Mutex()
    private val operations = mutableMapOf<String, Job>()

    fun directory(id: String) = File(getApplication<Application>().filesDir, "scans/$id").apply { mkdirs() }
    fun images(id: String) = directory(id).listFiles { f -> f.name.startsWith("image-") && f.extension == "jpg" }?.sortedBy { it.name }.orEmpty()

    init {
        viewModelScope.launch {
            dao.scans().first().forEach { scan ->
                if (scan.status == "uploading") dao.saveScan(scan.copy(status="ready", error="Tải lên bị gián đoạn. Bấm Generate để gửi lại cùng phiên."))
                else if (scan.jobId.isNotBlank() && scan.status in setOf("queued", "processing", "calibration_queued", "calibrating")) refresh(scan.id)
            }
        }
    }

    suspend fun create(name: String, type: String, axis: String, cm: Float): String {
        require(name.trim().length in 1..120 && cm.isFinite() && cm > 0 && cm <= 1000)
        require(type in setOf("CHAIR", "SOFA", "TABLE", "BED", "WARDROBE", "LAMP", "BOX"))
        require(axis in setOf("width", "height", "depth"))
        val scan = ScanSessionEntity(name=name.trim(), modelType=type, referenceAxis=axis, referenceCm=cm)
        dao.saveScan(scan)
        return scan.id
    }

    suspend fun addPhoto(id: String, bitmap: Bitmap): Boolean = withContext(Dispatchers.Default) {
        operationLock.withLock {
            val scan = dao.scan(id) ?: return@withLock false
            if (!scan.canEditCapture) {
                message.value = FROZEN_CAPTURE_MESSAGE
                return@withLock false
            }
            val files = images(id)
            if (files.size >= 150) return@withLock false
            if (imageSharpness(bitmap) < 35) {
                message.value = "Ảnh hơi mờ — giữ máy ổn định rồi chụp lại."
                return@withLock false
            }
            val path = File(directory(id), "image-${System.nanoTime()}.jpg")
            withContext(Dispatchers.IO) {
                path.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                dao.saveScan(scan.copy(imageCount=files.size + 1, status="capturing", error=""))
            }
            message.value = "Đã lưu ${files.size + 1}/150 ảnh. Tiếp tục di chuyển quanh vật thể."
            true
        }
    }

    fun importImages(id: String, uris: List<Uri>) = operate(id) {
        require(requireNotNull(dao.scan(id)).canEditCapture) { FROZEN_CAPTURE_MESSAGE }
        for (uri in uris.take(150 - images(id).size)) {
            val bitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(getApplication(), uri, 2400) }
                ?: error("Không đọc được ảnh đã chọn")
            try { addPhoto(id, bitmap) } finally { bitmap.recycle() }
        }
    }

    fun importVideo(id: String, uri: Uri) = operate(id) {
        operationLock.withLock {
            val scan = requireNotNull(dao.scan(id))
            require(scan.canEditCapture) { FROZEN_CAPTURE_MESSAGE }
            withContext(Dispatchers.IO) {
                val output = File(directory(id), "capture.mp4")
                val partial = File(directory(id), "capture.mp4.part")
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        partial.outputStream().use { target ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 1024L * 1024 * 1024) { "Video tối đa 1 GB" }
                                target.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Không đọc được video")
                    check(partial.renameTo(output))
                    dao.saveScan(scan.copy(status="ready", error="", stage="video_imported"))
                } finally { partial.delete() }
            }
        }
    }

    fun generate(id: String) = operate(id) {
        val backendUrl = settings.url
        val token = settings.token
        val initial = requireNotNull(dao.scan(id))
        require(initial.jobId.isBlank()) { "Phiên đã có job. Dùng Cập nhật / tải lại kết quả." }
        require(initial.backendUrl.isBlank() || initial.backendUrl == backendUrl) { "Hãy kết nối lại đúng laptop: ${initial.backendUrl}" }
        val client = createClient(backendUrl, token)
        val capabilities = client.capabilities()
        requireNativeReady(capabilities)
        val (scan, files) = operationLock.withLock {
            // A camera callback may finish while capabilities is in flight. Read the latest row,
            // then freeze its input before releasing the lock or starting any upload.
            val latest = requireNotNull(dao.scan(id))
            val video = File(directory(id), "capture.mp4")
            val selectedFiles = if (video.isFile) listOf(video) else images(id)
            require(video.isFile || selectedFiles.size in capabilities.getInt("min_images")..capabilities.getInt("max_images")) {
                "Cần ${capabilities.getInt("min_images")}–${capabilities.getInt("max_images")} ảnh rõ hoặc một video."
            }
            val frozen = latest.copy(backendUrl=backendUrl, status="uploading", error="", stage="uploading")
            dao.saveScan(frozen)
            frozen to selectedFiles
        }
        uploadProgress.value = 0
        val metadata = JSONObject().put("name", scan.name).put("category", scan.modelType)
            .put("reference_axis", scan.referenceAxis).put("reference_cm", scan.referenceCm)
        val job = client.upload(id, metadata, files) { uploadProgress.value = it }
        dao.saveScan(scan.copy(jobId=job.getString("id"), status=job.getString("status"), stage=job.getString("stage")))
        poll(id, client)
    }

    fun refresh(id: String) = operate(id) {
        val scan = requireNotNull(dao.scan(id))
        require(scan.backendUrl == settings.url) { "Hãy kết nối lại đúng laptop: ${scan.backendUrl}" }
        poll(id, createClient(scan.backendUrl, settings.token))
    }

    fun action(id: String, action: String, body: JSONObject = JSONObject()) {
        if (action == "cancel") {
            operations.remove(id)?.cancel()
            busy.value = busy.value - id
        }
        operate(id) {
            val scan = requireNotNull(dao.scan(id))
            require(scan.backendUrl == settings.url) { "Phiên này thuộc laptop ${scan.backendUrl}" }
            val client = createClient(scan.backendUrl, settings.token)
            val job = client.action(scan.jobId, action, body)
            dao.saveScan(scan.copy(status=job.getString("status"), error="", progress=job.optInt("progress")))
            if (action != "cancel") poll(id, client)
        }
    }

    private suspend fun poll(id: String, client: BackendClient) {
        while (true) {
            currentCoroutineContext().ensureActive()
            var scan = requireNotNull(dao.scan(id))
            val job = client.job(scan.jobId)
            val status = job.getString("status")
            val resultJson = job.optJSONObject("result")?.toString().orEmpty()
            scan = scan.copy(status=status, progress=job.optInt("progress"), stage=job.optString("stage"),
                error=scanFailureMessage(job.optString("error").takeUnless { it == "null" }.orEmpty()),
                // Preserve the cached model/scale pair through HTTP/validation failure or
                // recalibration. New metadata becomes visible only with its downloaded GLB.
                resultJson=if (scan.modelPath.isNotBlank() || status == "completed") scan.resultJson else resultJson)
            dao.saveScan(scan)
            if (status in setOf("needs_review", "completed")) {
                if (scan.previewPath.isBlank() || !File(scan.previewPath).exists()) {
                    val file = client.download(scan.jobId, "preview", File(directory(id), "preview-${System.nanoTime()}.glb"))
                    scan = scan.copy(previewPath=file.absolutePath)
                    dao.saveScan(scan)
                }
                if (scan.thumbnailPath.isBlank()) {
                    val thumbnail = client.download(scan.jobId, "thumbnail", File(directory(id), "thumbnail.jpg"))
                    scan = scan.copy(thumbnailPath=thumbnail.absolutePath)
                    dao.saveScan(scan)
                }
                if (status == "completed") {
                    val file = client.download(scan.jobId, "model", File(directory(id), "model-${System.nanoTime()}.glb"))
                    scan = scan.copy(modelPath=file.absolutePath, resultJson=resultJson)
                    dao.saveScan(scan)
                }
                break
            }
            if (status in setOf("failed", "cancelled")) break
            delay(2500)
        }
    }

    fun saveToInventory(id: String) = operate(id) {
        db.withTransaction {
            val scan = requireNotNull(dao.scan(id))
            require(scan.status == "completed" && File(scan.modelPath).isFile) { "Cần tải model đã xác nhận tỷ lệ trước khi lưu" }
            val result = JSONObject(scan.resultJson)
            require(result.getBoolean("scale_confirmed"))
            val old = if (scan.savedItemId != 0L) db.itemDao().getItemById(scan.savedItemId) else null
            val item = ItemEntity(id=old?.id ?: 0, name=scan.name, category="FURNITURE", roomType="LIVING_ROOM",
                widthCm=result.getDouble("width_cm").toFloat(), heightCm=result.getDouble("height_cm").toFloat(),
                depthCm=result.getDouble("depth_cm").toFloat(), colorHex="#FFFFFF", material="Texture từ ảnh quét",
                imageUri=scan.thumbnailPath, modelType3D=scan.modelType, modelPath=scan.modelPath, modelSource="PHOTOGRAMMETRY",
                polygonCount=result.getInt("polygon_count"), scanSessionId=id, scaleConfirmed=true,
                notes="Kích thước suy ra từ ${scan.referenceAxis} tham chiếu; cần đối chiếu thước đo.")
            val itemId = db.itemDao().insertItem(item)
            dao.saveScan(scan.copy(savedItemId=itemId))
            message.value = "Đã lưu ${scan.name} vào My Furniture."
        }
    }

    private fun operate(id: String, block: suspend () -> Unit) {
        if (id in busy.value) return
        // A previous failed preflight must not remain beside a newer job result.
        message.value = ""
        busy.value = busy.value + id
        val operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val explanation = error.localizedMessage ?: "Không thể hoàn tất. Kiểm tra kết nối laptop rồi thử lại."
                message.value = explanation
                dao.scan(id)?.let { scan -> dao.saveScan(scan.copy(error=explanation,
                    status=if (scan.status == "uploading") "ready" else scan.status)) }
            } finally {
                if (operations[id] == currentCoroutineContext()[Job]) {
                    operations.remove(id)
                    busy.value = busy.value - id
                }
            }
        }
        operations[id] = operation
        operation.start()
    }
}

fun imageSharpness(bitmap: Bitmap): Double {
    val longest = maxOf(bitmap.width, bitmap.height)
    val sample = if (longest > 640) Bitmap.createScaledBitmap(bitmap, (bitmap.width * 640 / longest).coerceAtLeast(1), (bitmap.height * 640 / longest).coerceAtLeast(1), true) else bitmap
    try {
        val width = sample.width
        val height = sample.height
        if (width < 3 || height < 3) return 0.0
        val pixels = IntArray(width * height)
        sample.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = DoubleArray(pixels.size) { i ->
            val c = pixels[i]
            0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)
        }
        var sum = 0.0; var squares = 0.0; var count = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val index = y * width + x
            val laplacian = gray[index - width] + gray[index + width] + gray[index - 1] + gray[index + 1] - 4 * gray[index]
            sum += laplacian; squares += laplacian * laplacian; count++
        }
        return squares / count - (sum / count) * (sum / count)
    } finally { if (sample !== bitmap) sample.recycle() }
}
