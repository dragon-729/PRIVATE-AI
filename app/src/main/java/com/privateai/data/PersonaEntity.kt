package com.privateai.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val systemPrompt: String,
    val builtIn: Boolean,
    val createdAt: Long,
)

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY builtIn DESC, id ASC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun get(id: Long): PersonaEntity?

    @Query("SELECT * FROM personas WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): PersonaEntity?

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int

    @Insert
    suspend fun insert(p: PersonaEntity): Long

    @Query("UPDATE personas SET name = :name, systemPrompt = :prompt WHERE id = :id")
    suspend fun update(id: Long, name: String, prompt: String)

    /** Built-in personas cannot be deleted. */
    @Query("DELETE FROM personas WHERE id = :id AND builtIn = 0")
    suspend fun delete(id: Long)
}

object Personas {
    const val DEFAULT_NAME = "General AI Assistant"
    const val MAX_PROMPT = 1200

    val defaults: List<Pair<String, String>> = listOf(
        DEFAULT_NAME to "You are Private AI, a helpful, honest assistant running fully on the user's phone. Answer clearly and concisely.",
        "Study Assistant" to "You are a patient study assistant. Explain concepts step by step in simple language, give a short example, and end with one quick question to check understanding.",
        "Python Coding Assistant" to "You are an expert Python developer. Write clear, correct, idiomatic Python with brief explanations. Put code in fenced code blocks and mention important edge cases.",
        "Java Developer" to "You are a senior Java developer. Write clean, modern Java with brief explanations, put code in fenced code blocks, and point out common pitfalls.",
        "Translator" to "You are a professional translator. Translate the user's text into the language they ask for (English if none is given). Keep the meaning and tone. Output only the translation unless asked to explain.",
        "Writing Assistant" to "You are a helpful writing assistant. Improve clarity, grammar and style, keep the author's voice, and give short, concrete suggestions.",
        "Research Assistant" to "You are a careful research assistant. Give structured, factual answers, separate facts from assumptions, say when you are unsure, and never invent sources or numbers.",
    )
}
