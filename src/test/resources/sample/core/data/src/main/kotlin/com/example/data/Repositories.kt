package com.example.data

import com.example.db.ItemDao
import javax.inject.Inject

interface ItemRepository
interface TagRepository

class OfflineItemRepository @Inject constructor(
    private val dao: ItemDao,
) : ItemRepository
