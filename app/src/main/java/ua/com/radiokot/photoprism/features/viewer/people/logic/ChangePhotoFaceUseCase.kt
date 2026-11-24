package ua.com.radiokot.photoprism.features.viewer.people.logic

import android.graphics.RectF
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.markers.model.PhotoPrismMarkerCreation
import ua.com.radiokot.photoprism.api.markers.model.PhotoPrismMarkerUpdate
import ua.com.radiokot.photoprism.api.markers.service.PhotoPrismMarkersService
import ua.com.radiokot.photoprism.extension.toSingle

/**
 * Changes of a single face in a photo, the way the web does them.
 * All of them require the files update permission.
 *
 * Names are shared by the whole library: naming a face which belongs to an unnamed cluster
 * names the cluster too.
 */
class ChangePhotoFaceUseCase(
    private val photoPrismMarkersService: PhotoPrismMarkersService,
) {
    /**
     * Assigns the face to a person by name, a new name creates the person.
     */
    fun setName(markerUid: String, name: String): Completable =
        update(
            markerUid,
            PhotoPrismMarkerUpdate(
                name = name.trim(),
                subjectSource = PhotoPrismMarkerUpdate.SUBJECT_SOURCE_MANUAL,
            )
        )

    /**
     * Takes the name off this face only.
     */
    fun clearName(markerUid: String): Completable = {
        photoPrismMarkersService.clearMarkerSubject(markerUid)
    }
        .toSingle()
        .ignoreElement()
        .subscribeOn(Schedulers.io())

    /**
     * Marks the area as not a face, or a face again.
     * The name stays.
     */
    fun setNotAFace(markerUid: String, isNotAFace: Boolean): Completable =
        update(markerUid, PhotoPrismMarkerUpdate(invalid = isNotAFace))

    /**
     * Confirms the name the library guessed.
     */
    fun confirm(markerUid: String): Completable =
        update(markerUid, PhotoPrismMarkerUpdate(review = false))

    /**
     * Marks a face the detection missed.
     *
     * @param area relative to the oriented image
     */
    fun add(fileUid: String, area: RectF, name: String?): Completable = {
        val trimmedName = name?.trim()?.takeIf(String::isNotEmpty)
        val left = area.left.coerceIn(0f, MAX_START)
        val top = area.top.coerceIn(0f, MAX_START)
        photoPrismMarkersService.createMarker(
            PhotoPrismMarkerCreation(
                fileUid = fileUid,
                x = left,
                y = top,
                w = area.width().coerceIn(MIN_SIZE, 1f - left),
                h = area.height().coerceIn(MIN_SIZE, 1f - top),
                name = trimmedName,
                subjectSource = trimmedName?.let { PhotoPrismMarkerUpdate.SUBJECT_SOURCE_MANUAL },
            )
        )
    }
        .toSingle()
        .ignoreElement()
        .subscribeOn(Schedulers.io())

    private fun update(markerUid: String, update: PhotoPrismMarkerUpdate): Completable = {
        photoPrismMarkersService.updateMarker(markerUid, update)
    }
        .toSingle()
        .ignoreElement()
        .subscribeOn(Schedulers.io())

    private companion object {
        private const val MIN_SIZE = 0.01f
        private const val MAX_START = 1f - MIN_SIZE
    }
}
