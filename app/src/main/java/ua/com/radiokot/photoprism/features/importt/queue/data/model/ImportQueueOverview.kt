package ua.com.radiokot.photoprism.features.importt.queue.data.model

import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import java.util.Date

/**
 * All the files in the upload queue, in the order they are uploaded.
 */
data class ImportQueueOverview(
    val batches: List<Batch>,
    val items: List<Item>,
) {
    val countsByState: Map<ImportQueueItemState, Int> =
        items.groupingBy(Item::state).eachCount()

    fun count(vararg states: ImportQueueItemState): Int =
        states.sumOf { countsByState[it] ?: 0 }

    /**
     * A set of files added to the queue at once.
     */
    data class Batch(
        val id: Long,
        val createdAt: Date,
        val albums: Set<DestinationAlbum>,
    )

    data class Item(
        val id: Long,
        val batchId: Long,
        val contentUri: String,
        val displayName: String,
        val mimeType: String?,
        val state: ImportQueueItemState,
        val failureReason: ImportQueueFailureReason?,
        /**
         * Technical description of the failure.
         */
        val error: String?,
    ) {
        val isVideo: Boolean =
            mimeType?.startsWith("video/") == true

        /**
         * Whether the item is being uploaded or imported right now,
         * so it can't be removed.
         */
        val isInProgress: Boolean =
            state.isInProgress
    }
}
