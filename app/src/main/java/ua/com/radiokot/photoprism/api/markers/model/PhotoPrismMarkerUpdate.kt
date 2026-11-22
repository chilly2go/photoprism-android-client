package ua.com.radiokot.photoprism.api.markers.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The server fills the rest from the marker.
 * Naming needs a subject source that may name people, like [SUBJECT_SOURCE_MANUAL],
 * otherwise the server answers 400.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
class PhotoPrismMarkerUpdate(
    @JsonProperty("Name")
    val name: String? = null,
    @JsonProperty("SubjSrc")
    val subjectSource: String? = null,
    @JsonProperty("Review")
    val review: Boolean? = null,
    @JsonProperty("Invalid")
    val invalid: Boolean? = null,
) {
    companion object {
        const val SUBJECT_SOURCE_MANUAL = "manual"
    }
}
