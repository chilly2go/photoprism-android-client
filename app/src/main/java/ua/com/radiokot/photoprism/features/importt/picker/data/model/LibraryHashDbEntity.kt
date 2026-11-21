package ua.com.radiokot.photoprism.features.importt.picker.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * SHA-1 of a file known to be in the library.
 */
@Entity(
    tableName = "library_hashes",
    primaryKeys = ["library_root_url", "sha1"],
)
data class LibraryHashDbEntity(
    @ColumnInfo(name = "library_root_url")
    val libraryRootUrl: String,
    @ColumnInfo(name = "sha1")
    val sha1: String,
    /**
     * When the library was last seen having the file.
     */
    @ColumnInfo(name = "checked_at_ms")
    val checkedAtMs: Long,
)
