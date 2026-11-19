package ua.com.radiokot.photoprism.features.importt.queue.data.model

import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import java.util.Date

/**
 * A batch of files added to the upload queue at once, with the state of its items.
 */
data class ImportQueueBatch(
    val id: Long,
    val createdAt: Date,
    val albums: Set<DestinationAlbum>,
    val countsByState: Map<ImportQueueItemState, Int>,
    val failedItems: List<FailedItem>,
) {
    val totalCount: Int =
        countsByState.values.sum()

    fun count(vararg states: ImportQueueItemState): Int =
        states.sumOf { countsByState[it] ?: 0 }

    /**
     * Count of the items that won't change anymore.
     */
    val finishedCount: Int =
        countsByState.entries.sumOf { (state, count) -> if (state.isFinal) count else 0 }

    val isFinished: Boolean =
        finishedCount == totalCount

    val isInProgress: Boolean =
        count(ImportQueueItemState.UPLOADING, ImportQueueItemState.UPLOADED) > 0

    data class FailedItem(
        val displayName: String,
        val error: String?,
    )
}
