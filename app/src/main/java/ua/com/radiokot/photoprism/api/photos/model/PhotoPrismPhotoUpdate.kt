package ua.com.radiokot.photoprism.api.photos.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
class PhotoPrismPhotoUpdate(
    @JsonProperty("Favorite")
    val favorite: Boolean? = null,
    @JsonProperty("Private")
    val private: Boolean? = null,
    /**
     * UTC time, like "2020-10-06T20:03:26Z".
     */
    @JsonProperty("TakenAt")
    val takenAt: String? = null,
    /**
     * Local time in the [timeZone], written as if it was UTC, like "2020-10-06T22:03:26Z".
     */
    @JsonProperty("TakenAtLocal")
    val takenAtLocal: String? = null,
    /**
     * IANA time zone name, like "Europe/Berlin".
     */
    @JsonProperty("TimeZone")
    val timeZone: String? = null,
    @JsonProperty("TakenSrc")
    val takenSrc: String? = null,
    @JsonProperty("Year")
    val year: Int? = null,
    @JsonProperty("Month")
    val month: Int? = null,
    @JsonProperty("Day")
    val day: Int? = null,
)
