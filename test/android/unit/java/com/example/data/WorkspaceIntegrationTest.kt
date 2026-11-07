package com.example.data

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.spacefit.FreeRegion
import com.example.spacefit.LayoutPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class WorkspaceIntegrationTest {
    private val item=ItemEntity(name="Fixture",category="FURNITURE",roomType="OFFICE",widthCm=50f,heightCm=80f,depthCm=50f,colorHex="#FFFFFF",material="test")
    private suspend fun TestScope.withWorkspace(block:suspend TestScope.(WorkspaceViewModel)->Unit){
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=ApplicationProvider.getApplicationContext<Application>()
        val db=Room.inMemoryDatabaseBuilder(app,AppDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor{it.run()}.setTransactionExecutor{it.run()}.build()
        val holder=ViewModelStore()
        try {
            val vm=WorkspaceViewModel(app,db);holder.put("fixture",vm)
            advanceUntilIdle()
            assertNotNull(vm.draft.value)
            block(vm)
        } finally {holder.clear();db.close();Dispatchers.resetMain()}
    }

    @Test fun autosaveAndProjectSwitchPreserveIndependentLayouts()=runTest {withWorkspace{vm->
        val original=requireNotNull(vm.draft.value).id
        vm.add(item)
        vm.update(requireNotNull(vm.draft.value).placements.single().copy(xCm=123f,rotation=45f,locked=true))
        vm.newProject().join()
        val second=requireNotNull(vm.draft.value).id
        assertNotEquals(original,second)
        assertTrue(requireNotNull(vm.draft.value).placements.isEmpty())
        val first=vm.db.projectDao().projects().first().first{it.id==original}
        vm.openProject(first).join()
        val restored=requireNotNull(vm.draft.value).placements.single()
        assertEquals(123f,restored.xCm,0f);assertEquals(45f,restored.rotation,0f);assertTrue(restored.locked)
        assertEquals(2,vm.db.projectDao().projects().first().size)
    }}

    @Test fun selectingCurrentProjectDoesNotRevertUnsavedEdits()=runTest {withWorkspace{vm->
        val stale=vm.db.projectDao().projects().first().single()
        vm.edit{it.copy(name="Tên mới chưa debounce")}
        vm.openProject(stale).join()
        assertEquals("Tên mới chưa debounce",vm.draft.value?.name)
        vm.saveNow().join()
        assertEquals("Tên mới chưa debounce",vm.db.projectDao().projects().first().single().name)
    }}

    @Test fun duplicateDeleteAndInvalidEditsDoNotCorruptTheDraft()=runTest {withWorkspace{vm->
        vm.add(item)
        val first=vm.draft.value!!.placements.single()
        vm.duplicate(first.copy(locked=true))
        val duplicate=vm.draft.value!!.placements.last()
        assertNotEquals(first.id,duplicate.id);assertFalse(duplicate.locked)
        vm.remove(duplicate.id)
        val before=vm.draft.value
        val selection=vm.selectedId
        vm.add(item.copy(modelPath="unscaled.glb",scaleConfirmed=false))
        assertEquals(before,vm.draft.value)
        assertEquals(selection,vm.selectedId)
        assertTrue(vm.message.value.contains("tỷ lệ"))
        vm.edit{it.copy(placements=(1L..101L).map{id->first.copy(id=id)})}
        assertEquals(before,vm.draft.value)
        vm.saveNow().join()
        assertEquals(listOf(first),DesignCodec.readPlacements(vm.db.projectDao().projects().first().single().placementsJson))
    }}

    @Test fun regionFitAddsOffCentreWithoutMovingExistingObjectsAndSurvivesReopen()=runTest {withWorkspace{vm->
        val stored=item.copy(id=vm.db.itemDao().insertItem(item))
        vm.add(item.copy(widthCm=80f,depthCm=100f))
        val before=vm.draft.value!!
        val region=FreeRegion(0f,0f,240f,100f)
        val result=RegionFitSearch.find(before,region,listOf(stored))
        val option=result.options.single()
        assertNotNull(option.placement)
        vm.addRegionFit(result,option).join()
        val added=vm.draft.value!!.placements.last()
        assertEquals(before.placements.single(),vm.draft.value!!.placements.first())
        assertEquals(added.id,vm.selectedId)
        assertTrue(region.contains(added.footprint()))
        assertTrue(LayoutPlanner.valid(before.room.spec(),added.footprint(),before.placements.map{it.footprint()}))
        vm.saveNow().join()
        vm.newProject().join()
        vm.openProject(vm.db.projectDao().projects().first().first{it.id==before.id}).join()
        assertEquals(before.placements+added,vm.draft.value!!.placements)
    }}

    @Test fun regionSearchHandlesEmptyInvalidUnscaledAndOversizedInventoryRecords()=runTest {withWorkspace{vm->
        val source=vm.draft.value!!;val region=FreeRegion(0f,0f,100f,100f)
        assertTrue(RegionFitSearch.find(source,region,emptyList()).options.isEmpty())
        val options=RegionFitSearch.find(source,region,listOf(item,item.copy(widthCm=Float.NaN),
            item.copy(heightCm=0f),item.copy(modelPath="preview.glb",scaleConfirmed=false),item.copy(widthCm=200f,depthCm=200f))).options
        assertNotNull(options.first().placement)
        assertTrue(options.drop(1).all{it.placement==null && it.reason.isNotBlank()})
        assertTrue(options[3].reason.contains("tỷ lệ"))
    }}

    @Test fun regionResultCannotOverwriteChangedLayoutOrBeAppliedTwice()=runTest {withWorkspace{vm->
        val stored=item.copy(id=vm.db.itemDao().insertItem(item))
        val result=RegionFitSearch.find(vm.draft.value!!,FreeRegion(0f,0f,100f,100f),listOf(stored))
        vm.addRegionFit(result,result.options.single()).join()
        val once=vm.draft.value
        vm.addRegionFit(result,result.options.single()).join()
        assertEquals(once,vm.draft.value);assertTrue(vm.message.value.contains("Thiết kế đã đổi"))
        val next=RegionFitSearch.find(vm.draft.value!!,FreeRegion(120f,0f,100f,100f),listOf(stored))
        vm.updateRoom(vm.draft.value!!.room.copy(widthCm=80f))
        val changed=vm.draft.value
        vm.addRegionFit(next,next.options.single()).join()
        assertEquals(changed,vm.draft.value);assertTrue(vm.message.value.contains("Thiết kế đã đổi"))
    }}

    @Test fun regionResultBelongsToItsOriginalProject()=runTest {withWorkspace{vm->
        val stored=item.copy(id=vm.db.itemDao().insertItem(item))
        val result=RegionFitSearch.find(vm.draft.value!!,FreeRegion(0f,0f,100f,100f),listOf(stored))
        vm.newProject().join()
        val second=vm.draft.value
        vm.addRegionFit(result,result.options.single()).join()
        assertEquals(second,vm.draft.value);assertNull(vm.selectedId)
    }}

    @Test fun regionResultRejectsUpdatedOrDeletedInventory()=runTest {withWorkspace{vm->
        val stored=item.copy(id=vm.db.itemDao().insertItem(item))
        val before=vm.draft.value
        val result=RegionFitSearch.find(before!!,FreeRegion(0f,0f,100f,100f),listOf(stored))
        vm.db.itemDao().updateItem(stored.copy(widthCm=200f))
        vm.addRegionFit(result,result.options.single()).join()
        assertEquals(before,vm.draft.value);assertTrue(vm.message.value.contains("trong kho đã đổi"))
        vm.db.itemDao().deleteById(stored.id)
        vm.addRegionFit(result,result.options.single()).join()
        assertEquals(before,vm.draft.value);assertNull(vm.selectedId)
    }}

    @Test fun regionApplyRevalidatesCoordinatesScaleAndMembership()=runTest {withWorkspace{vm->
        val stored=item.copy(id=vm.db.itemDao().insertItem(item))
        val before=vm.draft.value!!
        val result=RegionFitSearch.find(before,FreeRegion(0f,0f,100f,100f),listOf(stored))
        val invalid=result.options.single().copy(placement=result.options.single().placement!!.copy(xCm=300f))
        vm.addRegionFit(result,invalid).join()
        assertEquals(before,vm.draft.value)
        vm.addRegionFit(result.copy(options=listOf(invalid)),invalid).join()
        assertEquals(before,vm.draft.value);assertTrue(vm.message.value.contains("Vị trí"))
        val unscaled=stored.copy(modelPath="preview.glb",scaleConfirmed=false)
        vm.db.itemDao().updateItem(unscaled)
        val unsafe=RegionFitOption(unscaled,FurniturePlacement(item=unscaled))
        vm.addRegionFit(result.copy(options=listOf(unsafe)),unsafe).join()
        assertEquals(before,vm.draft.value);assertTrue(vm.message.value.contains("tỷ lệ"))
    }}

    @Test fun fullProjectDisablesAllRegionOptions()=runTest {withWorkspace{vm->
        vm.edit{it.copy(placements=(1L..100L).map{id->FurniturePlacement(id=id,item=item)})}
        val result=RegionFitSearch.find(vm.draft.value!!,FreeRegion(150f,0f,100f,100f),listOf(item))
        assertNull(result.options.single().placement)
        assertTrue(result.options.single().reason.contains("100"))
    }}
}
