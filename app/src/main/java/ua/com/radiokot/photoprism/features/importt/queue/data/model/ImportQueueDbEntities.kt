package ua.com.radiokot.photoprism.features.importt.queue.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    "import_queue_batches",
)
data class ImportQueueBatchDbEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
    /**
     * Root URL of the library the batch is to be uploaded to.
     */
    @ColumnInfo("library_root_url")
    val libraryRootUrl: String,
    /**
     * JSON array of destination albums.
     */
    @ColumnInfo("albums_json")
    val albumsJson: String,
)

@Entity(
    "import_queue_items",
    indices = [
        Index("batch_id"),
        Index("state"),
    ]
)
data class ImportQueueItemDbEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0,
    @ColumnInfo("batch_id")
    val batchId: Long,
    @ColumnInfo("content_uri")
    val contentUri: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("mime_type")
    val mimeType: String?,
    @ColumnInfo("reported_size")
    val reportedSize: Long?,
    /**
     * SHA-1 hash of the content, lowercase hex, once calculated.
     */
    @ColumnInfo("sha1")
    val sha1: String?,
    /**
     * Name of [ImportQueueItemState].
     */
    @ColumnInfo("state")
    val state: String,
    @ColumnInfo("error")
    val error: String?,
    /**
     * Name of [ImportQueueFailureReason].
     */
    @ColumnInfo("failure_reason")
    val failureReason: String? = null,
    /**
     * Token of the server upload folder the file was uploaded to,
     * kept until the library has added the file,
     * so an interrupted import can be finished without uploading again.
     */
    @ColumnInfo("upload_token")
    val uploadToken: String? = null,
    /**
     * When the file was taken as known on the device, in milliseconds since the epoch.
     */
    @ColumnInfo("taken_at_ms")
    val takenAtMs: Long? = null,
) {
    val itemState: ImportQueueItemState
        get() = ImportQueueItemState.fromName(state)
}

data class ImportQueueStateCount(
    @ColumnInfo("batch_id")
    val batchId: Long,
    @ColumnInfo("state")
    val state: String,
    @ColumnInfo("count")
    val count: Int,
)
