package ua.com.radiokot.photoprism.api.faces.service

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import ua.com.radiokot.photoprism.api.faces.model.PhotoPrismFace
import ua.com.radiokot.photoprism.api.faces.model.PhotoPrismFaceUpdate
import java.io.IOException

interface PhotoPrismFacesService {
    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @GET("v1/faces")
    fun getFaces(
        @Query("count")
        count: Int,
        @Query("offset")
        offset: Int,
        @Query("unknown")
        unknown: Boolean,
        @Query("markers")
        markers: Boolean,
        @Query("q")
        q: String? = null,
        /**
         * "samples" for the biggest clusters first, like People > New does.
         */
        @Query("order")
        order: String? = null,
        /**
         * "yes" to include hidden faces, which are left out by default.
         */
        @Query("hidden")
        hidden: String? = null,
    ): List<PhotoPrismFace>

    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @PUT("v1/faces/{id}")
    fun updateFace(
        @Path("id") id: String,
        @Body update: PhotoPrismFaceUpdate,
    ): Any // There must be some non-void return type because of Retrofit assertions.
}
