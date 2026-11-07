package com.privateai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatEntity::class, MessageEntity::class, NoteEntity::class,
        DocumentEntity::class, DocumentChunkEntity::class,
        PersonaEntity::class, BenchmarkEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun noteDao(): NoteDao
    abstract fun documentDao(): DocumentDao
    abstract fun personaDao(): PersonaDao
    abstract fun benchmarkDao(): BenchmarkDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // Migrations only add tables, so existing chats, notes and documents are kept.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `documents` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `author` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, " +
                        "`chunkCount` INTEGER NOT NULL, `ocrPages` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_chunks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentId` INTEGER NOT NULL, " +
                        "`page` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `overlapWords` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, `embedding` BLOB NOT NULL, " +
                        "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_chunks_documentId` ON `document_chunks` (`documentId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personas` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`systemPrompt` TEXT NOT NULL, `builtIn` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `benchmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`model` TEXT NOT NULL, `modelBytes` INTEGER NOT NULL, `quant` TEXT NOT NULL, " +
                        "`promptKind` TEXT NOT NULL, `maxTokens` INTEGER NOT NULL, `runs` INTEGER NOT NULL, " +
                        "`firstTokenMs` INTEGER NOT NULL, `tokensPerSec` REAL NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`tokens` INTEGER NOT NULL, `availRamMb` INTEGER NOT NULL, `appMemMb` INTEGER NOT NULL, " +
                        "`tempStartC` REAL NOT NULL, `tempEndC` REAL NOT NULL, `batteryPct` INTEGER NOT NULL, " +
                        "`thermal` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "privateai.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
