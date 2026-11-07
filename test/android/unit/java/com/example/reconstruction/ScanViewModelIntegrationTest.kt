package com.example.reconstruction

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.ScanSessionEntity
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Room + real HTTP client; server artifacts are explicitly synthetic fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class ScanViewModelIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val token="test-only-scan-refresh-token"
    private fun result(width:Int)=JSONObject().put("scale_confirmed",true)
        .put("width_cm",width).put("depth_cm",25).put("height_cm",35).put("polygon_count",12).toString()
    private fun glb():ByteArray {
        val json="""{"asset":{"version":"2.0"},"extras":{"fixture":true}}"""
        val padded=json.padEnd((json.length+3)/4*4,' ').toByteArray()
        return ByteBuffer.allocate(20+padded.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(20+padded.size).putInt(padded.size).putInt(0x4E4F534A).put(padded).array()
    }
    private data class Reply(val status:Int,val bytes:ByteArray)
    private data class Request(val path:String,val requestId:String?,val body:String)
    private class Server(private val reply:(String)->Reply):AutoCloseable {
        val paths=CopyOnWriteArrayList<String>()
        val auth=CopyOnWriteArrayList<String>()
        val requests=CopyOnWriteArrayList<Request>()
        private val executor=Executors.newCachedThreadPool{r->Thread(r,"scan-http-fixture").apply{isDaemon=true}}
        private val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            this.executor=this@Server.executor
            createContext("/"){exchange->exchange.use {
                val path=it.requestURI.path
                paths.add(path);auth.add(it.requestHeaders.getFirst("Authorization").orEmpty())
                requests.add(Request(path,it.requestHeaders.getFirst("X-Request-ID"),it.requestBody.readBytes().toString(Charsets.UTF_8)))
                val response=reply(path)
                it.sendResponseHeaders(response.status,response.bytes.size.toLong())
                it.responseBody.write(response.bytes)
            }}
            start()
        }
        val url="http://127.0.0.1:${server.address.port}/"
        override fun close(){server.stop(0);executor.shutdownNow()}
    }

    private fun ready():Reply {
        val names=listOf("colmap","blender","InterfaceCOLMAP","DensifyPointCloud","ReconstructMesh","RefineMesh","TextureMesh")
        return Reply(200,JSONObject().put("ready",true).put("min_images",50).put("max_images",150)
            .put("tools",JSONObject().apply{names.forEach{put(it,true)}})
            .put("runtime",JSONObject().put("checked",true).put("checking",false)
                .put("tools",JSONObject().apply{names.forEach{put(it,JSONObject().put("status","ready"))}}))
            .toString().toByteArray())
    }

    private suspend fun TestScope.withNewScan(
        url:String,
        block:suspend TestScope.(ScanViewModel,AppDatabase,ScanSessionEntity,ViewModelStore)->Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=ApplicationProvider.getApplicationContext<Application>()
        val preferences=app.getSharedPreferences("reconstruction_backend",0)
        preferences.edit().clear().putString("url",url).commit()
        val db=Room.inMemoryDatabaseBuilder(app,AppDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor{it.run()}.setTransactionExecutor{it.run()}.build()
        val holder=ViewModelStore()
        try {
            val scan=ScanSessionEntity(referenceCm=15f)
            db.projectDao().saveScan(scan)
            val vm=ScanViewModel(app,db){base,_->BackendClient(base,token)}
            holder.put("scan-fixture",vm)
            runCurrent()
            block(vm,db,scan,holder)
        } finally {holder.clear();db.close();preferences.edit().clear().commit();Dispatchers.resetMain()}
    }

    private fun changeBackend(url:String) {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("reconstruction_backend",0)
            .edit().putString("url",url).commit()
    }
    private suspend fun TestScope.withScan(
        modelReply:Reply,
        block:suspend TestScope.(ScanViewModel,AppDatabase,ScanSessionEntity,Server)->Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=ApplicationProvider.getApplicationContext<Application>()
        val db=Room.inMemoryDatabaseBuilder(app,AppDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor{it.run()}.setTransactionExecutor{it.run()}.build()
        val holder=ViewModelStore()
        val id=UUID.randomUUID().toString()
        val oldModel=temporary.newFile("verified-old.glb").apply{writeBytes(glb())}
        val preview=temporary.newFile("preview-fixture.glb").apply{writeBytes(glb())}
        val thumbnail=temporary.newFile("thumbnail-fixture.jpg").apply{writeText("explicit thumbnail fixture")}
        val status=JSONObject().put("id",id).put("status","completed").put("stage","completed")
            .put("progress",100).put("result",JSONObject(result(19))).toString().toByteArray()
        Server { path->when(path) {
            "/jobs/$id"->Reply(200,status)
            "/jobs/$id/artifacts/model"->modelReply
            else->Reply(404,"missing fixture".toByteArray())
        }}.use { server->
            val preferences=app.getSharedPreferences("reconstruction_backend",0)
            preferences.edit().clear().putString("url",server.url).commit()
            val scan=ScanSessionEntity(id=id,jobId=id,backendUrl=server.url,status="completed",
                modelPath=oldModel.absolutePath,previewPath=preview.absolutePath,thumbnailPath=thumbnail.absolutePath,
                resultJson=result(15),referenceCm=15f)
            try {
                db.projectDao().saveScan(scan)
                val vm=ScanViewModel(app,db){url,_->BackendClient(url,token)}
                holder.put("scan-fixture",vm)
                runCurrent()
                block(vm,db,scan,server)
                assertTrue(server.auth.isNotEmpty())
                assertTrue(server.auth.all{it=="Bearer $token"})
            } finally {holder.clear();db.close();preferences.edit().clear().commit();Dispatchers.resetMain()}
        }
    }

    @Test fun failedRefreshKeepsVerifiedModelAndItsMatchingMetadata()=runTest {
        withScan(Reply(500,"""{"detail":"download fixture failure"}""".toByteArray())) { vm,db,original,server->
            val bytes=java.io.File(original.modelPath).readBytes()
            vm.refresh(original.id)
            vm.busy.first{original.id !in it}
            val persisted=requireNotNull(db.projectDao().scan(original.id))
            assertEquals(original.modelPath,persisted.modelPath)
            assertEquals(original.resultJson,persisted.resultJson)
            assertEquals(original.previewPath,persisted.previewPath)
            assertEquals(original.thumbnailPath,persisted.thumbnailPath)
            assertArrayEquals(bytes,java.io.File(original.modelPath).readBytes())
            assertTrue(persisted.error.contains("download fixture failure"))
            assertEquals(listOf("/jobs/${original.id}","/jobs/${original.id}/artifacts/model"),server.paths)
            assertFalse(vm.directory(original.id).listFiles().orEmpty().any{it.name.endsWith(".part")})
            vm.saveToInventory(original.id)
            vm.busy.first{original.id !in it}
            val saved=requireNotNull(db.projectDao().scan(original.id))
            val item=requireNotNull(db.itemDao().getItemById(saved.savedItemId))
            assertEquals(15f,item.widthCm)
            assertEquals(original.modelPath,item.modelPath)
        }
    }

    @Test fun confirmedOomRefreshPersistsHelpfulErrorWithoutRetryOrInputLoss()=runTest {
        val jobId=UUID.randomUUID().toString()
        val failed=JSONObject().put("id",jobId).put("status","failed").put("stage","refine")
            .put("progress",80).put("error","NATIVE_OUT_OF_MEMORY: explicit HTTP fixture").toString().toByteArray()
        Server{path->if(path=="/jobs/$jobId") Reply(200,failed) else Reply(404,byteArrayOf())}.use { server->
            withNewScan(server.url) {vm,db,original,_->
                val input=File(vm.directory(original.id),"capture-fixture.txt").apply{writeText("preserved input fixture")}
                db.projectDao().saveScan(original.copy(jobId=jobId,backendUrl=server.url,status="processing",imageCount=52))
                vm.refresh(original.id)
                vm.busy.first{original.id !in it}
                val saved=requireNotNull(db.projectDao().scan(original.id))
                assertEquals("failed",saved.status)
                assertEquals("refine",saved.stage)
                assertEquals(80,saved.progress)
                assertEquals(52,saved.imageCount)
                assertTrue(saved.error.contains("Docker xác nhận"))
                assertTrue(saved.error.contains("thiếu RAM"))
                assertTrue(saved.modelPath.isBlank())
                assertEquals("preserved input fixture",input.readText())
                assertEquals(listOf("/jobs/$jobId"),server.paths)
            }
        }
    }

    @Test fun invalidGlbCannotReplaceCachedModelOrScale()=runTest {
        withScan(Reply(200,"not a GLB fixture".toByteArray())) {vm,db,original,_->
            vm.refresh(original.id)
            vm.busy.first{original.id !in it}
            val saved=requireNotNull(db.projectDao().scan(original.id))
            assertEquals(original.modelPath,saved.modelPath)
            assertEquals(original.resultJson,saved.resultJson)
            assertTrue(saved.error.isNotBlank())
            assertFalse(vm.directory(original.id).listFiles().orEmpty().any{it.name.endsWith(".part")})
        }
    }

    @Test fun firstDownloadFailureNeverPromotesUnscaledPreviewIntoInventory()=runTest {
        withScan(Reply(500,"""{"detail":"download fixture failure"}""".toByteArray())) {vm,db,original,_->
            db.projectDao().saveScan(original.copy(modelPath="",resultJson=""))
            vm.refresh(original.id)
            vm.busy.first{original.id !in it}
            val failed=requireNotNull(db.projectDao().scan(original.id))
            assertEquals("",failed.modelPath)
            assertEquals("",failed.resultJson)
            assertTrue(File(failed.previewPath).isFile)
            vm.saveToInventory(original.id)
            vm.busy.first{original.id !in it}
            val saved=requireNotNull(db.projectDao().scan(original.id))
            assertEquals(0L,saved.savedItemId)
            assertTrue(saved.error.contains("model đã xác nhận"))
        }
    }

    @Test fun successfulRefreshPublishesModelAndScaleTogetherWithoutDeletingOldInventoryAsset()=runTest {
        withScan(Reply(200,glb())) {vm,db,original,_->
            vm.message.value="Laptop đang kiểm tra khởi động COLMAP/OpenMVS/Blender."
            vm.refresh(original.id)
            vm.busy.first{original.id !in it}
            assertEquals("",vm.message.value)
            val saved=requireNotNull(db.projectDao().scan(original.id))
            assertNotEquals(original.modelPath,saved.modelPath)
            assertArrayEquals(glb(),File(saved.modelPath).readBytes())
            assertTrue(File(original.modelPath).isFile)
            assertEquals(result(19),saved.resultJson)
            vm.saveToInventory(original.id)
            vm.busy.first{original.id !in it}
            val item=requireNotNull(db.itemDao().getItemById(requireNotNull(db.projectDao().scan(original.id)).savedItemId))
            assertEquals(19f,item.widthCm)
            assertEquals(saved.modelPath,item.modelPath)
        }
    }

    @Test fun failedUploadFreezesInputsAcrossRecreationAndRetriesIdenticalCapture()=runTest {
        val uploads=AtomicInteger()
        val jobId=UUID.randomUUID().toString()
        val cancelled=Reply(200,"""{"id":"$jobId","status":"cancelled","stage":"cancelled"}""".toByteArray())
        Server{path->when(path){
            "/capabilities"->ready()
            "/jobs"->if(uploads.incrementAndGet()==1) Reply(500,"""{"detail":"response lost fixture"}""".toByteArray()) else cancelled
            "/jobs/$jobId"->cancelled
            else->Reply(404,byteArrayOf())
        }}.use{server->withNewScan(server.url){vm,db,scan,holder->
            val video=File(vm.directory(scan.id),"capture.mp4").apply{writeText("synthetic capture A")}
            vm.generate(scan.id)
            vm.busy.first{scan.id !in it}
            assertEquals(server.url,requireNotNull(db.projectDao().scan(scan.id)).backendUrl)
            holder.clear()
            val restarted=ScanViewModel(ApplicationProvider.getApplicationContext(),db){base,_->BackendClient(base,token)}
            holder.put("restarted",restarted)
            runCurrent()
            val other=temporary.newFile("other.mp4").apply{writeText("synthetic capture B")}
            restarted.importVideo(scan.id,Uri.fromFile(other))
            restarted.busy.first{scan.id !in it}
            assertEquals("synthetic capture A",video.readText())
            assertTrue(requireNotNull(db.projectDao().scan(scan.id)).error.contains("phiên mới"))
            val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {
                for(y in 0 until height) for(x in 0 until width) setPixel(x,y,if((x+y)%2==0) Color.BLACK else Color.WHITE)
            }
            try {assertFalse(restarted.addPhoto(scan.id,bitmap))} finally {bitmap.recycle()}
            assertTrue(restarted.images(scan.id).isEmpty())
            restarted.importImages(scan.id,listOf(Uri.parse("content://invalid-fixture/photo")))
            restarted.busy.first{scan.id !in it}
            assertTrue(requireNotNull(db.projectDao().scan(scan.id)).error.contains("phiên mới"))
            Server{Reply(500,byteArrayOf())}.use{otherBackend->
                changeBackend(otherBackend.url)
                restarted.generate(scan.id)
                restarted.busy.first{scan.id !in it}
                assertTrue(otherBackend.requests.isEmpty())
            }
            changeBackend(server.url)
            restarted.generate(scan.id)
            restarted.busy.first{scan.id !in it}
            val posted=server.requests.filter{it.path=="/jobs"}
            assertEquals(2,posted.size)
            assertTrue(posted.all{it.requestId==scan.id && it.body.contains("synthetic capture A") && !it.body.contains("synthetic capture B")})
            assertEquals(jobId,requireNotNull(db.projectDao().scan(scan.id)).jobId)
        }}
    }

    @Test fun changingSettingsDuringPreflightCannotRebindUploadToDifferentLaptop()=runTest {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val jobId=UUID.randomUUID().toString()
        val cancelled=Reply(200,"""{"id":"$jobId","status":"cancelled","stage":"cancelled"}""".toByteArray())
        Server{path->if(path=="/capabilities") {
            entered.countDown()
            check(release.await(15,TimeUnit.SECONDS)){"Fixture preflight was never released"}
            ready()
        } else cancelled}.use{first->Server{Reply(500,byteArrayOf())}.use{second->
            try {withNewScan(first.url){vm,db,scan,_->
                File(vm.directory(scan.id),"capture.mp4").writeText("synthetic video fixture")
                vm.generate(scan.id)
                runCurrent()
                assertTrue(withContext(Dispatchers.IO){entered.await(15,TimeUnit.SECONDS)})
                // A camera callback completing during preflight must not be overwritten by
                // the older row that generate() read before its HTTP suspension.
                val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {
                    for(y in 0 until height) for(x in 0 until width) setPixel(x,y,if((x+y)%2==0) Color.BLACK else Color.WHITE)
                }
                try {assertTrue(vm.addPhoto(scan.id,bitmap))} finally {bitmap.recycle()}
                changeBackend(second.url)
                release.countDown()
                vm.busy.first{scan.id !in it}
                assertEquals(first.url,requireNotNull(db.projectDao().scan(scan.id)).backendUrl)
                assertEquals(1,requireNotNull(db.projectDao().scan(scan.id)).imageCount)
                vm.refresh(scan.id)
                vm.busy.first{scan.id !in it}
                assertTrue(second.requests.isEmpty())
                assertEquals(listOf("/capabilities","/jobs","/jobs/$jobId"),first.paths)
            }} finally {release.countDown()}
        }}
    }

    @Test fun validationFailureBeforeUploadDoesNotFreezeTheCapture()=runTest {
        Server{ready()}.use{server->withNewScan(server.url){vm,db,scan,_->
            vm.generate(scan.id)
            vm.busy.first{scan.id !in it}
            assertEquals("",requireNotNull(db.projectDao().scan(scan.id)).backendUrl)
            assertEquals(listOf("/capabilities"),server.paths)
            val video=temporary.newFile("new.mp4").apply{writeText("synthetic newly imported video")}
            vm.importVideo(scan.id,Uri.fromFile(video))
            vm.busy.first{scan.id !in it}
            assertEquals(video.readText(),File(vm.directory(scan.id),"capture.mp4").readText())
            assertEquals("ready",requireNotNull(db.projectDao().scan(scan.id)).status)
            assertEquals("",requireNotNull(db.projectDao().scan(scan.id)).error)
        }}
    }

    @Test fun nativeNotReadyNeverUploadsOrFreezesValidInput()=runTest {
        val notReady=JSONObject(ready().bytes.toString(Charsets.UTF_8)).put("ready",false).apply {
            getJSONObject("runtime").put("checking",true)
        }
        Server{Reply(200,notReady.toString().toByteArray())}.use{server->withNewScan(server.url){vm,db,scan,_->
            File(vm.directory(scan.id),"capture.mp4").writeText("synthetic video fixture")
            vm.generate(scan.id)
            vm.busy.first{scan.id !in it}
            val saved=requireNotNull(db.projectDao().scan(scan.id))
            assertEquals("",saved.backendUrl)
            assertEquals("",saved.jobId)
            assertTrue(saved.canEditCapture)
            assertTrue(saved.error.contains("đang kiểm tra"))
            assertEquals(listOf("/capabilities"),server.paths)
        }}
    }
}
