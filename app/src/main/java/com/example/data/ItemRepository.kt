package com.example.data

import kotlinx.coroutines.flow.Flow

class ItemRepository(private val itemDao: ItemDao) {
    val allItems: Flow<List<ItemEntity>> = itemDao.getAllItems()

    fun getItemsByCategory(category: String): Flow<List<ItemEntity>> {
        return if (category == "ALL" || category.isEmpty()) {
            itemDao.getAllItems()
        } else {
            itemDao.getItemsByCategory(category)
        }
    }

    fun getItemsByRoom(roomType: String): Flow<List<ItemEntity>> {
        return if (roomType == "ALL" || roomType.isEmpty()) {
            itemDao.getAllItems()
        } else {
            itemDao.getItemsByRoom(roomType)
        }
    }

    fun search(query: String): Flow<List<ItemEntity>> = itemDao.searchItems(query)

    suspend fun getItemById(id: Long): ItemEntity? = itemDao.getItemById(id)

    suspend fun insert(item: ItemEntity): Long = itemDao.insertItem(item)

    suspend fun update(item: ItemEntity) = itemDao.updateItem(item)

    suspend fun delete(item: ItemEntity) = itemDao.deleteItem(item)

    suspend fun deleteById(id: Long) = itemDao.deleteById(id)
}
