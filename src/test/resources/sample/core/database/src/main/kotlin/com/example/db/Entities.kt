package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "items")
data class ItemEntity(@PrimaryKey val id: Long, val name: String)

@Entity
data class TagEntity(@PrimaryKey val id: Long)
