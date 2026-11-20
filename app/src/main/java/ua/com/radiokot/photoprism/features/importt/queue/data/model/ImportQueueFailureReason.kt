package ua.com.radiokot.photoprism.features.importt.queue.data.model

/**
 * Why a queue item has [ImportQueueItemState.FAILED],
 * to explain it to the user without technical details.
 */
enum class ImportQueueFailureReason {
    /**
     * The file can't be read on the device, it may have been deleted or moved.
     */
    CANT_READ,

    /**
     * The library refused the upload as too large (HTTP 413).
     */
    TOO_LARGE,

    /**
     * The upload failed for another reason.
     */
    UPLOAD_FAILED,

    /**
     * The library refused to import the uploaded files.
     */
    IMPORT_REJECTED,

    /**
     * The file was uploaded but didn't show up in the library after the import,
     * for example, because its format is not supported.
     */
    NOT_FOUND_AFTER_IMPORT,
    ;
}
