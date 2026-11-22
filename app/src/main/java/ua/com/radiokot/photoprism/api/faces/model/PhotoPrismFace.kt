package ua.com.radiokot.photoprism.api.faces.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class PhotoPrismFace
@JsonCreator
constructor(
    @JsonProperty("ID")
    val id: String,
    @JsonProperty("Thumb")
    val thumb: String,
    @JsonProperty("Name")
    val name: String,
    @JsonProperty("Samples")
    val samples: Int,
    /**
     * The marker representing the face, only present when requested with markers.
     * Naming the face is naming this marker.
     */
    @JsonProperty("MarkerUID")
    val markerUid: String? = null,
    @JsonProperty("FileUID")
    val fileUid: String? = null,
    @JsonProperty("SubjUID")
    val subjUid: String? = null,
    @JsonProperty("Hidden")
    val hidden: Boolean? = null,
)
