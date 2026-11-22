package ua.com.radiokot.photoprism.features.importt.logic

import android.content.ContentResolver
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest

/**
 * Calculates SHA-1 of an image as Android gives it to apps without the location access:
 * the values of the GPS tags are zeroed, keeping the size.
 * The upstream app and sharing uploaded such copies.
 *
 * Apps with the location access get the original contents even without asking for them,
 * so the redaction is repeated here the way MediaProvider does it.
 */
@RequiresApi(Build.VERSION_CODES.Q)
object RedactedImageHasher {
    /**
     * @return SHA-1 of the redacted contents of the image at [uri],
     * or null if there is nothing to redact.
     */
    @Throws(IOException::class)
    fun calculate(
        contentResolver: ContentResolver,
        uri: Uri,
        isCancelled: () -> Boolean = { false },
    ): String? {
        val ranges: List<LongArray> =
            (contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Can't open file descriptor"))
                .use { descriptor ->
                    val exif = try {
                        ExifInterface(descriptor.fileDescriptor)
                    } catch (error: Exception) {
                        // Not a format with Exif.
                        return null
                    }
                    REDACTED_TAGS.mapNotNull { tag ->
                        try {
                            exif.getAttributeRange(tag)
                        } catch (error: IllegalStateException) {
                            null
                        }
                    }
                }
                .filter { it[1] > 0 }
                .sortedBy { it[0] }

        if (ranges.isEmpty()) {
            return null
        }

        val digest = MessageDigest.getInstance("SHA-1")
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Can't open input stream")

        input.use {
            val buffer = ByteArray(64 * 1024)
            var position = 0L
            while (true) {
                if (isCancelled()) {
                    throw InterruptedIOException("Cancelled")
                }
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }

                val end = position + read
                for ((offset, length) in ranges.map { it[0] to it[1] }) {
                    val from = maxOf(offset, position)
                    val to = minOf(offset + length, end)
                    if (from < to) {
                        buffer.fill(0, (from - position).toInt(), (to - position).toInt())
                    }
                }

                digest.update(buffer, 0, read)
                position = end
            }
        }

        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    /**
     * The tags MediaProvider redacts for apps without the location access.
     */
    private val REDACTED_TAGS = listOf(
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_VERSION_ID,
    )
}
