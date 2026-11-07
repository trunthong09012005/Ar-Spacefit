package com.example.reconstruction

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Real loopback HTTP exercises the production OkHttp client; replies are explicit fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class BackendClientIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val id=UUID.randomUUID().toString()
    private val token="test-only-pairing-token"
    private data class Request(val path:String,val auth:String?,val requestId:String?,val body:String)
    private class Server(val status:Int=200,val bytes:ByteArray="{}".toByteArray(),val headers:Map<String,String> = emptyMap()):AutoCloseable {
        val requests=CopyOnWriteArrayList<Request>()
        private val executor=Executors.newCachedThreadPool{r->Thread(r,"test-http").apply{isDaemon=true}}
        private val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            this.executor=this@Server.executor
            createContext("/"){exchange->
                exchange.use {
                    requests.add(Request(it.requestURI.path,it.requestHeaders.getFirst("Authorization"),it.requestHeaders.getFirst("X-Request-ID"),it.requestBody.readBytes().toString(Charsets.UTF_8)))
                    headers.forEach{(name,value)->it.responseHeaders.set(name,value)}
                    it.sendResponseHeaders(status,bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            start()
        }
        val url="http://127.0.0.1:${server.address.port}"
        override fun close(){server.stop(0);executor.shutdownNow()}
    }
    private fun validGlb():ByteArray {
        val json="""{"asset":{"version":"2.0"}}""".padEnd(28,' ').toByteArray()
        return ByteBuffer.allocate(20+json.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(20+json.size).putInt(json.size).putInt(0x4E4F534A).put(json).array()
    }

    @Test fun capabilitiesSendTokenAndParseResponse()=runBlocking {
        Server(bytes="""{"ready":true,"min_images":50}""".toByteArray()).use{s->
            assertTrue(BackendClient(s.url,token).capabilities().getBoolean("ready"))
            assertEquals("/capabilities",s.requests.single().path)
            assertEquals("Bearer $token",s.requests.single().auth)
        }
    }
    @Test fun nativeFailureFromHttpPreventsTheUploadGuardFromApproving()=runBlocking {
        val names=listOf("colmap","blender","InterfaceCOLMAP","DensifyPointCloud","ReconstructMesh","RefineMesh","TextureMesh")
        val reply=JSONObject().put("ready",false)
            .put("tools",JSONObject().apply { names.forEach { put(it,true) } })
            .put("runtime",JSONObject().put("checking",false).put("checked",true)
                .put("tools",JSONObject().apply { names.forEach { put(it,JSONObject().put("status","ready").put("reason","ok")) } }))
        for ((reason,code) in listOf("startup_failed" to "0xc0e90002", "docker_start_failed" to "0x7d")) {
            reply.getJSONObject("runtime").getJSONObject("tools").put("TextureMesh",JSONObject()
                .put("status","error").put("reason",reason).put("hex_exit",code))
            Server(bytes=reply.toString().toByteArray()).use{s->
                val received=BackendClient(s.url,token).capabilities()
                val failure=runCatching { requireNativeReady(received) }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains("TextureMesh"))
                assertTrue(failure.message.orEmpty().contains(code))
                if(reason=="docker_start_failed") assertTrue(failure.message.orEmpty().contains("Docker Desktop"))
                assertEquals(listOf("/capabilities"),s.requests.map { it.path })
            }
        }
    }
    @Test fun multipartUploadHasStableRequestIdMetadataAndProgress()=runBlocking {
        Server(status=202,bytes="""{"id":"$id","status":"queued"}""".toByteArray()).use{s->
            val file=temporary.newFile("capture.mp4").apply{writeText("fixture video")}
            val progress=CopyOnWriteArrayList<Int>()
            BackendClient(s.url,token).upload(id,JSONObject().put("name","Ghế").put("reference_cm",15),listOf(file),progress::add)
            val sent=s.requests.single()
            assertEquals(id,sent.requestId)
            assertTrue(sent.body.contains("name=\"metadata\""))
            assertTrue(sent.body.contains("\"reference_cm\":15"))
            assertTrue(sent.body.contains("filename=\"capture.mp4\""))
            assertEquals(100,progress.last())
            assertTrue(progress.zipWithNext().all{it.first<=it.second})
        }
    }
    @Test fun invalidIdOrActionCannotSendNetworkRequests()=runBlocking {
        Server().use{s->
            val client=BackendClient(s.url,token)
            assertTrue(runCatching{client.job("../private")}.isFailure)
            assertTrue(runCatching{client.action(id,"delete-all")}.isFailure)
            assertTrue(runCatching{client.download(id,"../database",temporary.newFile())}.isFailure)
            assertTrue(s.requests.isEmpty())
        }
    }
    @Test fun authenticationAndUploadLimitErrorsHaveUsefulMessages()=runBlocking {
        for((status,message) in listOf(401 to "Mã ghép nối",413 to "dung lượng",409 to "Chưa sẵn sàng")) {
            Server(status, """{"detail":"Chưa sẵn sàng"}""".toByteArray()).use{s->
                val failure=runCatching{BackendClient(s.url,token).capabilities()}.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains(message))
            }
        }
    }
    @Test fun redirectsNeverForwardPairingToken()=runBlocking {
        Server().use{target->Server(302,headers=mapOf("Location" to target.url+"/stolen")).use{s->
            assertTrue(runCatching{BackendClient(s.url,token).capabilities()}.isFailure)
            assertTrue(target.requests.isEmpty())
        }}
    }
    @Test fun validGlbDownloadIsByteExactAndLeavesNoPartialFile()=runBlocking {
        val bytes=validGlb()
        Server(bytes=bytes).use{s->
            val destination=temporary.root.resolve("model.glb")
            BackendClient(s.url,token).download(id,"model",destination)
            assertArrayEquals(bytes,destination.readBytes())
            assertFalse(temporary.root.resolve("model.glb.part").exists())
        }
    }
    @Test fun truncatedGlbCannotReplaceExistingDownloadedModel()=runBlocking {
        for((index,bytes) in listOf("glTF".toByteArray(),validGlb().dropLast(4).toByteArray(),"<html>error</html>".toByteArray()).withIndex()) {
            Server(bytes=bytes).use{s->
                val destination=temporary.newFile("existing-$index.glb").apply{writeText("previous verified model")}
                assertTrue(runCatching{BackendClient(s.url,token).download(id,"model",destination)}.isFailure)
                assertEquals("previous verified model",destination.readText())
                assertFalse(temporary.root.resolve(destination.name+".part").exists())
            }
        }
    }
    @Test fun serverErrorDoesNotPublishPartialArtifact()=runBlocking {
        Server(500, """{"detail":"export failed"}""".toByteArray()).use{s->
            val destination=temporary.root.resolve("failed.glb")
            assertTrue(runCatching{BackendClient(s.url,token).download(id,"model",destination)}.isFailure)
            assertFalse(destination.exists())
            assertFalse(temporary.root.resolve("failed.glb.part").exists())
        }
    }
    @Test fun fourMagicBytesAloneAreNotACompleteGlb()=runBlocking {
        Server(bytes="glTF".toByteArray()).use{s->
            val destination=temporary.root.resolve("truncated.glb")
            assertTrue(runCatching{BackendClient(s.url,token).download(id,"model",destination)}.isFailure)
            assertFalse(destination.exists())
        }
    }
}
