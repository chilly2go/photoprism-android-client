package ua.com.radiokot.photoprism.features.importt.queue.logic

import android.content.ContentResolver
import androidx.core.net.toUri
import okhttp3.MultipartBody
import retrofit2.HttpException
import ua.com.radiokot.photoprism.api.files.service.PhotoPrismFilesService
import ua.com.radiokot.photoprism.api.session.service.PhotoPrismSessionService
import ua.com.radiokot.photoprism.api.upload.model.PhotoPrismUploadOptions
import ua.com.radiokot.photoprism.api.upload.service.PhotoPrismUploadService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.importt.model.ImportableFileRequestBody
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemDbEntity
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemState
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueRepository
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Uploads the queued files of the library batch by batch.
 *
 * Each batch is processed in chunks. For each file of a chunk, its SHA-1 is calculated
 * and the library is checked for it, so files which are already there are not uploaded.
 * The rest is uploaded with a token of the chunk, then the chunk import is started
 * and each uploaded file is checked to be in the library.
 * A failed file doesn't fail the rest.
 * If the library can't be reached, [LibraryUnreachableException] is thrown
 * and the unfinished files stay in the queue.
 *
 * All the work is blocking.
 */
class ImportQueueProcessor(
    private val repository: ImportQueueRepository,
    private val contentResolver: ContentResolver,
    private val sessionService: PhotoPrismSessionService,
    private val uploadService: PhotoPrismUploadService,
    private val filesService: PhotoPrismFilesService,
    private val libraryRootUrl: String,
    private val isStopped: () -> Boolean,
    private val onProgress: (Progress) -> Unit,
) {
    private val log = kLogger("ImportQueueProcessor")

    private var batchNumber = 0
    private val unreachableError = AtomicReference<LibraryUnreachableException?>(null)
    private var batchCount = 0
    private var importedCount = 0
    private var alreadyInLibraryCount = 0
    private var failedCount = 0

    fun run(): Result {
        val userId = withRetries {
            sessionService.getCurrentSession().user.uid
        }

        while (!isStopped()) {
            val batch = repository.getNextBatch(libraryRootUrl)
                ?: break

            batchNumber++
            batchCount = batchNumber - 1 + repository.countBatchesWithPendingItems(libraryRootUrl)

            log.debug {
                "run(): processing_batch:" +
                        "\nbatchId=${batch.id}," +
                        "\nbatchNumber=$batchNumber," +
                        "\nbatchCount=$batchCount"
            }

            val albums = repository.getAlbums(batch)

            // Pending items are taken chunk by chunk,
            // so cancellation and retries made meanwhile are respected.
            while (!isStopped()) {
                val chunk = repository.getPendingItems(batch.id, limit = CHUNK_SIZE)
                if (chunk.isEmpty()) {
                    break
                }

                processChunk(
                    batchId = batch.id,
                    items = chunk,
                    albums = albums,
                    userId = userId,
                )
            }
        }

        return Result(
            importedCount = importedCount,
            alreadyInLibraryCount = alreadyInLibraryCount,
            failedCount = failedCount,
        ).also {
            log.debug {
                "run(): done:" +
                        "\nresult=$it," +
                        "\nisStopped=${isStopped()}"
            }
        }
    }

    private fun processChunk(
        batchId: Long,
        items: List<ImportQueueItemDbEntity>,
        albums: Set<DestinationAlbum>,
        userId: String,
    ) {
        val uploadToken = "${System.currentTimeMillis()}"

        log.debug {
            "processChunk(): processing:" +
                    "\nbatchId=$batchId," +
                    "\nitems=${items.size}," +
                    "\nuploadToken=$uploadToken"
        }

        repository.updateItems(items.map { it.copy(state = ImportQueueItemState.UPLOADING.name) })
        reportProgress(batchId, Phase.UPLOADING)

        // Check and upload the files in parallel.
        val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS)
        val uploadedItems: List<ImportQueueItemDbEntity>
        try {
            uploadedItems = executor
                .invokeAll(items.map { item ->
                    Callable {
                        checkAndUploadItem(
                            item = item,
                            userId = userId,
                            uploadToken = uploadToken,
                        ).also { updatedItem ->
                            repository.updateItems(listOf(updatedItem))
                            reportProgress(batchId, Phase.UPLOADING)
                        }
                    }
                })
                .map { future ->
                    try {
                        future.get()
                    } catch (error: ExecutionException) {
                        throw error.cause ?: error
                    }
                }
                .filter { it.itemState == ImportQueueItemState.UPLOADED }
        } finally {
            executor.shutdownNow()
        }

        // Unfinished items of the chunk are put back to the queue by the caller.
        unreachableError.get()?.also { throw it }

        if (uploadedItems.isEmpty() || isStopped()) {
            return
        }

        reportProgress(batchId, Phase.IMPORTING)

        val importOutcome = processUpload(
            userId = userId,
            uploadToken = uploadToken,
            albums = albums,
        )

        log.debug {
            "processChunk(): upload_processed:" +
                    "\nuploadToken=$uploadToken," +
                    "\noutcome=$importOutcome"
        }

        confirmImport(
            items = uploadedItems,
            outcome = importOutcome,
        )

        reportProgress(batchId, Phase.UPLOADING)
    }

    /**
     * @return the [item] in its new state: [ImportQueueItemState.ALREADY_IN_LIBRARY],
     * [ImportQueueItemState.UPLOADED] or [ImportQueueItemState.FAILED].
     */
    private fun checkAndUploadItem(
        item: ImportQueueItemDbEntity,
        userId: String,
        uploadToken: String,
    ): ImportQueueItemDbEntity {
        // Once the library is unreachable, don't spend time on the rest of the chunk.
        unreachableError.get()?.also { throw it }

        val sha1 = item.sha1
            ?: try {
                calculateSha1(item)
            } catch (error: Exception) {
                log.warn(error) {
                    "checkAndUploadItem(): failed_reading_file:" +
                            "\nitem=${item.displayName}"
                }

                failed()
                return item.copy(
                    state = ImportQueueItemState.FAILED.name,
                    error = "Can't read the file: ${error.message ?: error.javaClass.simpleName}",
                )
            }

        val itemWithHash = item.copy(sha1 = sha1)

        if (isInLibrary(sha1) == true) {
            alreadyInLibrary()
            return itemWithHash.copy(
                state = ImportQueueItemState.ALREADY_IN_LIBRARY.name,
                error = null,
            )
        }

        return try {
            withRetries(times = MAX_UPLOAD_RETRIES) {
                uploadService.uploadUserFiles(
                    userId = userId,
                    uploadToken = uploadToken,
                    files = listOf(
                        MultipartBody.Part.createFormData(
                            name = PhotoPrismUploadService.UPLOAD_FILES_PART_NAME,
                            filename = item.displayName,
                            body = ImportableFileRequestBody(
                                importableFile = item.toImportableFile(),
                                contentResolver = contentResolver,
                                onReadingProgress = {},
                            ),
                        )
                    ),
                )
            }

            itemWithHash.copy(
                state = ImportQueueItemState.UPLOADED.name,
                error = null,
            )
        } catch (error: Exception) {
            // The file has just been read for the hash, so an I/O error here
            // is most likely a network one. Unless the library still responds,
            // in which case the problem is with this file.
            if (error.isConnectionError() && !isLibraryResponding(sha1)) {
                throw markUnreachable(error)
            }

            log.warn(error) {
                "checkAndUploadItem(): upload_failed:" +
                        "\nitem=${item.displayName}"
            }

            failed()
            itemWithHash.copy(
                state = ImportQueueItemState.FAILED.name,
                error = "Upload failed: ${error.describe()}",
            )
        }
    }

    private fun processUpload(
        userId: String,
        uploadToken: String,
        albums: Set<DestinationAlbum>,
    ): ImportOutcome {
        var busyAttempts = 0

        while (true) {
            try {
                uploadService.processUserUpload(
                    userId = userId,
                    uploadToken = uploadToken,
                    uploadOptions = PhotoPrismUploadOptions(
                        albums = albums.map { album ->
                            when (album) {
                                is DestinationAlbum.Existing ->
                                    album.uid

                                is DestinationAlbum.ToCreate ->
                                    album.title
                            }
                        },
                    ),
                )

                return ImportOutcome.Done
            } catch (error: HttpException) {
                when (val code = error.code()) {
                    // Another import or indexing is running,
                    // the staged files are kept for another request.
                    HttpURLConnection.HTTP_UNAVAILABLE -> {
                        if (busyAttempts++ >= MAX_BUSY_RETRIES || isStopped()) {
                            return ImportOutcome.Unknown
                        }
                        log.debug {
                            "processUpload(): library_busy_waiting:" +
                                    "\nattempt=$busyAttempts"
                        }
                        sleep(BUSY_RETRY_DELAY_MS)
                    }

                    // No staged files means they have already been processed,
                    // for example, by a previous request which timed out.
                    HttpURLConnection.HTTP_NOT_FOUND,
                    in 500..599 ->
                        return ImportOutcome.Unknown

                    else ->
                        return ImportOutcome.Rejected("Import rejected by the library (HTTP $code)")
                }
            } catch (error: IOException) {
                // The connection may be cut by a proxy while the library keeps importing.
                // Repeating the request would not help, the result is to be checked.
                return ImportOutcome.Unknown
            }
        }
    }

    private fun confirmImport(
        items: List<ImportQueueItemDbEntity>,
        outcome: ImportOutcome,
    ) {
        val (rounds, delayMs) = when (outcome) {
            ImportOutcome.Done ->
                CONFIRM_ROUNDS_AFTER_DONE to CONFIRM_DELAY_AFTER_DONE_MS

            ImportOutcome.Unknown ->
                CONFIRM_ROUNDS_AFTER_UNKNOWN to CONFIRM_DELAY_AFTER_UNKNOWN_MS

            is ImportOutcome.Rejected ->
                1 to 0L
        }

        var unconfirmed = items
        for (round in 1..rounds) {
            val confirmed = unconfirmed.filter { isInLibrary(it.sha1!!) == true }
            repository.updateItems(confirmed.map { it.copy(state = ImportQueueItemState.IMPORTED.name) })
            repeat(confirmed.size) { imported() }

            unconfirmed = unconfirmed - confirmed.toSet()
            if (unconfirmed.isEmpty() || round == rounds || isStopped()) {
                break
            }

            log.debug {
                "confirmImport(): waiting_for_unconfirmed:" +
                        "\nunconfirmed=${unconfirmed.size}," +
                        "\nround=$round"
            }

            sleep(delayMs)
        }

        if (isStopped()) {
            // The rest will be uploaded again.
            return
        }

        val error = (outcome as? ImportOutcome.Rejected)?.message
            ?: "Not found in the library after the import"
        repository.updateItems(unconfirmed.map {
            it.copy(
                state = ImportQueueItemState.FAILED.name,
                error = error,
            )
        })
        repeat(unconfirmed.size) { failed() }
    }

    /**
     * @return true if the library has a file with the [sha1],
     * false if not, null if it couldn't be checked.
     */
    private fun isInLibrary(sha1: String): Boolean? =
        try {
            withRetries(times = MAX_CHECK_RETRIES) {
                filesService.getFile(sha1)
            }
            true
        } catch (error: HttpException) {
            if (error.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                false
            } else {
                log.warn(error) { "isInLibrary(): check_failed" }
                null
            }
        } catch (error: Exception) {
            if (error.isConnectionError()) {
                throw markUnreachable(error)
            }
            log.warn(error) { "isInLibrary(): check_failed" }
            null
        }

    private fun isLibraryResponding(sha1: String): Boolean =
        try {
            filesService.getFile(sha1)
            true
        } catch (error: HttpException) {
            true
        } catch (error: Exception) {
            false
        }

    private fun markUnreachable(error: Exception): LibraryUnreachableException {
        val unreachable = LibraryUnreachableException(error)
        unreachableError.compareAndSet(null, unreachable)
        return unreachableError.get()!!
    }

    /**
     * @return true if the error means that the library can't be reached at all,
     * as opposed to an error specific to a file.
     */
    private fun Throwable.isConnectionError(): Boolean =
        when (this) {
            // Interruption is expected when the work is stopped.
            is InterruptedIOException ->
                !isStopped()

            // Retrofit HttpException is not an IOException,
            // so these are failures to get any response.
            is IOException ->
                true

            else ->
                cause?.isConnectionError() == true
        }

    private fun calculateSha1(item: ImportQueueItemDbEntity): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val input = contentResolver.openInputStream(item.contentUri.toUri())
            ?: throw IOException("Can't open input stream")

        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun <T> withRetries(
        times: Int = MAX_UPLOAD_RETRIES,
        block: () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: Exception) {
                if (attempt >= times || !error.isRetriable() || isStopped()) {
                    throw error
                }
                attempt++
                sleep(RETRY_DELAY_MS)
            }
        }
    }

    private fun Exception.isRetriable(): Boolean = when (this) {
        is InterruptedIOException ->
            !isStopped()

        is IOException ->
            true

        is HttpException ->
            code() == 408 || code() == 429 || code() in 500..599

        else ->
            false
    }

    private fun Exception.describe(): String = when (this) {
        is HttpException ->
            "HTTP ${code()}"

        else ->
            message ?: javaClass.simpleName
    }

    private fun sleep(ms: Long) {
        // Sleep in small steps to stop quickly.
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && !isStopped()) {
            Thread.sleep(500)
        }
    }

    private fun reportProgress(batchId: Long, phase: Phase) {
        val (finished, total) = repository.getBatchProgress(batchId)
        onProgress(
            Progress(
                batchNumber = batchNumber,
                batchCount = batchCount,
                finishedInBatch = finished,
                totalInBatch = total,
                phase = phase,
            )
        )
    }

    @Synchronized
    private fun imported() {
        importedCount++
    }

    @Synchronized
    private fun alreadyInLibrary() {
        alreadyInLibraryCount++
    }

    @Synchronized
    private fun failed() {
        failedCount++
    }

    private fun ImportQueueItemDbEntity.toImportableFile() = ImportableFile(
        contentUri = contentUri,
        displayName = displayName,
        mimeType = mimeType,
        reportedSize = reportedSize,
    )

    class LibraryUnreachableException(cause: Throwable) :
        IOException("The library can't be reached: ${cause.message ?: cause.javaClass.simpleName}", cause)

    private sealed interface ImportOutcome {
        object Done : ImportOutcome {
            override fun toString() = "Done"
        }

        /**
         * The import may have been done or may be still running.
         */
        object Unknown : ImportOutcome {
            override fun toString() = "Unknown"
        }

        data class Rejected(val message: String) : ImportOutcome
    }

    enum class Phase {
        UPLOADING,
        IMPORTING,
    }

    data class Progress(
        val batchNumber: Int,
        val batchCount: Int,
        val finishedInBatch: Int,
        val totalInBatch: Int,
        val phase: Phase,
    )

    data class Result(
        val importedCount: Int,
        val alreadyInLibraryCount: Int,
        val failedCount: Int,
    )

    private companion object {
        private const val CHUNK_SIZE = 20
        private const val MAX_CONCURRENT_UPLOADS = 4
        private const val MAX_UPLOAD_RETRIES = 3
        private const val MAX_CHECK_RETRIES = 2
        private const val RETRY_DELAY_MS = 10_000L
        private const val MAX_BUSY_RETRIES = 20
        private const val BUSY_RETRY_DELAY_MS = 30_000L
        private const val CONFIRM_ROUNDS_AFTER_DONE = 3
        private const val CONFIRM_DELAY_AFTER_DONE_MS = 5_000L
        private const val CONFIRM_ROUNDS_AFTER_UNKNOWN = 16
        private const val CONFIRM_DELAY_AFTER_UNKNOWN_MS = 15_000L
    }
}
