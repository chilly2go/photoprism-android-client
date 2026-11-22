package ua.com.radiokot.photoprism.features.importt.queue.data.model

enum class ImportQueueItemState {
    /**
     * Waiting to be uploaded.
     */
    PENDING,

    /**
     * Being checked or uploaded right now.
     */
    UPLOADING,

    /**
     * Uploaded, waiting for the rest of its chunk to be uploaded,
     * after which the library is asked to add the files.
     */
    UPLOADED,

    /**
     * Uploaded, the library is adding it right now
     * and the app is waiting to see it there.
     */
    IMPORTING,

    /**
     * Confirmed to be in the library after the import.
     */
    IMPORTED,

    /**
     * Found in the library before uploading, so not uploaded.
     */
    ALREADY_IN_LIBRARY,

    /**
     * Couldn't be uploaded or imported, see the error.
     */
    FAILED,
    ;

    val isFinal: Boolean
        get() = this == IMPORTED
                || this == ALREADY_IN_LIBRARY
                || this == FAILED

    /**
     * Whether the item is being uploaded or imported right now,
     * so it can't be removed.
     */
    val isInProgress: Boolean
        get() = this == UPLOADING
                || this == UPLOADED
                || this == IMPORTING

    companion object {
        /**
         * @return the state with the [name], or [FAILED] for an unknown one,
         * for example, a state written by another version of the app.
         */
        fun fromName(name: String): ImportQueueItemState =
            values().find { it.name == name } ?: FAILED
    }
}
