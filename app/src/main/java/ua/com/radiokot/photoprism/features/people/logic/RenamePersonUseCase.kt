package ua.com.radiokot.photoprism.features.people.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.subjects.model.PhotoPrismSubjectUpdate
import ua.com.radiokot.photoprism.api.subjects.service.PhotoPrismSubjectsService
import ua.com.radiokot.photoprism.extension.toSingle

/**
 * Renames a person for the whole library.
 * If another person has this name, the server merges the two into that one,
 * so ask before.
 */
class RenamePersonUseCase(
    private val photoPrismSubjectsService: PhotoPrismSubjectsService,
) {
    operator fun invoke(
        personUid: String,
        newName: String,
    ): Completable = {
        photoPrismSubjectsService.updateSubject(
            uid = personUid,
            update = PhotoPrismSubjectUpdate(
                name = newName.trim(),
            ),
        )
    }
        .toSingle()
        .ignoreElement()
        .subscribeOn(Schedulers.io())
}
