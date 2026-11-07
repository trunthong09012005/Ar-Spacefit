package com.example.data

import android.app.Application
import androidx.compose.runtime.*
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.spacefit.LayoutPlanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class DesignDraft(val id:String,val name:String,val room:RoomEntity,val placements:List<FurniturePlacement>,
    val beforePath:String="",val afterPath:String="") {
    init { DesignCodec.validatePlacements(placements) }
}

@OptIn(FlowPreview::class)
class WorkspaceViewModel(application:Application,val db:AppDatabase):AndroidViewModel(application){
    constructor(application:Application):this(application,AppDatabase.getInstance(application))
    private val dao=db.projectDao()
    private val prefs=application.getSharedPreferences("active_design",0)
    val items=db.itemDao().getAllItems().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val projects=dao.projects().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val draft=MutableStateFlow<DesignDraft?>(null)
    val message=MutableStateFlow("")
    val planning=MutableStateFlow(false)
    val suggestions=MutableStateFlow<List<List<FurniturePlacement>>>(emptyList())
    var selectedId by mutableStateOf<Long?>(null)

    init{
        viewModelScope.launch{
            try{
                val existing=dao.projects().first()
                val selected=existing.firstOrNull{it.id==prefs.getString("id",null)} ?: existing.firstOrNull()
                if(selected==null)newProject() else openProject(selected)
            }catch(error:Exception){message.value="Không mở được project: ${error.localizedMessage}. Dữ liệu cũ vẫn được giữ."}
        }
        viewModelScope.launch{
            draft.filterNotNull().debounce(500).collect{snapshot->
                try{save(snapshot)}catch(error:Exception){message.value="Chưa lưu được project: ${error.localizedMessage}"}
            }
        }
    }
    private suspend fun save(value:DesignDraft){
        db.withTransaction{
            dao.saveRoom(value.room)
            dao.saveProject(DesignProjectEntity(value.id,value.room.id,value.name,DesignCodec.room(value.room),
                DesignCodec.placements(value.placements),value.beforePath,value.afterPath))
        }
    }
    fun saveNow()=viewModelScope.launch{try{draft.value?.let{save(it)};message.value="Đã lưu thiết kế."}catch(error:Exception){message.value=error.localizedMessage.orEmpty()}}
    fun newProject()=viewModelScope.launch{
        try{
            draft.value?.let{save(it)}
            val next=DesignDraft(UUID.randomUUID().toString(),"Thiết kế mới",RoomEntity(),emptyList())
            save(next);draft.value=next;selectedId=null;suggestions.value=emptyList();prefs.edit{putString("id",next.id)}
        }catch(error:Exception){message.value=error.localizedMessage.orEmpty()}
    }
    fun openProject(project:DesignProjectEntity)=viewModelScope.launch{
        if(draft.value?.id==project.id)return@launch
        try{
            draft.value?.let{save(it)}
            val next=DesignDraft(project.id,project.name,DesignCodec.readRoom(project.roomJson),DesignCodec.readPlacements(project.placementsJson),project.beforeImagePath,project.afterImagePath)
            draft.value=next;selectedId=null;suggestions.value=emptyList();prefs.edit{putString("id",next.id)}
        }catch(error:Exception){message.value="Project không đọc được: ${error.localizedMessage}"}
    }
    fun edit(transform:(DesignDraft)->DesignDraft){
        try {draft.value?.let{draft.value=transform(it)};suggestions.value=emptyList()}
        catch(error:IllegalArgumentException){message.value=error.localizedMessage.orEmpty()}
    }
    fun updateRoom(room:RoomEntity){
        try{val spec=room.spec();spec.openings.forEach{it.reservedZone(spec)};edit{it.copy(room=room)}}
        catch(error:Exception){message.value=error.localizedMessage.orEmpty()}
    }
    fun add(item:ItemEntity){
        val placement=FurniturePlacement(item=item)
        edit{it.copy(placements=it.placements+placement)}
        if(draft.value?.placements?.any{it.id==placement.id}==true)selectedId=placement.id
    }
    fun update(placement:FurniturePlacement){edit{it.copy(placements=it.placements.map{old->if(old.id==placement.id)placement else old})}}
    fun duplicate(placement:FurniturePlacement){
        val next=placement.copy(id=UUID.randomUUID().mostSignificantBits,xCm=placement.xCm+20,zCm=placement.zCm+20,locked=false)
        edit{it.copy(placements=it.placements+next)}
        if(draft.value?.placements?.any{it.id==next.id}==true)selectedId=next.id
    }
    fun remove(id:Long){edit{it.copy(placements=it.placements.filterNot{it.id==id})};if(selectedId==id)selectedId=null}
    fun addRegionFit(result:RegionFitResult,option:RegionFitOption)=viewModelScope.launch{
        try{
            val placement=option.placement
            require(option in result.options && placement!=null && placement.item==option.item){"Kết quả chọn vùng không hợp lệ."}
            val latestItem=db.itemDao().getItemById(option.item.id)
            require(latestItem==option.item){"Món đồ trong kho đã đổi; hãy chọn lại vùng."}
            val source=result.source
            require(draft.value==source){"Thiết kế đã đổi; hãy chọn lại vùng."}
            require(result.region.contains(placement.footprint()) && LayoutPlanner.valid(source.room.spec(),placement.footprint(),source.placements.map{it.footprint()})){
                "Vị trí không còn hợp lệ; hãy chọn lại vùng."
            }
            val next=source.copy(placements=source.placements+placement) // Includes scale, size and duplicate-ID validation.
            require(draft.compareAndSet(source,next)){"Thiết kế đã đổi; hãy chọn lại vùng."}
            selectedId=placement.id;suggestions.value=emptyList();message.value="Đã thêm đồ vào vùng đã chọn."
        }catch(cancel:CancellationException){throw cancel
        }catch(error:Exception){message.value=error.localizedMessage ?: "Chưa thêm được đồ. Hãy thử lại."}
    }
    fun suggest(style:String){
        val source=draft.value ?: return
        if(planning.value)return
        planning.value=true
        viewModelScope.launch{
            try{
                val results=withContext(Dispatchers.Default){LayoutPlanner.suggest(source.room.spec(),source.placements.map{it.footprint()},source.placements.filter{it.locked}.map{it.id}.toSet(),style)}
                if(draft.value!=source){message.value="Thiết kế đã đổi; hãy tạo lại gợi ý.";return@launch}
                suggestions.value=results.map{layout->layout.map{f->source.placements.first{it.id==f.id}.copy(xCm=f.centerXcm,zCm=f.centerZcm,rotation=f.rotationDegrees)}}
                message.value=if(results.isEmpty())"Không tìm thấy phương án đủ chỗ và đủ khoảng cách cho tất cả đồ. Giảm số đồ hoặc kiểm tra các món đã khóa."
                    else "${results.size} phương án đã kiểm tra tường, va chạm, cửa và khoảng cách giữa đồ."
            }catch(error:Exception){message.value=error.localizedMessage.orEmpty()}finally{planning.value=false}
        }
    }
    fun applySuggestion(index:Int){val selected=suggestions.value.getOrNull(index)?:return;edit{it.copy(placements=selected)}}
}
