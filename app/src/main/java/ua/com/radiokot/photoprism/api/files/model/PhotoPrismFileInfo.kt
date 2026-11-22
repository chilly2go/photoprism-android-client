package ua.com.radiokot.photoprism.api.files.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The part of a library file which the app needs.
 */
data class PhotoPrismFileInfo
@JsonCreator
constructor(
    @JsonProperty("PhotoUID")
    val photoUid: String?,
)
