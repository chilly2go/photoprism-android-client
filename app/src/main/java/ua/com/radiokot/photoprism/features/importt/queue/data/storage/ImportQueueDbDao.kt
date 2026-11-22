package ua.com.radiokot.photoprism.features.importt.queue.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueBatchDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueStateCount

@Dao
interface ImportQueueDbDao {
    @Insert
    fun insertBatch(batch: ImportQueueBatchDbEntity): Long

    @Insert
    fun insertItems(items: List<ImportQueueItemDbEntity>)

    @Update
    fun updateItems(items: List<ImportQueueItemDbEntity>)

    @Query("SELECT * FROM import_queue_batches ORDER BY id")
    fun getBatches(): List<ImportQueueBatchDbEntity>

    @Query("SELECT * FROM import_queue_batches WHERE id=:batchId")
    fun getBatch(batchId: Long): ImportQueueBatchDbEntity?

    @Query("SELECT * FROM import_queue_items ORDER BY batch_id, id")
    fun getAllItems(): List<ImportQueueItemDbEntity>

    @Query("SELECT content_uri FROM import_queue_items WHERE state IN (:states)")
    fun getContentUrisInStates(states: List<String>): List<String>

    @Query("SELECT state, COUNT(*) AS count, 0 AS batch_id FROM import_queue_items WHERE batch_id IN (SELECT id FROM import_queue_batches WHERE library_root_url=:libraryRootUrl) GROUP BY state")
    fun getLibraryStateCounts(libraryRootUrl: String): List<ImportQueueStateCount>

    @Query("SELECT * FROM import_queue_items WHERE batch_id=:batchId AND state=:state ORDER BY id LIMIT :limit")
    fun getBatchItemsInState(batchId: Long, state: String, limit: Int): List<ImportQueueItemDbEntity>

    /**
     * @return items of the library in the [states] which have an upload token.
     */
    @Query(
        "SELECT * FROM import_queue_items " +
                "WHERE state IN (:states) AND upload_token IS NOT NULL " +
                "AND batch_id IN (SELECT id FROM import_queue_batches WHERE library_root_url=:libraryRootUrl) " +
                "ORDER BY batch_id, id"
    )
    fun getLibraryItemsWithUploadToken(libraryRootUrl: String, states: List<String>): List<ImportQueueItemDbEntity>

    /**
     * @return the oldest batch of the library which has items waiting to be uploaded.
     */
    @Query(
        "SELECT * FROM import_queue_batches " +
                "WHERE library_root_url=:libraryRootUrl " +
                "AND id IN (SELECT batch_id FROM import_queue_items WHERE state=:pendingState) " +
                "ORDER BY id LIMIT 1"
    )
    fun getNextBatch(libraryRootUrl: String, pendingState: String): ImportQueueBatchDbEntity?

    @Query("SELECT COUNT(*) FROM import_queue_items WHERE state IN (:states)")
    fun countItemsInStates(states: List<String>): Int

    @Query("UPDATE import_queue_items SET state=:toState, error=NULL, failure_reason=NULL, upload_token=NULL WHERE state IN (:fromStates)")
    fun changeStates(fromStates: List<String>, toState: String)

    /**
     * Changes the state of the items which have no upload token,
     * so their upload must be repeated.
     */
    @Query("UPDATE import_queue_items SET state=:toState, error=NULL, failure_reason=NULL WHERE state IN (:fromStates) AND upload_token IS NULL")
    fun changeStatesWithoutUploadToken(fromStates: List<String>, toState: String)

    /**
     * Changes the state of the items which have an upload token,
     * keeping it, so their import can be finished.
     */
    @Query("UPDATE import_queue_items SET state=:toState WHERE state IN (:fromStates) AND upload_token IS NOT NULL")
    fun changeStatesWithUploadToken(fromStates: List<String>, toState: String)

    @Query("UPDATE import_queue_items SET state=:toState, error=NULL, failure_reason=NULL, upload_token=NULL WHERE id=:itemId AND state IN (:fromStates)")
    fun changeItemState(itemId: Long, fromStates: List<String>, toState: String): Int

    @Query(
        "UPDATE import_queue_items SET state=:toState, error=:error, failure_reason=:failureReason, upload_token=NULL " +
                "WHERE state=:fromState " +
                "AND batch_id IN (SELECT id FROM import_queue_batches WHERE library_root_url=:libraryRootUrl)"
    )
    fun failLibraryItemsInState(
        libraryRootUrl: String,
        fromState: String,
        toState: String,
        error: String,
        failureReason: String,
    )

    @Query("DELETE FROM import_queue_items WHERE state IN (:states)")
    fun deleteItemsInStates(states: List<String>)

    @Query("DELETE FROM import_queue_items WHERE id=:itemId AND state IN (:states)")
    fun deleteItemInStates(itemId: Long, states: List<String>)

    @Query("DELETE FROM import_queue_batches WHERE id NOT IN (SELECT DISTINCT batch_id FROM import_queue_items)")
    fun deleteEmptyBatches()

    @Query("DELETE FROM import_queue_items")
    fun deleteAllItems()

    @Query("DELETE FROM import_queue_batches")
    fun deleteAllBatches()
}
