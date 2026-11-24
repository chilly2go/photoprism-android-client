package ua.com.radiokot.photoprism.api.photos.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import ua.com.radiokot.photoprism.api.markers.model.PhotoPrismMarker

/**
 * Details of a photo from GET photos/{uid}, so far only what the people in it need.
 * Unlike the merged search results, its files have their markers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PhotoPrismPhoto
@JsonCreator
constructor(
    @JsonProperty("UID")
    val uid: String,
    @JsonProperty("Files")
    val files: List<File>? = null,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class File
    @JsonCreator
    constructor(
        @JsonProperty("UID")
        val uid: String,
        @JsonProperty("Hash")
        val hash: String? = null,
        @JsonProperty("Primary")
        val primary: Boolean? = null,
        @JsonProperty("Width")
        val width: Int? = null,
        @JsonProperty("Height")
        val height: Int? = null,
        @JsonProperty("Orientation")
        val orientation: Int? = null,
        /**
         * Areas of the file, like faces, relative to the oriented image.
         */
        @JsonProperty("Markers")
        val markers: List<PhotoPrismMarker>? = null,
    )
}
