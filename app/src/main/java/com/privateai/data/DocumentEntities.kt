package com.privateai.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val title: String,
    val author: String,
    val pageCount: Int,
    val chunkCount: Int,
    val ocrPages: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val page: Int,           // real PDF page number (1-based)
    val idx: Int,            // order inside the document
    val overlapWords: Int,   // words repeated from the previous chunk of the same page
    val text: String,
    val embedding: ByteArray, // 384 float32 values, little-endian
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Insert
    suspend fun insert(doc: DocumentEntity): Long

    @Insert
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Query("SELECT * FROM document_chunks WHERE documentId = :id ORDER BY idx ASC")
    suspend fun chunks(id: Long): List<DocumentChunkEntity>

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)
}
