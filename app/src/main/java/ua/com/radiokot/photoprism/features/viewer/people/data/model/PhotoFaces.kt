package ua.com.radiokot.photoprism.features.viewer.people.data.model

/**
 * Faces of the file a photo is shown by.
 *
 * @param fileUid the file new faces are to be marked in, null if the photo has none suitable.
 */
class PhotoFaces(
    val photoUid: String,
    val fileUid: String?,
    val faces: List<PhotoFace>,
)
