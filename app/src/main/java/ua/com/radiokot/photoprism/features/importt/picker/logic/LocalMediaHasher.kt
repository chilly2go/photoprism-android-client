package ua.com.radiokot.photoprism.features.importt.picker.logic

import android.content.ContentResolver
import ua.com.radiokot.photoprism.db.AppDatabase
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LocalMedia
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LocalMediaHashDbEntity
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest

/**
 * Calculates SHA-1 of local media files, caching it in the database.
 * All the work is blocking.
 */
class LocalMediaHasher(
    private val database: AppDatabase,
    private val contentResolver: ContentResolver,
) {
    private val log = kLogger("LocalMediaHasher")
    private val dao = database.localMediaHashes()

    private val cache: MutableMap<Long, LocalMediaHashDbEntity> by lazy {
        dao.getAll().associateByTo(mutableMapOf(), LocalMediaHashDbEntity::mediaId)
    }

    @Synchronized
    fun getCached(media: LocalMedia): String? =
        cache[media.id]
            ?.takeIf {
                it.size == media.size
                        && it.dateModified == media.dateModified
                        && it.isOriginal == media.isOriginal
            }
            ?.sha1

    /**
     * Calculates the hash of the [media] contents.
     * Call [save] to cache the results.
     */
    @Throws(IOException::class)
    fun calculate(
        media: LocalMedia,
        isCancelled: () -> Boolean = { false },
    ): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val input = contentResolver.openInputStream(media.contentUri)
            ?: throw IOException("Can't open input stream")

        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (isCancelled()) {
                    throw InterruptedIOException("Cancelled")
                }
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    /**
     * Forgets the hashes of the media which is not on the device anymore.
     */
    @Synchronized
    fun forgetAllExcept(mediaIds: Collection<Long>) {
        val existing = mediaIds.toHashSet()
        val gone = cache.keys.filterNot(existing::contains)
        if (gone.isEmpty()) {
            return
        }

        // The list is split to stay below the SQLite variable limit.
        gone.chunked(MAX_SQL_VARIABLES).forEach(dao::delete)
        gone.forEach(cache::remove)

        log.debug {
            "forgetAllExcept(): forgot:" +
                    "\ngone=${gone.size}"
        }
    }

    @Synchronized
    fun save(hashes: Map<LocalMedia, String>) {
        if (hashes.isEmpty()) {
            return
        }

        val entities = hashes.map { (media, sha1) ->
            LocalMediaHashDbEntity(
                mediaId = media.id,
                size = media.size,
                dateModified = media.dateModified,
                isOriginal = media.isOriginal,
                sha1 = sha1,
            )
        }
        dao.insertOrReplace(entities)
        entities.associateByTo(cache, LocalMediaHashDbEntity::mediaId)

        log.debug {
            "save(): saved:" +
                    "\nhashes=${entities.size}"
        }
    }

    private companion object {
        private const val MAX_SQL_VARIABLES = 500
    }
}
