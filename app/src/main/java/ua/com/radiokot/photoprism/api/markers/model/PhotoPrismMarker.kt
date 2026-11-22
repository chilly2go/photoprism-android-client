package ua.com.radiokot.photoprism.api.markers.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class PhotoPrismMarker
@JsonCreator
constructor(
    @JsonProperty("UID")
    val uid: String,
    @JsonProperty("FileUID")
    val fileUid: String? = null,
    @JsonProperty("Type")
    val type: String? = null,
    @JsonProperty("Name")
    val name: String? = null,
    @JsonProperty("SubjUID")
    val subjUid: String? = null,
    @JsonProperty("SubjSrc")
    val subjSrc: String? = null,
    @JsonProperty("FaceID")
    val faceId: String? = null,
    @JsonProperty("Review")
    val review: Boolean? = null,
    @JsonProperty("Invalid")
    val invalid: Boolean? = null,
    @JsonProperty("X")
    val x: Float? = null,
    @JsonProperty("Y")
    val y: Float? = null,
    @JsonProperty("W")
    val w: Float? = null,
    @JsonProperty("H")
    val h: Float? = null,
    @JsonProperty("Thumb")
    val thumb: String? = null,
)
