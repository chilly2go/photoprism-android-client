package ua.com.radiokot.photoprism.api.markers.service

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Headers
import retrofit2.http.PUT
import retrofit2.http.Path
import ua.com.radiokot.photoprism.api.markers.model.PhotoPrismMarker
import ua.com.radiokot.photoprism.api.markers.model.PhotoPrismMarkerUpdate
import java.io.IOException

/**
 * Markers are areas of a file, like faces.
 * Changing them requires the files update permission.
 */
interface PhotoPrismMarkersService {
    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @PUT("v1/markers/{uid}")
    fun updateMarker(
        @Path("uid") uid: String,
        @Body update: PhotoPrismMarkerUpdate,
    ): PhotoPrismMarker

    /**
     * Clears the subject of this marker only and takes it out of its face cluster.
     * The cluster and its other markers keep the subject.
     */
    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @DELETE("v1/markers/{uid}/subject")
    fun clearMarkerSubject(
        @Path("uid") uid: String,
    ): PhotoPrismMarker
}
