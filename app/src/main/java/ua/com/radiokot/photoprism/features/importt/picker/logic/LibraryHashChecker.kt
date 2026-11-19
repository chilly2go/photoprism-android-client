package ua.com.radiokot.photoprism.features.importt.picker.logic

import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.kLogger
import java.io.IOException

/**
 * Checks which file hashes are in the library, many at once.
 */
class LibraryHashChecker(
    private val photosService: PhotoPrismPhotosService,
) {
    private val log = kLogger("LibraryHashChecker")

    /**
     * @return the [hashes] which are in the library, with private and archived photos included.
     */
    @Throws(IOException::class)
    fun getHashesInLibrary(hashes: Collection<String>): Set<String> {
        if (hashes.isEmpty()) {
            return emptySet()
        }

        // Passed as a separate parameter, as the query has a length limit
        // beyond which the hash filter is silently ignored.
        val hashParam = hashes.joinToString(separator = "|")
        val found = mutableSetOf<String>()

        // The archived photos are only returned when asked explicitly.
        for (archived in listOf(null, true)) {
            val photos = photosService.getMergedPhotos(
                // A photo may have several matching files.
                count = hashes.size * 2,
                offset = 0,
                public = false,
                hash = hashParam,
                archived = archived,
            )

            photos.forEach { photo ->
                found += photo.hash
                photo.files.mapTo(found) { it.hash }
            }
        }

        return found.intersect(hashes.toSet()).also {
            log.debug {
                "getHashesInLibrary(): checked:" +
                        "\nhashes=${hashes.size}," +
                        "\nfound=${it.size}"
            }
        }
    }
}
