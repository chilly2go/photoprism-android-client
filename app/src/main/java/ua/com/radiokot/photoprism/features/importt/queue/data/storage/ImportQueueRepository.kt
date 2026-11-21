package ua.com.radiokot.photoprism.features.importt.queue.data.storage

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import ua.com.radiokot.photoprism.db.AppDatabase
import ua.com.radiokot.photoprism.di.JsonObjectMapper
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueBatchDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueFailureReason
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemState
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueOverview
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

    fun getOverview(): ImportQueueOverview =
        ImportQueueOverview(
            batches = dao.getBatches().map { batch ->
                ImportQueueOverview.Batch(
                    id = batch.id,
                    createdAt = Date(batch.createdAtMs),
                    albums = readAlbums(batch.albumsJson),
                )
            },
            items = dao.getAllItems().map { item ->
                ImportQueueOverview.Item(
                    id = item.id,
                    batchId = item.batchId,
                    contentUri = item.contentUri,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    state = item.itemState,
                    failureReason = item.failureReason
                        ?.let { name -> ImportQueueFailureReason.values().find { it.name == name } },
                    error = item.error,
                )
            },
        )

    fun getNextBatch(libraryRootUrl: String): ImportQueueBatchDbEntity? =
        dao.getNextBatch(libraryRootUrl, ImportQueueItemState.PENDING.name)

    /**
     * @return count of finished items to total count of items of the library,
     * cancelled ones excluded.
     */
    fun getProgress(libraryRootUrl: String): Pair<Int, Int> {
        val counts = dao.getLibraryStateCounts(libraryRootUrl)
            .associate { ImportQueueItemState.valueOf(it.state) to it.count }
            .minus(ImportQueueItemState.CANCELLED)
        val finished = counts
            .filterKeys(ImportQueueItemState::isFinal)
            .values
            .sum()
        return finished to counts.values.sum()
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
     * Marks the item as being uploaded, unless it has been removed or changed meanwhile.
     *
     * @return true if the item is to be uploaded
     */
    fun startItem(itemId: Long): Boolean {
        val isStarted = dao.changeItemState(
            itemId = itemId,
            fromStates = listOf(ImportQueueItemState.PENDING.name),
            toState = ImportQueueItemState.UPLOADING.name,
        ) > 0
        if (isStarted) {
            notifyChanged()
        }
        return isStarted
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

    fun retryAllFailedItems() {
        dao.changeStates(
            fromStates = listOf(ImportQueueItemState.FAILED.name),
            toState = ImportQueueItemState.PENDING.name,
        )
        notifyChanged()
    }

    fun retryItem(itemId: Long) {
        dao.changeItemState(
            itemId = itemId,
            fromStates = listOf(ImportQueueItemState.FAILED.name),
            toState = ImportQueueItemState.PENDING.name,
        )
        notifyChanged()
    }

    /**
     * Removes the item unless it is being uploaded right now.
     */
    fun removeItem(itemId: Long) {
        database.runInTransaction {
            dao.deleteItemInStates(itemId, REMOVABLE_STATES)
            dao.deleteEmptyBatches()
        }
        notifyChanged()
    }

    /**
     * Removes all the items waiting to be uploaded.
     */
    fun removePendingItems() =
        removeItemsInStates(ImportQueueItemState.PENDING)

    /**
     * Removes the items which are successfully done or cancelled,
     * keeping the failed ones.
     */
    fun removeDoneItems() =
        removeItemsInStates(
            ImportQueueItemState.IMPORTED,
            ImportQueueItemState.ALREADY_IN_LIBRARY,
            ImportQueueItemState.CANCELLED,
        )

    fun removeFailedItems() =
        removeItemsInStates(ImportQueueItemState.FAILED)

    private fun removeItemsInStates(vararg states: ImportQueueItemState) {
        database.runInTransaction {
            dao.deleteItemsInStates(states.map(ImportQueueItemState::name))
            dao.deleteEmptyBatches()
        }
        notifyChanged()
    }

    private fun readAlbums(albumsJson: String): Set<DestinationAlbum> =
        jsonObjectMapper
            .readerForListOf(DestinationAlbum::class.java)
            .readValue<Collection<DestinationAlbum>>(albumsJson)
            .toSet()

    private companion object {
        private val REMOVABLE_STATES = ImportQueueItemState.values()
            .filterNot { it == ImportQueueItemState.UPLOADING || it == ImportQueueItemState.UPLOADED }
            .map(ImportQueueItemState::name)
    }
}
