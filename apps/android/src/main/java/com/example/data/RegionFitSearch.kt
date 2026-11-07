package com.example.data

import com.example.spacefit.FreeRegion
import com.example.spacefit.LayoutPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class RegionFitOption(val item:ItemEntity,val placement:FurniturePlacement?,val reason:String="")
data class RegionFitResult(val source:DesignDraft,val region:FreeRegion,val options:List<RegionFitOption>)

/** Inventory records can be legacy/invalid. One bad record must not crash the chooser. */
object RegionFitSearch {
    suspend fun find(source:DesignDraft,region:FreeRegion,inventory:List<ItemEntity>):RegionFitResult =
        withContext(Dispatchers.Default){
            val room=source.room.spec()
            val others=source.placements.map{it.footprint()}
            RegionFitResult(source,region,inventory.map{item->
                yield() // A dismissed/replaced search stops between items, off the UI thread.
                try{
                    val candidate=FurniturePlacement(item=item)
                    DesignCodec.validatePlacements(listOf(candidate))
                    if(source.placements.size>=100)RegionFitOption(item,null,"Project đã đủ 100 đồ vật")
                    else {
                        val fit=LayoutPlanner.fitsHere(room,region,candidate.footprint(),others)
                        if(fit==null)RegionFitOption(item,null,"Chưa tìm được vị trí trong các điểm thử")
                        else RegionFitOption(item,candidate.copy(xCm=fit.centerXcm,zCm=fit.centerZcm,rotation=fit.rotationDegrees))
                    }
                }catch(error:IllegalArgumentException){RegionFitOption(item,null,error.message ?: "Kích thước hoặc tỷ lệ chưa hợp lệ")}
            })
        }
}
