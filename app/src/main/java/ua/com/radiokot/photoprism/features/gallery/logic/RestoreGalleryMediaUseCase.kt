package ua.com.radiokot.photoprism.features.gallery.logic

import io.reactivex.rxjava3.core.Completable
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository

class RestoreGalleryMediaUseCase {

    /**
     * Brings archived items back to the library.
     *
     * @param currentGalleryMediaRepository repository currently used to present the gallery,
     * which will be updated on successful restoring.
     */
    operator fun invoke(
        mediaUids: Collection<String>,
        currentGalleryMediaRepository: SimpleGalleryMediaRepository,
    ): Completable =
        currentGalleryMediaRepository
            .restore(
                itemUids = mediaUids
            )

    /**
     * @param currentGalleryMediaRepository repository currently used to present the gallery,
     * which will be updated on successful restoring.
     */
    operator fun invoke(
        mediaUid: String,
        currentGalleryMediaRepository: SimpleGalleryMediaRepository,
    ) = invoke(
        setOf(mediaUid),
        currentGalleryMediaRepository,
    )
}
