package com.privateai.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(db: AppDatabase) {
    private val chats = db.chatDao()
    private val msgs = db.messageDao()

    fun observeChats(): Flow<List<ChatEntity>> = chats.observeAll()
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = msgs.observe(chatId)
    suspend fun messages(chatId: Long): List<MessageEntity> = msgs.getAll(chatId)

    suspend fun newChat(title: String): Long {
        val now = System.currentTimeMillis()
        return chats.insert(ChatEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun addMessage(chatId: Long, role: String, text: String): Long {
        val now = System.currentTimeMillis()
        val id = msgs.insert(MessageEntity(chatId = chatId, role = role, text = text, time = now))
        chats.touch(chatId, now)
        return id
    }

    suspend fun setText(id: Long, text: String) = msgs.setText(id, text)
    suspend fun deleteMessage(id: Long) = msgs.delete(id)
    suspend fun deleteFrom(chatId: Long, fromId: Long) = msgs.deleteFrom(chatId, fromId)
    suspend fun deleteEmptyAssistant() = msgs.deleteEmptyAssistant()
    suspend fun rename(id: Long, title: String) = chats.rename(id, title.trim().ifEmpty { "Untitled chat" })
    suspend fun deleteChat(id: Long) = chats.delete(id)
    suspend fun deleteAll() = chats.deleteAll()
}
