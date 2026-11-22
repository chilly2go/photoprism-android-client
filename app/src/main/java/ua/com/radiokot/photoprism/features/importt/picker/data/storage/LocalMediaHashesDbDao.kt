package ua.com.radiokot.photoprism.features.importt.picker.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LocalMediaHashDbEntity

@Dao
interface LocalMediaHashesDbDao {
    @Query("SELECT * FROM local_media_hashes")
    fun getAll(): List<LocalMediaHashDbEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(hashes: List<LocalMediaHashDbEntity>)

    @Query("DELETE FROM local_media_hashes WHERE media_id IN (:mediaIds)")
    fun delete(mediaIds: List<Long>)
}
