package ua.com.radiokot.photoprism.api.markers.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A face somebody marks by hand.
 * The area is relative to the oriented image, x and y being its top left corner.
 * A name needs a subject source that may name people, like
 * [PhotoPrismMarkerUpdate.SUBJECT_SOURCE_MANUAL].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
class PhotoPrismMarkerCreation(
    @JsonProperty("FileUID")
    val fileUid: String,
    @JsonProperty("X")
    val x: Float,
    @JsonProperty("Y")
    val y: Float,
    @JsonProperty("W")
    val w: Float,
    @JsonProperty("H")
    val h: Float,
    @JsonProperty("Name")
    val name: String? = null,
    @JsonProperty("SubjSrc")
    val subjectSource: String? = null,
    @JsonProperty("Type")
    val type: String = "face",
    @JsonProperty("Src")
    val source: String = "manual",
)
