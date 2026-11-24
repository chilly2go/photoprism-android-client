package ua.com.radiokot.photoprism.features.viewer.people.logic

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.extension.toSingle
import ua.com.radiokot.photoprism.features.viewer.people.data.model.PhotoFace
import ua.com.radiokot.photoprism.features.viewer.people.data.model.PhotoFaces

/**
 * Loads the faces of the file the photo previews are made of:
 * the one with the photo hash, otherwise the primary one.
 * The merged search results have no markers, hence the separate request.
 */
class GetPhotoFacesUseCase(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
) {
    operator fun invoke(
        photoUid: String,
        previewHash: String?,
    ): Single<PhotoFaces> = {
        val photo = photoPrismPhotosService.getPhoto(photoUid)
        val files = photo.files.orEmpty()
        val file = files.find { previewHash != null && it.hash == previewHash }
            ?: files.find { it.primary == true }

        PhotoFaces(
            photoUid = photoUid,
            fileUid = file?.uid?.takeIf { !file.hash.isNullOrEmpty() },
            faces = file
                ?.markers
                .orEmpty()
                .mapNotNull { marker ->
                    PhotoFace.fromMarker(marker, file!!.uid)
                }
                // Left to right, the way people are usually named.
                .sortedBy { it.area.left },
        )
    }
        .toSingle()
        .subscribeOn(Schedulers.io())
}
