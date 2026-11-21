package ua.com.radiokot.photoprism.api.config.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class PhotoPrismClientConfig
@JsonCreator
constructor(
    @JsonProperty("downloadToken")
    val downloadToken: String,
    @JsonProperty("previewToken")
    val previewToken: String,
    @JsonProperty("public")
    val public: Boolean,
    @JsonProperty("years")
    val years: List<Int>?,
    @JsonProperty("version")
    val version: String? = null,
    @JsonProperty("edition")
    val edition: String? = null,
    /**
     * What the session may do, by resource ("photos", "albums", "people", …)
     * and then by permission ("view", "update", "delete", "full_access", …).
     */
    @JsonProperty("acl")
    val acl: Map<String, Map<String, Boolean>>? = null,
    @JsonProperty("settings")
    val settings: Settings? = null,
    /**
     * Features turned off on the server, like "faces" or "classification".
     */
    @JsonProperty("disable")
    val disable: Map<String, Boolean>? = null,
) {
    data class Settings
    @JsonCreator
    constructor(
        /**
         * Features turned on or off in the library settings,
         * like "archive", "delete", "edit", "people", "upload".
         */
        @JsonProperty("features")
        val features: Map<String, Boolean>?,
    )
}
