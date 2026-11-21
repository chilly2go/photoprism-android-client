package ua.com.radiokot.photoprism.features.importt.picker.data.storage

import ua.com.radiokot.photoprism.db.AppDatabase
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LibraryHashDbEntity

/**
 * Remembers which files are in the library, so they don't have to be checked each time.
 * All the methods are blocking and must be called off the main thread.
 */
class LibraryHashesRepository(
    private val database: AppDatabase,
) {
    private val log = kLogger("LibraryHashesRepo")
    private val dao: LibraryHashesDbDao
        get() = database.libraryHashes()

    /**
     * @return when each known file of the library was last seen there, by SHA-1.
     */
    fun getCheckedAtBySha1(libraryRootUrl: String): Map<String, Long> =
        dao.getAll(libraryRootUrl).associate { it.sha1 to it.checkedAtMs }

    /**
     * Updates the knowledge with the result of checking the [checked] hashes,
     * of which only [inLibrary] are in the library.
     */
    fun update(
        libraryRootUrl: String,
        checked: Collection<String>,
        inLibrary: Collection<String>,
    ) {
        val missing = checked - inLibrary.toSet()
        database.runInTransaction {
            remember(libraryRootUrl, inLibrary)
            // The list is split to stay below the SQLite variable limit.
            missing.chunked(MAX_SQL_VARIABLES).forEach { chunk ->
                dao.delete(libraryRootUrl, chunk)
            }
        }

        log.debug {
            "update(): updated:" +
                    "\nchecked=${checked.size}," +
                    "\ninLibrary=${inLibrary.size}"
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
        val now = System.currentTimeMillis()
        dao.insertOrReplace(sha1s.map { sha1 ->
            LibraryHashDbEntity(
                libraryRootUrl = libraryRootUrl,
                sha1 = sha1,
                checkedAtMs = now,
            )
        })
    }

    private companion object {
        private const val MAX_SQL_VARIABLES = 500
    }
}
