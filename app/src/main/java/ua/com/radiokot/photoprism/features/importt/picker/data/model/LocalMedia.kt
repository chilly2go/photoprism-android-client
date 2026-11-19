package ua.com.radiokot.photoprism.features.importt.picker.data.model

import android.net.Uri

/**
 * An image or a video from the device MediaStore.
 */
data class LocalMedia(
    val id: Long,
    /**
     * URI for thumbnails.
     */
    val uri: Uri,
    /**
     * URI to read the file contents from: the original one, if allowed,
     * so the location data is not redacted.
     */
    val contentUri: Uri,
    val isOriginal: Boolean,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    /**
     * Seconds.
     */
    val dateModified: Long,
    /**
     * Milliseconds, the modification date if unknown.
     */
    val dateTakenMs: Long,
    val bucketId: String?,
    val bucketName: String?,
    /**
     * Folder path relative to the storage volume, if known.
     */
    val bucketPath: String?,
    val isOnSdCard: Boolean,
    val isVideo: Boolean,
)
