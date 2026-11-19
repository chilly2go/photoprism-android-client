package ua.com.radiokot.photoprism.api.files.service

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import java.io.IOException

interface PhotoPrismFilesService {
    /**
     * Finds a library file by its SHA-1 hash.
     * Throws [retrofit2.HttpException] with 404 code if there is no such file.
     *
     * @param hash SHA-1 hash of the file content, lowercase hex
     */
    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @GET("v1/files/{hash}")
    fun getFile(
        @Path("hash")
        hash: String,
    ): Any
}
