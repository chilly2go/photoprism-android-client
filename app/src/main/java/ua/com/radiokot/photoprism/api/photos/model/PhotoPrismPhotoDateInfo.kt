package ua.com.radiokot.photoprism.api.photos.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Where the library got the date of a photo from.
 */
data class PhotoPrismPhotoDateInfo
@JsonCreator
constructor(
    /**
     * Source of the date: "meta" for the file metadata, "name" for the file name,
     * "manual" if set by hand, empty if the library had to use the file time,
     * which for an upload is the time of the upload.
     */
    @JsonProperty("TakenSrc")
    val takenSrc: String?,
)
