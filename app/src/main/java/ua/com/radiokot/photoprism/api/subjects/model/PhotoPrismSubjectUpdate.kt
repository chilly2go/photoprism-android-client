package ua.com.radiokot.photoprism.api.subjects.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Only the set fields are changed, the server keeps the rest.
 * Renaming to a name another person has merges the two.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
class PhotoPrismSubjectUpdate(
    @JsonProperty("Name")
    val name: String? = null,
    @JsonProperty("Favorite")
    val favorite: Boolean? = null,
    @JsonProperty("Hidden")
    val hidden: Boolean? = null,
)
