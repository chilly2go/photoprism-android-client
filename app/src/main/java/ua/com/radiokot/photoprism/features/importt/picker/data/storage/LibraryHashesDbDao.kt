package ua.com.radiokot.photoprism.features.importt.picker.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LibraryHashDbEntity

@Dao
interface LibraryHashesDbDao {
    @Query("SELECT * FROM library_hashes WHERE library_root_url=:libraryRootUrl")
    fun getAll(libraryRootUrl: String): List<LibraryHashDbEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(hashes: List<LibraryHashDbEntity>)

    @Query("DELETE FROM library_hashes")
    fun deleteAll()
}
