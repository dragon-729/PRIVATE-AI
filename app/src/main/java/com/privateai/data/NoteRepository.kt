package com.privateai.data

import kotlinx.coroutines.flow.Flow

class NoteRepository(db: AppDatabase) {
    private val dao = db.noteDao()
    fun observeAll(): Flow<List<NoteEntity>> = dao.observeAll()

    suspend fun insert(title: String, content: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(NoteEntity(title = title, content = content, createdAt = now, updatedAt = now))
    }

    suspend fun update(id: Long, title: String, content: String) =
        dao.update(id, title, content, System.currentTimeMillis())

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAll() = dao.deleteAll()
}
