package ua.com.radiokot.photoprism.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ua.com.radiokot.photoprism.features.ext.memories.data.model.MemoryDbEntity
import ua.com.radiokot.photoprism.features.ext.memories.data.storage.MemoriesDbDao
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchBookmarksDbEntity
import ua.com.radiokot.photoprism.features.gallery.data.storage.SearchBookmarksDbDao
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueBatchDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueDbDao
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LibraryHashDbEntity
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LocalMediaHashDbEntity
import ua.com.radiokot.photoprism.features.importt.picker.data.storage.LibraryHashesDbDao
import ua.com.radiokot.photoprism.features.importt.picker.data.storage.LocalMediaHashesDbDao
@Database(
    version = 12,
    entities = [
        SearchBookmarksDbEntity::class,
        MemoryDbEntity::class,
        ImportQueueBatchDbEntity::class,
        ImportQueueItemDbEntity::class,
        LocalMediaHashDbEntity::class,
        LibraryHashDbEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
    ],
    exportSchema = true,
)
@TypeConverters(
    value = [
        AppDatabaseConverters::class,
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarks(): SearchBookmarksDbDao
    abstract fun memories(): MemoriesDbDao
    abstract fun importQueue(): ImportQueueDbDao
    abstract fun localMediaHashes(): LocalMediaHashesDbDao
    abstract fun libraryHashes(): LibraryHashesDbDao
}
