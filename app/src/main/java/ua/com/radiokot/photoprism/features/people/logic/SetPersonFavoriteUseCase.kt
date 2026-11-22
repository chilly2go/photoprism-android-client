package ua.com.radiokot.photoprism.features.people.logic

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.subjects.service.PhotoPrismSubjectsService
import ua.com.radiokot.photoprism.extension.toSingle

class SetPersonFavoriteUseCase(
    private val photoPrismSubjectsService: PhotoPrismSubjectsService,
) {
    operator fun invoke(
        personUid: String,
        isFavorite: Boolean,
    ): Completable = {
        if (isFavorite) {
            photoPrismSubjectsService.likeSubject(personUid)
        } else {
            photoPrismSubjectsService.dislikeSubject(personUid)
        }
    }
        .toSingle()
        .ignoreElement()
        .subscribeOn(Schedulers.io())
}
