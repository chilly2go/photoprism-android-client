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
     * Uploaded, waiting for the import to be processed and confirmed.
     */
    UPLOADED,

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

    /**
     * Removed from the queue by the user before being uploaded.
     */
    CANCELLED,
    ;

    val isFinal: Boolean
        get() = this == IMPORTED
                || this == ALREADY_IN_LIBRARY
                || this == FAILED
                || this == CANCELLED
}
