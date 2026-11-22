package ua.com.radiokot.photoprism.features.importt.queue.logic

import ua.com.radiokot.photoprism.api.photos.model.PhotoPrismPhotoUpdate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns the date a file was taken, as known on the device,
 * into a photo update which sets it in the library.
 */
object ImportQueueTakenAt {
    /**
     * Source which keeps the date from being overwritten by re-indexing.
     */
    private const val TAKEN_SRC_MANUAL = "manual"
    private const val DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    /**
     * @param takenAtMs UTC milliseconds since the epoch, as MediaStore gives them
     * @param timeZone zone to get the local time in, the device one by default,
     * as the place where the photo was taken is unknown
     */
    fun toPhotoUpdate(
        takenAtMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): PhotoPrismPhotoUpdate {
        val date = Date(takenAtMs)
        val utcFormat = SimpleDateFormat(DATE_FORMAT, Locale.ROOT).apply {
            this.timeZone = TimeZone.getTimeZone("UTC")
        }
        // The library keeps the local time as if it was UTC.
        val localFormat = SimpleDateFormat(DATE_FORMAT, Locale.ROOT).apply {
            this.timeZone = timeZone
        }
        val localCalendar = Calendar.getInstance(timeZone, Locale.ROOT).apply {
            time = date
        }

        return PhotoPrismPhotoUpdate(
            takenAt = utcFormat.format(date),
            takenAtLocal = localFormat.format(date),
            timeZone = timeZone.id,
            takenSrc = TAKEN_SRC_MANUAL,
            year = localCalendar.get(Calendar.YEAR),
            month = localCalendar.get(Calendar.MONTH) + 1,
            day = localCalendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}
