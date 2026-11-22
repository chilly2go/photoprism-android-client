package ua.com.radiokot.photoprism.api.faces.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The server reads a missing [hidden] as false,
 * so it is always sent.
 */
class PhotoPrismFaceUpdate(
    @JsonProperty("Hidden")
    val hidden: Boolean,
)
