package com.privateai.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "benchmarks")
data class BenchmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val model: String,
    val modelBytes: Long,
    val quant: String,
    val promptKind: String,
    val maxTokens: Int,
    val runs: Int,
    val firstTokenMs: Int,
    val tokensPerSec: Double,
    val durationMs: Int,
    val tokens: Int,
    val availRamMb: Int,
    val appMemMb: Int,
    val tempStartC: Double,   // -1.0 = unknown
    val tempEndC: Double,
    val batteryPct: Int,
    val thermal: Int,         // Android thermal status, -1 = unknown
)

@Dao
interface BenchmarkDao {
    @Query("SELECT * FROM benchmarks ORDER BY id DESC LIMIT 50")
    fun observeAll(): Flow<List<BenchmarkEntity>>

    @Insert
    suspend fun insert(b: BenchmarkEntity): Long

    @Query("DELETE FROM benchmarks")
    suspend fun deleteAll()
}
