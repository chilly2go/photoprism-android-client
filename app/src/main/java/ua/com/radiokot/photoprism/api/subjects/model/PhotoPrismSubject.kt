package ua.com.radiokot.photoprism.api.subjects.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class PhotoPrismSubject
@JsonCreator
constructor(
    @JsonProperty("UID")
    val uid: String,
    @JsonProperty("Type")
    val type: String,
    @JsonProperty("Thumb")
    val thumb: String,
    @JsonProperty("Name")
    val name: String,
    @JsonProperty("Favorite")
    val favorite: Boolean,
    @JsonProperty("PhotoCount")
    val photoCount: Int,
    @JsonProperty("Hidden")
    val hidden: Boolean? = null,
    @JsonProperty("Private")
    val private: Boolean? = null,
    @JsonProperty("Excluded")
    val excluded: Boolean? = null,
    @JsonProperty("FileCount")
    val fileCount: Int? = null,
    @JsonProperty("MarkerUID")
    val markerUid: String? = null,
    @JsonProperty("Alias")
    val alias: String? = null,
)
