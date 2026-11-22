package ua.com.radiokot.photoprism.features.importt.picker.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * What is known about a file SHA-1 in the library:
 * whether it was there when last checked.
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
     * When the library was last checked for the file.
     */
    @ColumnInfo(name = "checked_at_ms")
    val checkedAtMs: Long,
    /**
     * Whether the library had the file when checked.
     * Files which are not there are remembered too,
     * so they are not asked about each time the picker opens.
     */
    @ColumnInfo(name = "in_library", defaultValue = "1")
    val isInLibrary: Boolean = true,
)
