package ua.com.radiokot.photoprism.features.gallery.logic

import io.reactivex.rxjava3.core.Completable
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository

class ApproveGalleryMediaUseCase {

    /**
     * Takes items of low quality out of review, so they count as fine.
     * Archived ones are restored too.
     *
     * @param currentGalleryMediaRepository repository currently used to present the gallery,
     * which will be updated on successful approval.
     */
    operator fun invoke(
        mediaUids: Collection<String>,
        currentGalleryMediaRepository: SimpleGalleryMediaRepository,
    ): Completable =
        currentGalleryMediaRepository
            .approve(
                itemUids = mediaUids
            )

    /**
     * @param currentGalleryMediaRepository repository currently used to present the gallery,
     * which will be updated on successful approval.
     */
    operator fun invoke(
        mediaUid: String,
        currentGalleryMediaRepository: SimpleGalleryMediaRepository,
    ) = invoke(
        setOf(mediaUid),
        currentGalleryMediaRepository,
    )
}
