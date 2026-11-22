package ua.com.radiokot.photoprism.features.importt.picker.logic

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import ua.com.radiokot.photoprism.extension.isSelfPermissionGranted
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LocalMedia

/**
 * Reads images and videos of the device from MediaStore.
 */
class LocalMediaReader(
    private val context: Context,
) {
    private val log = kLogger("LocalMediaReader")
    private val contentResolver: ContentResolver
        get() = context.contentResolver

    val readPermissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                )

            else ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /**
     * Permissions to request: reading, and location data for the originals.
     */
    val permissionsToRequest: Array<String>
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                readPermissions + Manifest.permission.ACCESS_MEDIA_LOCATION
            else
                readPermissions

    fun canRead(): Boolean =
        readPermissions.any(context::isSelfPermissionGranted)

    /**
     * @return whether the original files with the location data can be read,
     * which is always the case before Android 10.
     */
    fun canReadOriginals(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || context.isSelfPermissionGranted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    /**
     * @return all the images and videos, newest first.
     */
    fun readAll(): List<LocalMedia> {
        val canReadOriginals = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && context.isSelfPermissionGranted(Manifest.permission.ACCESS_MEDIA_LOCATION)

        val cursor = contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                COLUMN_DATE_TAKEN,
                COLUMN_BUCKET_ID,
                COLUMN_BUCKET_DISPLAY_NAME,
                *(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    arrayOf(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.VOLUME_NAME,
                    )
                else
                    emptyArray()),
            ),
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND ${MediaStore.MediaColumns.SIZE} > 0",
            arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ),
            null,
        ) ?: return emptyList<LocalMedia>().also {
            log.warn { "readAll(): query_failed" }
        }

        val media = cursor.use {
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val takenIndex = cursor.getColumnIndex(COLUMN_DATE_TAKEN)
            val bucketIdIndex = cursor.getColumnIndex(COLUMN_BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndex(COLUMN_BUCKET_DISPLAY_NAME)
            val relativePathIndex = cursor.getColumnIndex(COLUMN_RELATIVE_PATH)
            val volumeNameIndex = cursor.getColumnIndex(COLUMN_VOLUME_NAME)

            buildList(cursor.count) {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val isVideo =
                        cursor.getInt(typeIndex) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val uri = ContentUris.withAppendedId(
                        if (isVideo)
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val dateModified = cursor.getLongOrNull(modifiedIndex) ?: 0L

                    add(
                        LocalMedia(
                            id = id,
                            uri = uri,
                            contentUri =
                                if (canReadOriginals && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                    MediaStore.setRequireOriginal(uri)
                                else
                                    uri,
                            isOriginal = canReadOriginals,
                            displayName = cursor.getStringOrNull(nameIndex) ?: "$id",
                            mimeType = cursor.getStringOrNull(mimeIndex),
                            size = cursor.getLong(sizeIndex),
                            dateModified = dateModified,
                            dateTakenMs =
                                (if (takenIndex != -1) cursor.getLongOrNull(takenIndex) else null)
                                    ?.takeIf { it > 0 }
                                    ?: (dateModified * 1000),
                            bucketId =
                                if (bucketIdIndex != -1) cursor.getStringOrNull(bucketIdIndex) else null,
                            bucketName =
                                if (bucketNameIndex != -1) cursor.getStringOrNull(bucketNameIndex) else null,
                            bucketPath =
                                if (relativePathIndex != -1)
                                    cursor.getStringOrNull(relativePathIndex)?.trimEnd('/')
                                else
                                    null,
                            isOnSdCard =
                                volumeNameIndex != -1
                                        && cursor.getStringOrNull(volumeNameIndex)
                                    .let { it != null && it != VOLUME_EXTERNAL_PRIMARY },
                            isVideo = isVideo,
                        )
                    )
                }
            }
        }

        log.debug {
            "readAll(): read:" +
                    "\nmedia=${media.size}," +
                    "\ncanReadOriginals=$canReadOriginals"
        }

        return media.sortedByDescending(LocalMedia::dateTakenMs)
    }

    private companion object {
        // Plain names, as the constants are only defined in newer SDKs.
        private const val COLUMN_DATE_TAKEN = "datetaken"
        private const val COLUMN_BUCKET_ID = "bucket_id"
        private const val COLUMN_BUCKET_DISPLAY_NAME = "bucket_display_name"
        private const val COLUMN_RELATIVE_PATH = "relative_path"
        private const val COLUMN_VOLUME_NAME = "volume_name"
        private const val VOLUME_EXTERNAL_PRIMARY = "external_primary"
    }
}
