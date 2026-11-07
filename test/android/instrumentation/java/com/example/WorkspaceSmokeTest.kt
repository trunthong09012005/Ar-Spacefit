package com.example

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.sceneview.*
import io.github.sceneview.math.Position
import io.github.sceneview.node.CubeNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.example.ar.GlbWriter
import com.example.ar.PlacedFurniture
import com.example.data.AppDatabase
import com.example.data.ItemEntity
import com.example.data.RoomEntity
import com.example.data.FurniturePlacement
import com.example.ui.captureSurface
import com.example.ui.GlbItemDialog
import com.example.ui.RoomScene
import com.example.ui.rememberSpaceFitView
import com.example.ui.theme.MyApplicationTheme
import io.github.sceneview.SceneView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Runtime smoke coverage for Filament, navigation and a locally generated GLB fixture, not photogrammetry accuracy. */
@SdkSuppress(minSdkVersion=29)
class WorkspaceSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private var originalProjectIds:Set<String>?=null
    private var originalActiveProject:String?=null
    private var testItemId:Long?=null
    private val extraTestItemIds=mutableListOf<Long>()
    private var testFile:File?=null
    private var originalDesignImages:Set<String>?=null
    private val exportedNames=mutableListOf<String>()

    /** UI black-box with explicit HTTP error fixtures, not native/reconstruction success. */
    @Test fun laptopDialogDistinguishesCheckingFromBlockedNative(){
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish")){"Settings writes are restricted to the QA emulator"}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("reconstruction_backend",0)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalServiceFlags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        // Preserve encrypted bytes without decrypting or printing any original token.
        val originalUrl=preferences.getString("url",null)
        val originalCiphertext=preferences.getString("token_encrypted",null)
        val fixtureName="QA native guard ${java.util.UUID.randomUUID()}"
        val db=AppDatabase.getInstance(context)
        val originalScanIds=runBlocking{db.projectDao().scans().first().map{it.id}.toSet()}
        val names=listOf("colmap","blender","InterfaceCOLMAP","DensifyPointCloud","ReconstructMesh","RefineMesh","TextureMesh")
        val payload=JSONObject().put("ready",false).put("min_images",50).put("max_images",150)
            .put("tools",JSONObject().apply {names.forEach{put(it,true)}})
            .put("runtime",JSONObject().put("checked",false).put("checking",true))
        val response=AtomicReference(payload.toString())
        val requests=CopyOnWriteArrayList<String>()
        val stopped=AtomicBoolean(false)
        val serverFailure=AtomicReference<Throwable?>(null)
        val socket=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        val server=Thread({
            try {
                while(!stopped.get())socket.accept().use{client->
                    client.soTimeout=5000
                    val input=client.getInputStream().bufferedReader()
                    val first=input.readLine() ?: error("Missing HTTP request")
                    requests.add(first.split(' ')[1])
                    val headers=mutableListOf<String>()
                    while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;headers.add(line)}
                    check(headers.any{it.equals("Authorization: Bearer qa-native-dialog-only-token",ignoreCase=true)})
                    val bytes=response.get().toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply{
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes);flush()
                    }
                }
            }catch(failure:Throwable){if(!stopped.get())serverFailure.set(failure)}
        },"qa-native-dialog-http").apply{isDaemon=true;start()}
        try {
            compose.onNodeWithText("Quét 3D").performClick()
            compose.onNodeWithText("Cấu hình / kiểm tra laptop").performClick()
            compose.onNodeWithText("URL máy chủ").performTextReplacement("http://127.0.0.1:${socket.localPort}")
            compose.onNodeWithText("Mã ghép nối SPACEFIT_TOKEN").performTextReplacement("qa-native-dialog-only-token")
            compose.onNodeWithText("Lưu và kiểm tra").performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("Laptop đang kiểm tra",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Laptop đang kiểm tra",substring=true).assertIsDisplayed()
            val details=JSONObject().apply{names.forEach{put(it,JSONObject().put("status","ready").put("reason","ok"))}}
            details.put("TextureMesh",JSONObject().put("status","error").put("reason","startup_failed").put("hex_exit","0xc0e90002"))
            payload.put("runtime",JSONObject().put("checked",true).put("checking",false).put("tools",details))
            response.set(payload.toString())
            compose.onNodeWithText("Lưu và kiểm tra").performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("0xc0e90002",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("TextureMesh (không khởi động được: 0xc0e90002)",substring=true).assertIsDisplayed()
            try{
                compose.waitUntil(10000){automation.windows.none{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
            }catch(failure:Exception){screenshot("native-ime-failure-ui.png");throw failure}
            compose.onNodeWithText("Lưu và kiểm tra").assertIsDisplayed()
            compose.onNodeWithText("Đóng").assertIsDisplayed()
            compose.onAllNodesWithText("Các công cụ native khởi động được",substring=true).assertCountEquals(0)
            check(serverFailure.get()==null){"HTTP fixture failed: ${serverFailure.get()?.javaClass?.simpleName}"}
            check(requests==listOf("/capabilities","/capabilities")){"UI must not upload a job during diagnostic errors"}
            screenshot("native-blocked-dialog.png")
            compose.onNodeWithText("Đóng").performClick()
            compose.onNodeWithText("Bắt đầu phiên quét").performClick()
            compose.onNodeWithText("Tên món đồ").performTextReplacement(fixtureName)
            compose.onNodeWithText("Số đo thực bằng thước (cm)").performTextReplacement("15")
            compose.onNodeWithText("Tạo phiên").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("Generate 3D trên laptop").fetchSemanticsNodes().isNotEmpty()}
            val titleLayouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(fixtureName).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){check(it(titleLayouts))}
            check(titleLayouts.single().lineCount==1){"Long scan names must not overflow the toolbar"}
            compose.onNodeWithText("Generate 3D trên laptop").performScrollTo().performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("0xc0e90002",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onAllNodesWithText("TextureMesh (không khởi động được: 0xc0e90002)",substring=true).assertCountEquals(1)
            compose.onNodeWithText("TextureMesh (không khởi động được: 0xc0e90002)",substring=true).assertIsDisplayed()
            compose.waitUntil(10000){compose.onAllNodesWithText("Generate 3D trên laptop").fetchSemanticsNodes().single().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()}
            check(requests==listOf("/capabilities","/capabilities","/capabilities")){"Generate must not POST a job while startup failed"}
            screenshot("native-blocked-generate.png")
            details.put("TextureMesh",JSONObject().put("status","error").put("reason","docker_start_failed").put("hex_exit","0x7d"))
            response.set(payload.toString())
            compose.onNodeWithText("Generate 3D trên laptop").performScrollTo().performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("0x7d",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Docker không tạo được container: 0x7d",substring=true).assertIsDisplayed()
            compose.onNodeWithText("Docker Desktop",substring=true).assertIsDisplayed()
            compose.onAllNodesWithText("0xc0e90002",substring=true).assertCountEquals(0)
            check(requests==listOf("/capabilities","/capabilities","/capabilities","/capabilities")){"Docker failure must still prevent POST"}
            screenshot("docker-start-failed-generate.png")
        }finally{
            stopped.set(true);socket.close();server.join(5000)
            automation.serviceInfo=automation.serviceInfo.apply{flags=originalServiceFlags}
            compose.activityRule.scenario.close()
            runBlocking{
                db.projectDao().scans().first().filter{it.id !in originalScanIds && it.name==fixtureName}.forEach{scan->
                    check(java.util.UUID.fromString(scan.id).toString()==scan.id)
                    db.openHelper.writableDatabase.delete("scan_sessions","id = ?",arrayOf(scan.id))
                    // This test never imports/captures files. Delete only its empty directory.
                    File(context.filesDir,"scans/${scan.id}").takeIf{it.isDirectory && it.listFiles()?.isEmpty()==true}?.delete()
                }
            }
            preferences.edit().apply{
                if(originalUrl==null)remove("url") else putString("url",originalUrl)
                if(originalCiphertext==null)remove("token_encrypted") else putString("token_encrypted",originalCiphertext)
            }.commit()
        }
    }

    @After fun removeOnlyFixturesCreatedByThisTest(){
        val originals=originalProjectIds ?: return
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // Close ViewModels before cleanup so delayed autosave cannot resurrect a fixture.
        compose.activityRule.scenario.close()
        runBlocking {
            val db=AppDatabase.getInstance(context)
            db.projectDao().projects().first().filter{it.id !in originals}.forEach{
                db.projectDao().deleteProject(it.id)
                db.projectDao().deleteUnreferencedRoom(it.roomId)
            }
            testItemId?.let{db.itemDao().deleteById(it)}
            extraTestItemIds.forEach{db.itemDao().deleteById(it)}
        }
        context.getSharedPreferences("active_design",0).edit().apply{
            if(originalActiveProject==null)remove("id") else putString("id",originalActiveProject)
        }.commit()
        testFile?.delete()
        originalDesignImages?.let{originalsImages->
            File(context.filesDir,"design-images").listFiles().orEmpty().filter{
                it.name !in originalsImages && it.name.matches(Regex("after-[0-9]+\\.png"))
            }.forEach{it.delete()}
        }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        exportedNames.forEach{name->
            check(name.matches(Regex("qa-export-[a-f0-9-]+-(3d|plan)\\.png")))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("rm /sdcard/Download/$name")).use{it.readBytes()}
        }
    }

    /** Safe device probe: no test item is inserted into the user's inventory. */
    @Test fun glbDialogRendersWithoutChangingInventory(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"renderer-probe-chair.glb")
        val mesh=PlacedFurniture(1,"QA","CHAIR",0f,0f,0f,0f,68f,100f,70f,Color.Cyan).getTransformedMesh()
        val buffer=GlbWriter.encode(mesh)
        val bytes=ByteArray(buffer.remaining());buffer.get(bytes);file.writeBytes(bytes)
        val item=ItemEntity(name="Ghế kiểm thử GLB",category="FURNITURE",roomType="OFFICE",widthCm=68f,heightCm=100f,depthCm=70f,colorHex="#00FFFF",material="QA fixture",
            modelPath=file.absolutePath,modelSource="TEST_FIXTURE",scaleConfirmed=true,polygonCount=mesh.faces.sumOf{it.vertices.size-2})
        compose.runOnUiThread{compose.activity.setContent{MyApplicationTheme{GlbItemDialog(item,{},{},{})}}}
        compose.waitUntil(60000){compose.onAllNodesWithTag("glb_ready").fetchSemanticsNodes().isNotEmpty()}
        nativeScreenshot("glb-dialog-native.png")
        screenshot("glb-viewer.png")
    }

    @Test fun nativeRendererCanDrawGeometry(){
        compose.runOnUiThread {
            compose.activity.setContent {
                val engine=rememberEngine()
                val materials=rememberMaterialLoader(engine)
                val camera=rememberCameraNode(engine){position=Position(0f,0f,3f)}
                val nodes=rememberNodes{add(CubeNode(engine,materialInstance=materials.createColorInstance(Color.Cyan)))}
                val renderView=rememberSpaceFitView(engine)
                Scene(Modifier.fillMaxSize(),engine=engine,view=renderView,materialLoader=materials,cameraNode=camera,childNodes=nodes.toList())
            }
        }
        compose.waitForIdle()
        nativeScreenshot("renderer-cube.png")
    }

    /** Exercise several assets without touching saved projects or inventory. */
    @Test fun roomRendersMultipleModelsWithoutChangingProjects(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"room-probe-chair.glb")
        val mesh=PlacedFurniture(1,"QA","CHAIR",0f,0f,0f,0f,68f,100f,70f,Color.Cyan).getTransformedMesh()
        val buffer=GlbWriter.encode(mesh)
        val bytes=ByteArray(buffer.remaining());buffer.get(bytes);file.writeBytes(bytes)
        val item=ItemEntity(name="QA",category="FURNITURE",roomType="OFFICE",widthCm=68f,heightCm=100f,depthCm=70f,
            colorHex="#00FFFF",material="QA fixture",modelPath=file.absolutePath,modelType3D="CHAIR",scaleConfirmed=true)
        val placements=listOf(FurniturePlacement(item=item,xCm=-70f),FurniturePlacement(item=item.copy(modelPath=""),xCm=70f))
        compose.runOnUiThread{compose.activity.setContent{MyApplicationTheme{RoomScene(RoomEntity(),placements,{},Modifier.fillMaxSize())}}}
        compose.waitUntil(60000){compose.onAllNodesWithTag("room_ready").fetchSemanticsNodes().isNotEmpty()}
        nativeScreenshot("room-probe-native.png",requireBothSides=true)
        screenshot("room-probe-ui.png")
    }

    private fun prepareInventoryFixture(){
        assumeTrue("State-changing test runs only on the QA emulator",android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.waitUntil(30000){compose.onAllNodesWithContentDescription("Dự án thiết kế").fetchSemanticsNodes().isNotEmpty()}
        originalProjectIds=runBlocking{AppDatabase.getInstance(context).projectDao().projects().first().map{it.id}.toSet()}
        originalActiveProject=context.getSharedPreferences("active_design",0).getString("id",null)
        originalDesignImages=File(context.filesDir,"design-images").listFiles().orEmpty().map{it.name}.toSet()
        compose.onNodeWithContentDescription("Dự án thiết kế").performClick()
        compose.onNodeWithText("Tạo dự án mới").performClick()
        compose.waitUntil(15000){runBlocking{AppDatabase.getInstance(context).projectDao().projects().first().any{it.id !in originalProjectIds!!}}}
        val file=File.createTempFile("qa-chair-",".glb",context.filesDir).also{testFile=it}
        val mesh=PlacedFurniture(1,"QA","CHAIR",0f,0f,0f,0f,68f,100f,70f,Color.Cyan).getTransformedMesh()
        val buffer=GlbWriter.encode(mesh)
        val bytes=ByteArray(buffer.remaining());buffer.get(bytes);file.writeBytes(bytes)
        testItemId=runBlocking{AppDatabase.getInstance(context).itemDao().insertItem(ItemEntity(name="Ghế kiểm thử GLB hiện tại",category="FURNITURE",roomType="OFFICE",
            widthCm=68f,heightCm=100f,depthCm=70f,colorHex="#FFFFFF",material="QA fixture",modelPath=file.absolutePath,
            modelSource="TEST_FIXTURE",polygonCount=mesh.faces.sumOf{it.vertices.size-2},scaleConfirmed=true,modelType3D="CHAIR"))}
    }

    @Test fun localGlbCanOpenInInventoryAndBePlacedInRoom(){
        prepareInventoryFixture()
        compose.waitUntil(30000){compose.onAllNodesWithTag("workspace_inventory").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("workspace_inventory").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Ghế kiểm thử GLB hiện tại").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("Ghế kiểm thử GLB hiện tại").onFirst().performClick()
        compose.onNodeWithTag("glb_item_dialog").assertIsDisplayed()
        compose.waitUntil(60000){compose.onAllNodesWithTag("glb_ready").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
        nativeScreenshot("glb-native.png")
        screenshot("glb-viewer.png")
        compose.onNode(hasText("Đặt vào phòng") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithTag("workspace_room").assertIsDisplayed()
        compose.onNodeWithText("3D",useUnmergedTree=true).performClick()
        compose.waitUntil(60000){compose.onAllNodesWithTag("room_ready").fetchSemanticsNodes().isNotEmpty()}
        nativeScreenshot("room-native.png")
        screenshot("room-3d.png",compose.onNodeWithTag("room_ready").fetchSemanticsNode().boundsInWindow)
        compose.onNodeWithText("Mặt bằng").performClick()
        compose.waitForIdle()
        screenshot("floor-plan.png")
        compose.onNodeWithTag("workspace_scan").performClick()
        compose.onNodeWithText("Bắt đầu phiên quét").assertIsDisplayed()
        screenshot("scanner-home.png")
    }

    /** Fixture only seeds inventory; every layout change and success check goes through the UI. */
    @Test fun roomEditingLocksSuggestionsAndRecreationPreserveTheDesign(){
        prepareInventoryFixture()
        compose.onNodeWithText("Thêm đồ").performClick()
        compose.onNodeWithText("Ghế kiểm thử GLB hiện tại",substring=true).performClick()
        compose.onNodeWithText("Nhân bản").performScrollTo().performClick()
        compose.onNodeWithText("2 đồ vật",substring=true).assertExists()
        compose.onNodeWithText("Phát hiện va chạm với một vật thể khác.").assertExists()
        compose.onNodeWithText("Bỏ khỏi phòng").performScrollTo().performClick()
        compose.onNodeWithText("1 đồ vật",substring=true).assertExists()
        compose.onNodeWithText("Mặt bằng").performClick()
        compose.onNodeWithTag("floor_plan").performTouchInput{click(center)}
        compose.onNode(isToggleable()).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("Tạo phương án • giữ nguyên đồ đã khóa").performScrollTo().performClick()
        compose.waitUntil(30000){compose.onAllNodesWithText("Áp dụng phương án 1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Áp dụng phương án 1").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().assertIsOn()

        val name="QA lưu qua vòng đời ${System.currentTimeMillis()}"
        compose.onNodeWithContentDescription("Dự án thiết kế").performClick()
        compose.onNodeWithText("Tên thiết kế hiện tại").performTextReplacement(name)
        compose.onNodeWithText("Đổi tên").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Lưu thiết kế").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Đã lưu thiết kế.").fetchSemanticsNodes().isNotEmpty()}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30000){compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("1 đồ vật",substring=true).assertExists()
        compose.onNodeWithTag("floor_plan").performTouchInput{click(center)}
        compose.onNode(isToggleable()).performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("X: 0 cm • Z: 0 cm • Xoay: 0°").assertExists()
        screenshot("room-persistence.png")
        compose.onNodeWithText("Bỏ khỏi phòng").performScrollTo().performClick()
        compose.onNodeWithText("0 đồ vật",substring=true).assertExists()
    }

    /** Inventory is seeded; region selection, filtering, placement and reopen use real UI only. */
    @Test fun regionChooserFindsOffCentreSpaceRejectsBadItemsAndPersistsPlacement(){
        prepareInventoryFixture()
        val db=AppDatabase.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val fixture=runBlocking{requireNotNull(db.itemDao().getItemById(requireNotNull(testItemId)))}
        val tooLarge=runBlocking{db.itemDao().insertItem(fixture.copy(id=0,name="QA quá lớn",widthCm=1000f,depthCm=1000f))}.also{extraTestItemIds.add(it)}
        val unscaled=runBlocking{db.itemDao().insertItem(fixture.copy(id=0,name="QA chưa xác nhận tỷ lệ",scaleConfirmed=false))}.also{extraTestItemIds.add(it)}
        compose.onNodeWithText("Thêm đồ").performClick()
        compose.onNodeWithText("Ghế kiểm thử GLB hiện tại",substring=true).performClick()
        compose.onNodeWithText("Mặt bằng").performClick()
        compose.onNodeWithTag("floor_plan").performTouchInput{click(androidx.compose.ui.geometry.Offset(30f,30f))}

        fun selectRegion(widthCm:Float,depthCm:Float){
            compose.onNodeWithText("Đồ nào vừa đây?").performScrollTo().performClick()
            compose.onNodeWithTag("floor_plan").performTouchInput{
                val scale=minOf((width-48f)/400f,(height-48f)/500f)
                val half=androidx.compose.ui.geometry.Offset(widthCm*scale/2,depthCm*scale/2)
                swipe(center-half,center+half,1000)
            }
            compose.waitUntil(15000){compose.onAllNodesWithTag("region_fit_summary").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("region_fit_dialog").assertIsDisplayed()
        }
        selectRegion(320f,240f) // The middle already contains the first 68 x 70 cm chair.
        compose.onNodeWithTag("region_fit_item_$tooLarge").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("region_fit_item_$unscaled").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cần xác nhận tỷ lệ GLB trước khi đặt vào phòng").assertExists()
        compose.onNodeWithTag("region_fit_item_$testItemId").performScrollTo().assertIsEnabled()
        regionDialogScreenshot("region-fit-choices.png")
        compose.onNodeWithTag("region_fit_item_$testItemId").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("2 đồ vật",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Đã thêm đồ vào vùng đã chọn.").assertExists()
        compose.onAllNodesWithText("Phát hiện va chạm với một vật thể khác.").assertCountEquals(0)
        val positionText=compose.onNodeWithText("X: ",substring=true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString(""){it.text}
        val coordinates=Regex("X: (-?\\d+) cm • Z: (-?\\d+) cm").find(positionText) ?: error("Missing placement coordinates")
        val x=coordinates.groupValues[1].toFloat();val z=coordinates.groupValues[2].toFloat()
        check(x!=0f || z!=0f){"New chair must not overlap the centre chair"}
        compose.onNodeWithContentDescription("Lưu thiết kế").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Đã lưu thiết kế.").fetchSemanticsNodes().isNotEmpty()}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30000){compose.onAllNodesWithText("2 đồ vật",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("floor_plan").performTouchInput{
            val scale=minOf((width-48f)/400f,(height-48f)/500f)
            click(center+androidx.compose.ui.geometry.Offset(x*scale,z*scale))
        }
        compose.onNodeWithText(positionText).assertExists()
        compose.onAllNodesWithText("Phát hiện va chạm với một vật thể khác.").assertCountEquals(0)
        screenshot("region-fit-reopened.png",compose.onNodeWithTag("floor_plan").fetchSemanticsNode().boundsInWindow)
        compose.onNodeWithTag("floor_plan").performTouchInput{click(androidx.compose.ui.geometry.Offset(30f,30f))}
        selectRegion(40f,40f)
        compose.onNodeWithTag("region_fit_item_$testItemId").performScrollTo().assertIsNotEnabled()
        regionDialogScreenshot("region-fit-no-space.png")
        compose.onNodeWithText("Đóng").performClick()
        compose.onNodeWithText("2 đồ vật",substring=true).assertExists()
    }

    /** Real SAF chooser and saved-file pixels, not a fake ActivityResult callback. QA emulator only. */
    @Test fun exportPngThroughSystemChooserContainsGeometryInBothViews(){
        prepareInventoryFixture()
        compose.onNodeWithText("Thêm đồ").performClick()
        compose.onNodeWithText("Ghế kiểm thử GLB hiện tại",substring=true).performClick()
        compose.waitUntil(60000){compose.onAllNodesWithTag("room_ready").fetchSemanticsNodes().isNotEmpty()}
        nativeScreenshot("export-source-native.png")
        compose.onNodeWithText("Before / After").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Xuất PNG").performClick()
        waitForSystemNode{it.className=="android.widget.EditText"}
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitForSystemNode{it.packageName?.toString()=="com.spacefit.app" && it.text?.toString()=="AR-SpaceFit"}
        compose.onNodeWithText("Before / After").assertIsNotEnabled()
        listOf("3d","plan").forEach{view->
            compose.waitUntil(15000){
                !compose.onNodeWithText("Xuất PNG").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            if(view=="plan")compose.onNodeWithText("Mặt bằng").performClick()
            val filename="qa-export-${java.util.UUID.randomUUID()}-$view.png"
            exportedNames.add(filename)
            compose.onNodeWithText("Xuất PNG").performScrollTo().performClick()
            val arguments=android.os.Bundle().apply{
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename)
            }
            var named=false
            val nameDeadline=android.os.SystemClock.uptimeMillis()+15000
            while(!named && android.os.SystemClock.uptimeMillis()<nameDeadline){
                // Resolve a fresh active node; the cancelled chooser's accessibility tree
                // can briefly outlive its window when the next chooser is launched.
                val edit=waitForSystemNode{it.packageName?.toString()?.contains("documentsui")==true &&
                    it.className=="android.widget.EditText" && it.isEditable && it.isEnabled && it.isVisibleToUser}
                named=edit.refresh() && edit.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,arguments)
                if(!named)android.os.SystemClock.sleep(100)
            }
            check(named){"Cannot name SAF document"}
            waitForSystemNode{it.className=="android.widget.EditText" && it.text?.toString()==filename}
            screenshot("export-chooser-$view.png")
            val save=waitForSystemNode{it.text?.toString()?.equals("Save",ignoreCase=true)==true && it.isEnabled}
            check(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){"Cannot save SAF document"}
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            var png=byteArrayOf()
            val deadline=android.os.SystemClock.uptimeMillis()+15000
            while(android.os.SystemClock.uptimeMillis()<deadline){
                png=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /sdcard/Download/$filename")).use{it.readBytes()}
                if(png.size>12 && png.copyOfRange(png.size-8,png.size-4).contentEquals("IEND".toByteArray(Charsets.US_ASCII)))break
                android.os.SystemClock.sleep(100)
            }
            if(png.size<=12 || !png.copyOfRange(png.size-8,png.size-4).contentEquals("IEND".toByteArray(Charsets.US_ASCII))){
                screenshot("export-failure-ui.png")
                val errorText=compose.onRoot().printToString().lineSequence().filter{it.contains("Chưa xuất")}.joinToString()
                error("Saved PNG is absent/incomplete: $filename; $errorText")
            }
            val bitmap=android.graphics.BitmapFactory.decodeByteArray(png,0,png.size) ?: error("Saved PNG cannot be decoded")
            check(visibleFixturePixels(bitmap,0,bitmap.width,0,bitmap.height)>20){"Saved $view PNG has no cyan geometry"}
            bitmap.recycle()
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("qa"),"export-$view.png").writeBytes(png)
            compose.onNodeWithText("Before / After").assertIsEnabled()
        }
        compose.onNodeWithText("Before / After").performClick()
        compose.onNodeWithText("Thiết kế đã xuất gần nhất").assertIsDisplayed()
        compose.onNodeWithText("Đóng").performClick()
    }

    private fun waitForSystemNode(predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo{
        fun find(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo?{
            if(node==null)return null
            if(predicate(node))return node
            for(index in 0 until node.childCount)find(node.getChild(index))?.let{return it}
            return null
        }
        val deadline=android.os.SystemClock.uptimeMillis()+15000
        while(android.os.SystemClock.uptimeMillis()<deadline){
            find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)?.let{return it}
            android.os.SystemClock.sleep(100)
        }
        screenshot("system-chooser-failure.png")
        val errorText=compose.onRoot().printToString().lineSequence().filter{it.contains("Chưa xuất")}.joinToString()
        error("Expected system document chooser control did not appear; $errorText")
    }

    /** Dialog window animation can lag Compose semantics. Verify real opaque pixels, not just nodes. */
    private fun regionDialogScreenshot(name:String){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bounds=compose.onNodeWithTag("region_fit_dialog").fetchSemanticsNode().boundsInWindow
        val screen=IntArray(2);val window=IntArray(2)
        instrumentation.runOnMainSync{
            val decor=android.view.inspector.WindowInspector.getGlobalWindowViews().last{
                it.isShown && it!==compose.activity.window.decorView
            }
            decor.getLocationOnScreen(screen);decor.getLocationInWindow(window)
        }
        // Semantics bounds are local to the Dialog window, unlike UiAutomation's full-screen bitmap.
        val offsetX=screen[0]-window[0];val offsetY=screen[1]-window[1]
        val deadline=android.os.SystemClock.uptimeMillis()+10000
        var opaque=false
        var bitmap:Bitmap?=null
        val sampled=mutableListOf<Int>()
        try {
            do {
                compose.waitForIdle()
                bitmap?.recycle()
                val captured=instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
                bitmap=captured;sampled.clear()
                // Interior side padding contains no text; the dark theme's surfaceContainerHigh is #172236.
                opaque=listOf(bounds.left+12f,bounds.right-12f).all{x->
                    listOf(0.25f,0.5f,0.75f).all{fraction->
                        val pixel=captured.getPixel(x.toInt()+offsetX,(bounds.top+bounds.height*fraction).toInt()+offsetY)
                        sampled.add(pixel)
                        kotlin.math.abs(android.graphics.Color.red(pixel)-23)<=1 &&
                            kotlin.math.abs(android.graphics.Color.green(pixel)-34)<=1 &&
                            kotlin.math.abs(android.graphics.Color.blue(pixel)-54)<=1
                    }
                }
                if(opaque)break
                android.os.SystemClock.sleep(100)
            }while(android.os.SystemClock.uptimeMillis()<deadline)
        }finally{
            // Always replace evidence for this attempt, including deadline exit after the final sleep.
            bitmap?.let{captured->
                try{File(instrumentation.targetContext.getExternalFilesDir("qa"),name).outputStream().use{captured.compress(Bitmap.CompressFormat.PNG,100,it)}}
                finally{captured.recycle()}
            }
        }
        check(opaque){"Region chooser is not fully drawn/opaque: $name; bounds=$bounds offset=$offsetX,$offsetY pixels=$sampled"}
    }

    /** Pure UI inputs, never creates a scan or calls the real backend. */
    @Test fun scannerRequiresNameAndValidReferenceMeasurement(){
        compose.waitUntil(30000){compose.onAllNodesWithTag("workspace_scan").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("workspace_scan").performClick()
        compose.onNodeWithText("Bắt đầu phiên quét").performClick()
        compose.onNodeWithText("Tạo phiên").assertIsNotEnabled()
        compose.onNodeWithText("Tên món đồ").performTextInput("Ghế kiểm thử")
        compose.onNodeWithText("Số đo thực bằng thước (cm)").performTextInput("0")
        compose.onNodeWithText("Tạo phiên").assertIsNotEnabled()
        compose.onNodeWithText("Số đo thực bằng thước (cm)").performTextReplacement("15,5")
        compose.onNodeWithText("Tạo phiên").assertIsEnabled()
        compose.onNodeWithText("Tên món đồ").performTextReplacement("x".repeat(121))
        compose.onNodeWithText("Tạo phiên").assertIsNotEnabled()
        compose.onNodeWithText("Hủy").performClick()
        compose.onNodeWithText("Bắt đầu phiên quét").assertIsDisplayed()
    }

    @Test fun roomSettingsRejectInvalidDimensionsWithoutChangingProject(){
        compose.waitUntil(30000){compose.onAllNodesWithContentDescription("Kích thước và cửa").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Kích thước và cửa").performClick()
        compose.onNodeWithText("Rộng (cm)").performTextReplacement("-1")
        compose.onNodeWithText("Lưu phòng").assertIsNotEnabled()
        compose.onNodeWithText("Rộng (cm)").performTextReplacement("450,5")
        compose.onNodeWithText("Lưu phòng").assertIsEnabled()
        compose.onNodeWithText("Cao (cm)").performTextReplacement("NaN")
        compose.onNodeWithText("Lưu phòng").assertIsNotEnabled()
        compose.onNodeWithText("Hủy").performClick()
    }

    private fun nativeScreenshot(name:String,requireBothSides:Boolean=false){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun scenes(view:android.view.View):List<SceneView> = when(view){
            is SceneView->listOf(view)
            is android.view.ViewGroup->(0 until view.childCount).flatMap{scenes(view.getChildAt(it))}
            else->emptyList()
        }
        var surface:SceneView?=null
        compose.waitUntil(15000){
            instrumentation.runOnMainSync {
                surface=android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .flatMap{scenes(it)}.lastOrNull{it.isShown && it.width>0 && it.height>0 && it.holder.surface.isValid}
            }
            surface!=null
        }
        var bitmap:Bitmap?=null
        var lastFailure:Exception?=null
        fun visible(bitmap:Bitmap)=if(requireBothSides){
            // A visible parametric chair must not hide a broken GLB-file chair.
            visibleFixturePixels(bitmap,0,bitmap.width/2,bitmap.height/4,bitmap.height*3/4)>20 &&
                visibleFixturePixels(bitmap,bitmap.width/2,bitmap.width,bitmap.height/4,bitmap.height*3/4)>20
        }else visibleFixturePixels(bitmap)>20
        val deadline=android.os.SystemClock.uptimeMillis()+30000
        while(android.os.SystemClock.uptimeMillis()<deadline){
            // File I/O resumes on the real main dispatcher; advance Compose's
            // test clock so the newly loaded nodes reach AndroidView.update.
            compose.waitForIdle()
            bitmap?.recycle()
            bitmap=try{runBlocking{captureSurface(requireNotNull(surface))}}
                catch(failure:Exception){lastFailure=failure;null}
            if(bitmap!=null && visible(bitmap))break
            android.os.SystemClock.sleep(200)
        }
        val captured=bitmap ?: throw IllegalStateException("No native frame for $name",lastFailure)
        File(instrumentation.targetContext.getExternalFilesDir("qa"),name).outputStream().use{captured.compress(Bitmap.CompressFormat.PNG,100,it)}
        val hasFixture=visible(captured)
        captured.recycle()
        check(hasFixture){"Native scene/export has no visible fixture: $name"}
    }

    private fun screenshot(name:String,fixtureBounds:androidx.compose.ui.geometry.Rect?=null){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        fun visible(bitmap:Bitmap):Int=fixtureBounds?.let{bounds->
            visibleFixturePixels(bitmap,bounds.left.toInt().coerceIn(0,bitmap.width),bounds.right.toInt().coerceIn(0,bitmap.width),
                bounds.top.toInt().coerceIn(0,bitmap.height),bounds.bottom.toInt().coerceIn(0,bitmap.height))
        } ?: visibleFixturePixels(bitmap)
        if(name=="glb-viewer.png" || fixtureBounds!=null){
            // SurfaceView/GPU presentation can trail Compose's idle/frame callbacks.
            // Poll the actual pixels, not a fixed sleep or only a loaded-state label.
            val deadline=android.os.SystemClock.uptimeMillis()+10000
            while(visible(bitmap)<=20 && android.os.SystemClock.uptimeMillis()<deadline){
                bitmap.recycle()
                android.os.SystemClock.sleep(200)
                bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
            }
        }
        val output=File(instrumentation.targetContext.getExternalFilesDir("qa"),name)
        output.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name=="glb-viewer.png" || fixtureBounds!=null){
            // Loading a native asset is insufficient: a black viewport must fail.
            // This test's fixture is cyan; sample the centre of its viewport,
            // excluding labels and controls above/below the model.
            check(visible(bitmap)>20){"Viewport is blank: no visible cyan fixture geometry in $name"}
        }
        bitmap.recycle()
    }

    private fun visibleFixturePixels(bitmap:Bitmap,left:Int=bitmap.width/4,right:Int=bitmap.width*3/4,top:Int=bitmap.height/4,bottom:Int=bitmap.height/2):Int{
        var count=0
        for(y in top until bottom step 3){
            for(x in left until right step 3){
                val pixel=bitmap.getPixel(x,y)
                if(isCyanFixturePixel(pixel))count++
            }
        }
        return count
    }
}
