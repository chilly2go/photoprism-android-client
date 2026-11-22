package ua.com.radiokot.photoprism.features.importt.picker.data.storage

import ua.com.radiokot.photoprism.db.AppDatabase
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LibraryHashDbEntity

/**
 * Remembers which files are in the library and which are not,
 * so they don't have to be checked each time.
 * All the methods are blocking and must be called off the main thread.
 */
class LibraryHashesRepository(
    private val database: AppDatabase,
) {
    private val log = kLogger("LibraryHashesRepo")
    private val dao: LibraryHashesDbDao
        get() = database.libraryHashes()

    /**
     * @return what is known about the files of the library, by SHA-1.
     */
    fun getKnown(libraryRootUrl: String): Map<String, Known> =
        dao.getAll(libraryRootUrl).associate { entity ->
            entity.sha1 to Known(
                isInLibrary = entity.isInLibrary,
                checkedAtMs = entity.checkedAtMs,
            )
        }

    /**
     * Updates the knowledge with the result of checking the [checked] hashes,
     * of which only [inLibrary] are in the library.
     */
    fun update(
        libraryRootUrl: String,
        checked: Collection<String>,
        inLibrary: Collection<String>,
    ) {
        val inLibrarySet = inLibrary.toSet()
        val now = System.currentTimeMillis()
        dao.insertOrReplace(checked.map { sha1 ->
            LibraryHashDbEntity(
                libraryRootUrl = libraryRootUrl,
                sha1 = sha1,
                checkedAtMs = now,
                isInLibrary = sha1 in inLibrarySet,
            )
        })

        log.debug {
            "update(): updated:" +
                    "\nchecked=${checked.size}," +
                    "\ninLibrary=${inLibrarySet.size}"
        }
    }

    /**
     * Remembers that the files with the [sha1s] are in the library now.
     */
    fun remember(
        libraryRootUrl: String,
        sha1s: Collection<String>,
    ) {
        if (sha1s.isEmpty()) {
            return
        }
        update(
            libraryRootUrl = libraryRootUrl,
            checked = sha1s,
            inLibrary = sha1s,
        )
    }

    /**
     * Forgets everything, for example, when disconnecting from the library.
     */
    fun clear() =
        dao.deleteAll()

    data class Known(
        val isInLibrary: Boolean,
        val checkedAtMs: Long,
    )
}
