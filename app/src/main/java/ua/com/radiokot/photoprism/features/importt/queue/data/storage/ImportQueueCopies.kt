package ua.com.radiokot.photoprism.features.importt.queue.data.storage

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import ua.com.radiokot.photoprism.extension.kLogger
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Copies of shared files which the app can't keep the access to,
 * so they can still be uploaded after a restart of the device.
 * A copy is deleted once its file is in the library or removed from the queue.
 */
class ImportQueueCopies(
    private val directory: File,
    private val contentResolver: ContentResolver,
) {
    private val log = kLogger("ImportQueueCopies")

    fun isCopy(contentUri: String): Boolean =
        contentUri.startsWith(Uri.fromFile(directory).toString() + "/")

    /**
     * Copies the content of the [contentUri].
     *
     * @return URI of the copy, or null if it couldn't be made,
     * for example, because there is not enough space.
     */
    fun copy(
        contentUri: String,
        reportedSize: Long?,
    ): String? {
        directory.mkdirs()

        // Leave room for the rest of the device.
        val freeSpace = directory.usableSpace
        if (reportedSize != null && reportedSize > freeSpace - MIN_FREE_SPACE_BYTES) {
            log.debug {
                "copy(): skip_as_not_enough_space:" +
                        "\nreportedSize=$reportedSize," +
                        "\nfreeSpace=$freeSpace"
            }
            return null
        }

        val copy = File(directory, UUID.randomUUID().toString())
        return try {
            val input = contentResolver.openInputStream(contentUri.toUri())
                ?: throw IOException("Can't open input stream")
            input.use {
                copy.outputStream().use(input::copyTo)
            }
            Uri.fromFile(copy).toString()
        } catch (error: Exception) {
            log.warn(error) {
                "copy(): failed:" +
                        "\ncontentUri=$contentUri"
            }
            copy.delete()
            null
        }
    }

    /**
     * Deletes the copies which are not among the [neededContentUris].
     */
    @Synchronized
    fun deleteUnneeded(neededContentUris: Collection<String>) {
        val needed = neededContentUris
            .filter(::isCopy)
            .mapTo(mutableSetOf()) { it.toUri().lastPathSegment }
        val unneeded = directory.listFiles()
            ?.filterNot { it.name in needed }
            ?: return
        unneeded.forEach(File::delete)

        if (unneeded.isNotEmpty()) {
            log.debug {
                "deleteUnneeded(): deleted:" +
                        "\ncount=${unneeded.size}"
            }
        }
    }

    @Synchronized
    fun deleteAll() {
        directory.deleteRecursively()
    }

    private companion object {
        private const val MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024
    }
}
