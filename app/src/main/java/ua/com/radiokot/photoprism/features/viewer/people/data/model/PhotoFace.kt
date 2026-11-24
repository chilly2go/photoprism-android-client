package ua.com.radiokot.photoprism.features.viewer.people.data.model

import android.graphics.RectF
import ua.com.radiokot.photoprism.api.markers.model.PhotoPrismMarker

/**
 * A face in a photo, which is a face marker of its file.
 */
class PhotoFace(
    val markerUid: String,
    val fileUid: String,
    /**
     * Name of the person, null for an unnamed face.
     */
    val name: String?,
    /**
     * UID of the person, null for an unnamed face.
     */
    val personUid: String?,
    /**
     * Whether the name is only a guess of the library which is to be confirmed.
     */
    val isToReview: Boolean,
    /**
     * The face area relative to the oriented image (0..1),
     * the same image the previews show.
     */
    val area: RectF,
    /**
     * Thumbnail hash of the face crop, if any.
     */
    val thumbnailHash: String?,
) {
    val hasName: Boolean
        get() = name != null

    override fun equals(other: Any?): Boolean =
        other is PhotoFace
                && other.markerUid == markerUid
                && other.name == name
                && other.isToReview == isToReview
                && other.area == area

    override fun hashCode(): Int =
        markerUid.hashCode()

    override fun toString(): String =
        "PhotoFace(markerUid='$markerUid', hasName=$hasName, isToReview=$isToReview)"

    companion object {
        private const val TYPE_FACE = "face"

        /**
         * @return a face from a valid face marker having an area, null otherwise.
         */
        fun fromMarker(marker: PhotoPrismMarker, fileUid: String): PhotoFace? {
            if (marker.type != TYPE_FACE || marker.invalid == true) {
                return null
            }
            val x = marker.x ?: return null
            val y = marker.y ?: return null
            val w = marker.w?.takeIf { it > 0 } ?: return null
            val h = marker.h?.takeIf { it > 0 } ?: return null

            return PhotoFace(
                markerUid = marker.uid,
                fileUid = marker.fileUid ?: fileUid,
                name = marker.name?.takeIf(String::isNotBlank),
                personUid = marker.subjUid?.takeIf(String::isNotEmpty),
                isToReview = marker.review == true,
                area = RectF(x, y, x + w, y + h),
                thumbnailHash = marker.thumb?.takeIf(String::isNotEmpty),
            )
        }
    }
}
