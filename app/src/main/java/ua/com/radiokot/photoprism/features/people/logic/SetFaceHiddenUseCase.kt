package ua.com.radiokot.photoprism.features.people.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.faces.model.PhotoPrismFaceUpdate
import ua.com.radiokot.photoprism.api.faces.service.PhotoPrismFacesService
import ua.com.radiokot.photoprism.extension.toSingle

/**
 * Hides an unnamed face cluster from People > New, or shows it again.
 */
class SetFaceHiddenUseCase(
    private val photoPrismFacesService: PhotoPrismFacesService,
) {
    operator fun invoke(
        faceId: String,
        isHidden: Boolean,
    ): Completable = {
        photoPrismFacesService.updateFace(
            id = faceId,
            update = PhotoPrismFaceUpdate(
                hidden = isHidden,
            ),
        )
    }
        .toSingle()
        .ignoreElement()
        .subscribeOn(Schedulers.io())
}
