package ua.com.radiokot.photoprism.features.importt.queue.data.storage

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import ua.com.radiokot.photoprism.db.AppDatabase
import ua.com.radiokot.photoprism.di.JsonObjectMapper
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueBatch
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueBatchDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemState
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueStateCount
import java.util.Date

/**
 * Persistent queue of files to upload.
 * All the methods are blocking and must be called off the main thread.
 */
class ImportQueueRepository(
    private val database: AppDatabase,
    private val jsonObjectMapper: JsonObjectMapper,
) {
    private val log = kLogger("ImportQueueRepository")
    private val dao: ImportQueueDbDao
        get() = database.importQueue()

    private val changesSubject = BehaviorSubject.createDefault(Unit)

    /**
     * Emits each time the queue changes.
     */
    val changes: Observable<Unit> = changesSubject

    private fun notifyChanged() =
        changesSubject.onNext(Unit)

    /**
     * Adds the [files] to the queue as a new batch.
     *
     * @return ID of the batch
     */
    fun addBatch(
        files: Collection<ImportableFile>,
        albums: Set<DestinationAlbum>,
        libraryRootUrl: String,
    ): Long {
        val batchId = database.runInTransaction<Long> {
            val batchId = dao.insertBatch(
                ImportQueueBatchDbEntity(
                    createdAtMs = System.currentTimeMillis(),
                    libraryRootUrl = libraryRootUrl,
                    // Albums must be converted to a typed array to overcome Java type erasure,
                    // which makes @JsonTypeInfo useless when serializing a collection.
                    albumsJson = jsonObjectMapper.writeValueAsString(albums.toTypedArray()),
                )
            )

            dao.insertItems(
                files.map { file ->
                    ImportQueueItemDbEntity(
                        batchId = batchId,
                        contentUri = file.contentUri,
                        displayName = file.displayName,
                        mimeType = file.mimeType,
                        reportedSize = file.reportedSize,
                        sha1 = null,
                        state = ImportQueueItemState.PENDING.name,
                        error = null,
                    )
                }
            )

            batchId
        }

        log.debug {
            "addBatch(): added:" +
                    "\nbatchId=$batchId," +
                    "\nfiles=${files.size}"
        }

        notifyChanged()

        return batchId
    }

    fun getBatches(): List<ImportQueueBatch> {
        val countsByBatch = dao.getStateCounts().groupBy { it.batchId }
        val failedItemsByBatch = dao
            .getItemsInState(ImportQueueItemState.FAILED.name)
            .groupBy { it.batchId }

        return dao.getBatches().map { batch ->
            ImportQueueBatch(
                id = batch.id,
                createdAt = Date(batch.createdAtMs),
                albums = readAlbums(batch.albumsJson),
                countsByState = countsByBatch[batch.id]
                    .orEmpty()
                    .associate { ImportQueueItemState.valueOf(it.state) to it.count },
                failedItems = failedItemsByBatch[batch.id]
                    .orEmpty()
                    .map { item ->
                        ImportQueueBatch.FailedItem(
                            displayName = item.displayName,
                            error = item.error,
                        )
                    },
            )
        }
    }

    fun getNextBatch(libraryRootUrl: String): ImportQueueBatchDbEntity? =
        dao.getNextBatch(libraryRootUrl, ImportQueueItemState.PENDING.name)

    fun countBatchesWithPendingItems(libraryRootUrl: String): Int =
        dao.countBatchesWithPendingItems(libraryRootUrl, ImportQueueItemState.PENDING.name)

    /**
     * @return count of finished items to total count of items in the batch.
     */
    fun getBatchProgress(batchId: Long): Pair<Int, Int> {
        val counts = dao.getBatchStateCounts(batchId)
        val finished = counts
            .filter { ImportQueueItemState.valueOf(it.state).isFinal }
            .sumOf(ImportQueueStateCount::count)
        return finished to counts.sumOf(ImportQueueStateCount::count)
    }

    fun getAlbums(batch: ImportQueueBatchDbEntity): Set<DestinationAlbum> =
        readAlbums(batch.albumsJson)

    fun getPendingItems(batchId: Long, limit: Int): List<ImportQueueItemDbEntity> =
        dao.getBatchItemsInState(batchId, ImportQueueItemState.PENDING.name, limit)

    fun hasPendingItems(): Boolean =
        dao.countItemsInStates(listOf(ImportQueueItemState.PENDING.name)) > 0

    fun updateItems(items: List<ImportQueueItemDbEntity>) {
        if (items.isEmpty()) {
            return
        }
        dao.updateItems(items)
        notifyChanged()
    }

    /**
     * Returns the items which were interrupted while being processed
     * back to the queue, as their upload must be repeated.
     */
    fun requeueInterruptedItems() {
        dao.changeStates(
            fromStates = listOf(
                ImportQueueItemState.UPLOADING.name,
                ImportQueueItemState.UPLOADED.name,
            ),
            toState = ImportQueueItemState.PENDING.name,
        )
        notifyChanged()
    }

    fun retryFailedItems(batchId: Long) {
        dao.changeBatchStates(
            batchId = batchId,
            fromStates = listOf(ImportQueueItemState.FAILED.name),
            toState = ImportQueueItemState.PENDING.name,
        )
        notifyChanged()
    }

    fun cancelPendingItems(batchId: Long) {
        dao.changeBatchStates(
            batchId = batchId,
            fromStates = listOf(ImportQueueItemState.PENDING.name),
            toState = ImportQueueItemState.CANCELLED.name,
        )
        notifyChanged()
    }

    fun removeBatch(batchId: Long) {
        database.runInTransaction {
            dao.deleteBatchItems(batchId)
            dao.deleteBatch(batchId)
        }
        notifyChanged()
    }

    fun removeFinishedBatches() {
        getBatches()
            .filter(ImportQueueBatch::isFinished)
            .forEach { removeBatch(it.id) }
    }

    private fun readAlbums(albumsJson: String): Set<DestinationAlbum> =
        jsonObjectMapper
            .readerForListOf(DestinationAlbum::class.java)
            .readValue<Collection<DestinationAlbum>>(albumsJson)
            .toSet()
}
