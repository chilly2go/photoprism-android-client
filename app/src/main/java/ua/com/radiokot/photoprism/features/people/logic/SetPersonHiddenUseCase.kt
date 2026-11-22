package ua.com.radiokot.photoprism.features.people.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.subjects.model.PhotoPrismSubjectUpdate
import ua.com.radiokot.photoprism.api.subjects.service.PhotoPrismSubjectsService
import ua.com.radiokot.photoprism.extension.toSingle

/**
 * Hides a person from the people lists, or shows them again.
 */
class SetPersonHiddenUseCase(
    private val photoPrismSubjectsService: PhotoPrismSubjectsService,
) {
    operator fun invoke(
        personUid: String,
        isHidden: Boolean,
    ): Completable = {
        photoPrismSubjectsService.updateSubject(
            uid = personUid,
            update = PhotoPrismSubjectUpdate(
                hidden = isHidden,
            ),
        )
    }
        .toSingle()
        .ignoreElement()
        .subscribeOn(Schedulers.io())
}
