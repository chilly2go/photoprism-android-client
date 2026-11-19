package ua.com.radiokot.photoprism.features.importt.picker.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached SHA-1 of a local media file, valid while the size and modification date match.
 */
@Entity(tableName = "local_media_hashes")
data class LocalMediaHashDbEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "size")
    val size: Long,
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,
    /**
     * Whether the hash is of the original file, with location data not redacted.
     */
    @ColumnInfo(name = "is_original")
    val isOriginal: Boolean,
    @ColumnInfo(name = "sha1")
    val sha1: String,
)
