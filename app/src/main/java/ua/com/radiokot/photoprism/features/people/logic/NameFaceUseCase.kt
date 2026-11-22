package ua.com.radiokot.photoprism.features.people.logic

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.markers.model.PhotoPrismMarkerUpdate
import ua.com.radiokot.photoprism.api.markers.service.PhotoPrismMarkersService
import ua.com.radiokot.photoprism.extension.toSingle

/**
 * Names a face cluster the way the web does: by naming the marker representing it.
 * The cluster and its automatically matched faces follow.
 * A name somebody already has assigns the face to that person.
 *
 * Requires the files update permission.
 */
class NameFaceUseCase(
    private val photoPrismMarkersService: PhotoPrismMarkersService,
) {
    /**
     * @return UID of the person the face is now assigned to, if the server tells.
     */
    operator fun invoke(
        markerUid: String,
        name: String,
    ): Single<String> = {
        photoPrismMarkersService.updateMarker(
            uid = markerUid,
            update = PhotoPrismMarkerUpdate(
                name = name.trim(),
                subjectSource = PhotoPrismMarkerUpdate.SUBJECT_SOURCE_MANUAL,
            ),
        ).subjUid ?: ""
    }
        .toSingle()
        .subscribeOn(Schedulers.io())
}
