package ua.com.radiokot.photoprism.features.importt.picker.logic

import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.picker.data.storage.LibraryHashesRepository
import java.io.IOException

/**
 * Checks which file hashes are in the library, many at once,
 * remembering the results.
 */
class LibraryHashChecker(
    private val photosService: PhotoPrismPhotosService,
    private val libraryHashesRepository: LibraryHashesRepository,
    private val libraryRootUrl: String,
) {
    private val log = kLogger("LibraryHashChecker")

    /**
     * @return what previous checks and uploads from this device found out, by SHA-1.
     */
    fun getKnown(): Map<String, LibraryHashesRepository.Known> =
        libraryHashesRepository.getKnown(libraryRootUrl)

    /**
     * @return the [hashes] which are in the library, with private, archived,
     * hidden and broken files included, like when uploading checks a single file.
     */
    @Throws(IOException::class)
    fun getHashesInLibrary(hashes: Collection<String>): Set<String> {
        if (hashes.isEmpty()) {
            return emptySet()
        }

        val found = mutableSetOf<String>()

        // The search leaves out archived and hidden photos and files with errors
        // unless asked explicitly, one kind at a time.
        // Without the permission, the server silently ignores the filter.
        for (pass in SearchPass.values()) {
            val notFoundYet = hashes.filterNot(found::contains)
            if (notFoundYet.isEmpty()) {
                break
            }

            val photos = photosService.getMergedPhotos(
                // A photo may have several matching files.
                count = notFoundYet.size * 2,
                offset = 0,
                public = false,
                // Passed as a separate parameter, as the query has a length limit
                // beyond which the hash filter is silently ignored.
                hash = notFoundYet.joinToString(separator = "|"),
                archived = pass.archived,
                hidden = pass.hidden,
                error = pass.error,
            )

            photos.forEach { photo ->
                found += photo.hash
                photo.files.mapTo(found) { it.hash }
            }
        }

        return found.intersect(hashes.toSet()).also {
            libraryHashesRepository.update(
                libraryRootUrl = libraryRootUrl,
                checked = hashes,
                inLibrary = it,
            )

            log.debug {
                "getHashesInLibrary(): checked:" +
                        "\nhashes=${hashes.size}," +
                        "\nfound=${it.size}"
            }
        }
    }

    private enum class SearchPass(
        val archived: Boolean? = null,
        val hidden: Boolean? = null,
        val error: Boolean? = null,
    ) {
        DEFAULT,
        ARCHIVED(archived = true),
        HIDDEN(hidden = true),
        WITH_ERRORS(error = true),
        ;
    }
}
