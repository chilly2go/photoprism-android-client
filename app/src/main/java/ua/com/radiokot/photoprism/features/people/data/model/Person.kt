package ua.com.radiokot.photoprism.features.people.data.model

import ua.com.radiokot.photoprism.api.faces.model.PhotoPrismFace
import ua.com.radiokot.photoprism.api.subjects.model.PhotoPrismSubject
import ua.com.radiokot.photoprism.features.people.data.model.Person.Companion.isFaceId
import ua.com.radiokot.photoprism.features.people.data.model.Person.Companion.isSubjectUid

/**
 * A person recognized by the library.
 * It is a single entity for two underlying sources: [PhotoPrismSubject] and [PhotoPrismFace]
 *
 * @see id
 * @see isFace
 */
class Person(
    val name: String?,
    /**
     * Subject UID or face ID.
     *
     * @see isFace
     * @see isFaceId
     * @see isSubjectUid
     */
    val id: String,
    val isFavorite: Boolean,
    /**
     * Whether this is a known person (subject) or just a face.
     */
    private val isFace: Boolean,
    val photoCount: Int,
    val thumbnailHash: String,
    /**
     * For a face, the marker representing it, naming the face is naming this marker.
     * For a person, the marker of the cover.
     */
    val markerUid: String? = null,
    /**
     * Whether the person is hidden in the library, hidden people are only loaded on request.
     */
    val isHidden: Boolean = false,
) {
    val hasName: Boolean = name != null

    /**
     * Whether this is an unnamed face cluster, not a known person.
     */
    val isFaceCluster: Boolean
        get() = isFace

    constructor(
        personSubject: PhotoPrismSubject,
    ) : this(
        name = personSubject.name.takeIf(String::isNotEmpty),
        id = personSubject.uid,
        isFavorite = personSubject.favorite,
        isFace = false,
        photoCount = personSubject.photoCount,
        thumbnailHash = personSubject.thumb,
        markerUid = personSubject.markerUid?.takeIf(String::isNotEmpty),
        isHidden = personSubject.hidden == true,
    ) {
        require(personSubject.type == "person") {
            "Expected person subject"
        }
    }

    constructor(
        face: PhotoPrismFace,
    ) : this(
        name = face.name.takeIf(String::isNotEmpty),
        id = face.id,
        isFavorite = false,
        isFace = true,
        photoCount = face.samples,
        thumbnailHash = face.thumb,
        markerUid = face.markerUid?.takeIf(String::isNotEmpty),
        isHidden = face.hidden == true,
    ) {
        require(face.thumb.isNotEmpty()) {
            "The face must have a thumb, make sure it is requested with markers"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Person) return false

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "Person(name=$name, uid='$id', isFace=$isFace)"
    }

    companion object {
        private const val SUBJECT_UID_LENGTH = 16
        private const val FACE_ID_LENGTH = 32

        fun isSubjectUid(personId: String): Boolean =
            personId.length == SUBJECT_UID_LENGTH

        fun isFaceId(personId: String): Boolean =
            personId.length == FACE_ID_LENGTH
    }
}
