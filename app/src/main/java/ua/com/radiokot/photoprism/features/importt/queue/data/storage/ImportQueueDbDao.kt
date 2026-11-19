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

    @Query("SELECT batch_id, state, COUNT(*) AS count FROM import_queue_items GROUP BY batch_id, state")
    fun getStateCounts(): List<ImportQueueStateCount>

    @Query("SELECT * FROM import_queue_items WHERE state=:state ORDER BY id")
    fun getItemsInState(state: String): List<ImportQueueItemDbEntity>

    @Query("SELECT * FROM import_queue_items WHERE batch_id=:batchId AND state=:state ORDER BY id LIMIT :limit")
    fun getBatchItemsInState(batchId: Long, state: String, limit: Int): List<ImportQueueItemDbEntity>

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

    @Query(
        "SELECT COUNT(*) FROM import_queue_batches " +
                "WHERE library_root_url=:libraryRootUrl " +
                "AND id IN (SELECT batch_id FROM import_queue_items WHERE state=:pendingState)"
    )
    fun countBatchesWithPendingItems(libraryRootUrl: String, pendingState: String): Int

    @Query("SELECT state, COUNT(*) AS count, batch_id FROM import_queue_items WHERE batch_id=:batchId GROUP BY state")
    fun getBatchStateCounts(batchId: Long): List<ImportQueueStateCount>

    @Query("SELECT COUNT(*) FROM import_queue_items WHERE state IN (:states)")
    fun countItemsInStates(states: List<String>): Int

    @Query("UPDATE import_queue_items SET state=:toState, error=NULL WHERE state IN (:fromStates)")
    fun changeStates(fromStates: List<String>, toState: String)

    @Query("UPDATE import_queue_items SET state=:toState, error=NULL WHERE batch_id=:batchId AND state IN (:fromStates)")
    fun changeBatchStates(batchId: Long, fromStates: List<String>, toState: String)

    @Query("DELETE FROM import_queue_items WHERE batch_id=:batchId")
    fun deleteBatchItems(batchId: Long)

    @Query("DELETE FROM import_queue_batches WHERE id=:batchId")
    fun deleteBatch(batchId: Long)
}
