package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.spacefit.RoomOpening
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DesignPersistenceTest {
    private val item=ItemEntity(id=12,name="Ghế thật",category="FURNITURE",roomType="OFFICE",widthCm=68f,heightCm=120f,depthCm=72f,
        colorHex="#FFFFFF",material="texture",modelPath="/files/chair.glb",modelSource="PHOTOGRAMMETRY",polygonCount=50000,scanSessionId="scan-1",scaleConfirmed=true)

    @Test fun roomAndPlacementsRetainGlbScaleAndLocks(){
        val room=RoomEntity(openingsJson=openingsJson(listOf(RoomOpening("1","DOOR","NORTH",50f,90f))))
        val placements=listOf(FurniturePlacement(1,item,10f,-20f,75f,true),FurniturePlacement(2,item,150f,100f,0f,false))
        assertEquals(placements,DesignCodec.readPlacements(DesignCodec.placements(placements)))
        assertEquals(room.spec(),DesignCodec.readRoom(DesignCodec.room(room)).spec())
    }
    @Test fun scansAndProjectsSurviveDatabaseReopen()=runTest{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val path="design-roundtrip.db"
        val room=RoomEntity()
        val scan=ScanSessionEntity(id="scan-1",status="processing",jobId="remote-job",progress=38)
        val project=DesignProjectEntity(roomId=room.id,name="Phòng làm việc",roomJson=DesignCodec.room(room),placementsJson=DesignCodec.placements(listOf(FurniturePlacement(item=item))))
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,path).allowMainThreadQueries().build()
        try{
            val first=open()
            try{first.itemDao().insertItem(item);first.projectDao().saveScan(scan);first.projectDao().saveRoom(room);first.projectDao().saveProject(project)}finally{first.close()}
            val second=open()
            try{
                assertEquals(scan,second.projectDao().scan("scan-1"))
                assertEquals(item,second.itemDao().getItemById(12))
                assertEquals(room,second.projectDao().rooms().first().single())
                assertEquals(project,second.projectDao().projects().first().single())
                second.projectDao().saveScan(scan.copy(status="completed",modelPath="/files/final.glb"))
                assertEquals("completed",second.projectDao().scan("scan-1")?.status)
                second.projectDao().deleteProject(project.id)
                assertTrue(second.projectDao().projects().first().isEmpty())
                assertEquals(item,second.itemDao().getItemById(12))
                assertNotNull(second.projectDao().scan("scan-1"))
            }finally{second.close()}
        }finally{context.deleteDatabase(path)}
    }
}
