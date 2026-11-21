package ua.com.radiokot.photoprism.env.data.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import ua.com.radiokot.photoprism.api.config.model.PhotoPrismClientConfig

/**
 * What the current account may do in the library,
 * from its role permissions and the features turned on in the library settings.
 */
data class SessionCapabilities
@JsonCreator
constructor(
    @JsonProperty("canSetFavorite")
    val canSetFavorite: Boolean,
    @JsonProperty("canSetPrivate")
    val canSetPrivate: Boolean,
    @JsonProperty("canArchive")
    val canArchive: Boolean,
    @JsonProperty("canDelete")
    val canDelete: Boolean,
    @JsonProperty("canUpdateAlbums")
    val canUpdateAlbums: Boolean,
    @JsonProperty("canCreateAlbums")
    val canCreateAlbums: Boolean,
    @JsonProperty("canUpload")
    val canUpload: Boolean,
    @JsonProperty("canEditPhotos")
    val canEditPhotos: Boolean,
    @JsonProperty("canEditLabels")
    val canEditLabels: Boolean,
    @JsonProperty("canReview")
    val canReview: Boolean,
    @JsonProperty("canSeePeople")
    val canSeePeople: Boolean,
    @JsonProperty("canEditPeople")
    val canEditPeople: Boolean,
    /**
     * Naming a face is changing its marker, which is a file permission.
     */
    @JsonProperty("canEditFaces")
    val canEditFaces: Boolean,
    @JsonProperty("canShareAlbums")
    val canShareAlbums: Boolean,
    /**
     * Whether this is what the library said,
     * not an assumption made before it could be asked.
     */
    @JsonProperty("isFromLibrary")
    val isFromLibrary: Boolean,
) {
    companion object {
        /**
         * What the app assumes before asking the library:
         * the actions it always offered stay available, the newer ones are hidden.
         */
        val ASSUMED = SessionCapabilities(
            canSetFavorite = true,
            canSetPrivate = true,
            canArchive = true,
            canDelete = true,
            canUpdateAlbums = true,
            canCreateAlbums = true,
            canUpload = true,
            canEditPhotos = false,
            canEditLabels = false,
            canReview = false,
            canSeePeople = false,
            canEditPeople = false,
            canEditFaces = false,
            canShareAlbums = false,
            isFromLibrary = false,
        )

        fun fromConfig(config: PhotoPrismClientConfig): SessionCapabilities {
            val acl = config.acl
                // Libraries too old to tell.
                ?: return ASSUMED
            // A library which doesn't list its features has them all on.
            val features = config.settings?.features
            val disabled = config.disable ?: emptyMap()

            fun allows(resource: String, permission: String): Boolean {
                // Like Grant.Allow on the server.
                val grant = acl[resource]
                    ?: return false
                return grant[permission]
                    ?: grant[FULL_ACCESS]
                    ?: false
            }

            fun feature(name: String): Boolean =
                features?.get(name) ?: true

            val canUpdatePhotos = allows("photos", "update")
            val canDeletePhotos = allows("photos", "delete")
            val canSeePeople = allows("people", "view")
                    && feature("people")
                    && disabled["faces"] != true
            val canEditPeople = canSeePeople && allows("people", "update")

            return SessionCapabilities(
                canSetFavorite = canUpdatePhotos,
                canSetPrivate = canUpdatePhotos && feature("private"),
                canArchive = canDeletePhotos && feature("archive"),
                canDelete = canDeletePhotos && feature("delete"),
                canUpdateAlbums = allows("albums", "update"),
                canCreateAlbums = allows("albums", "create"),
                canUpload = (allows("files", "upload") || allows("files", "manage"))
                        && feature("upload")
                        && feature("import"),
                canEditPhotos = canUpdatePhotos && feature("edit"),
                canEditLabels = canUpdatePhotos && feature("labels"),
                canReview = canUpdatePhotos && feature("review"),
                canSeePeople = canSeePeople,
                canEditPeople = canEditPeople,
                canEditFaces = canEditPeople && allows("files", "update"),
                canShareAlbums = allows("albums", "share") && feature("share"),
                isFromLibrary = true,
            )
        }

        private const val FULL_ACCESS = "full_access"
    }
}
