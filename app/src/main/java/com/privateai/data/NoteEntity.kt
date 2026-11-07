package com.privateai.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun displayTitle(): String =
        title.ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40) ?: "Untitled" }
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("UPDATE notes SET title = :title, content = :content, updatedAt = :time WHERE id = :id")
    suspend fun update(id: Long, title: String, content: String, time: Long)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}
