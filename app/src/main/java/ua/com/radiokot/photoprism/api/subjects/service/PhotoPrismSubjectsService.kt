package ua.com.radiokot.photoprism.api.subjects.service

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import ua.com.radiokot.photoprism.api.subjects.model.PhotoPrismSubject
import ua.com.radiokot.photoprism.api.subjects.model.PhotoPrismSubjectUpdate
import java.io.IOException

interface PhotoPrismSubjectsService {
    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @GET("v1/subjects")
    fun getSubjects(
        @Query("count")
        count: Int,
        @Query("offset")
        offset: Int,
        @Query("type")
        type: String,
        @Query("q")
        q: String? = null,
        /**
         * "yes" to include hidden subjects, which are left out by default.
         */
        @Query("hidden")
        hidden: String? = null,
    ): List<PhotoPrismSubject>

    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @PUT("v1/subjects/{uid}")
    fun updateSubject(
        @Path("uid") uid: String,
        @Body update: PhotoPrismSubjectUpdate,
    ): Any // There must be some non-void return type because of Retrofit assertions.

    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @POST("v1/subjects/{uid}/like")
    fun likeSubject(
        @Path("uid") uid: String,
    ): Any // There must be some non-void return type because of Retrofit assertions.

    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @DELETE("v1/subjects/{uid}/like")
    fun dislikeSubject(
        @Path("uid") uid: String,
    ): Any // There must be some non-void return type because of Retrofit assertions.
}
