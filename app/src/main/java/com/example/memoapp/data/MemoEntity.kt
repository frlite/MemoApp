package com.example.memoapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memos")
data class MemoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    /** 朗读语速，0.5 ~ 2.0 */
    val speechRate: Float = 1.0f,
    /** 朗读音调，0.5 ~ 2.0 */
    val pitch: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
