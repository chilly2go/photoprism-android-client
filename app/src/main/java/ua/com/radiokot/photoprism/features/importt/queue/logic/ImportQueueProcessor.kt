package ua.com.radiokot.photoprism.features.importt.queue.logic

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import okhttp3.MultipartBody
import retrofit2.HttpException
import ua.com.radiokot.photoprism.api.files.service.PhotoPrismFilesService
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.api.session.service.PhotoPrismSessionService
import ua.com.radiokot.photoprism.api.upload.model.PhotoPrismUploadOptions
import ua.com.radiokot.photoprism.api.upload.service.PhotoPrismUploadService
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.importt.model.ImportableFileRequestBody
import ua.com.radiokot.photoprism.features.importt.picker.data.storage.LibraryHashesRepository
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueFailureReason
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
 * First, imports which were interrupted after the upload are finished,
 * so their files are not uploaded again.
 * Then each batch is processed in chunks. For each file of a chunk, its SHA-1 is calculated
 * and the library is checked for it, so files which are already there are not uploaded.
 * The rest is uploaded with a token of the chunk, then the chunk import is started
 * and each uploaded file is checked to be in the library.
 * A failed file doesn't fail the rest.
 *
 * If the library can't be reached, [LibraryUnreachableException] is thrown
 * and the unfinished files stay in the queue.
 * If the account is not allowed to upload, [NotAllowedException] is thrown
 * and the files waiting to be uploaded are failed.
 * Once stopped, uploads are cut short and the unfinished files stay in the queue.
 *
 * All the work is blocking.
 */
class ImportQueueProcessor(
    private val repository: ImportQueueRepository,
    private val contentResolver: ContentResolver,
    private val sessionService: PhotoPrismSessionService,
    private val uploadService: PhotoPrismUploadService,
    private val filesService: PhotoPrismFilesService,
    private val photosService: PhotoPrismPhotosService,
    private val libraryHashesRepository: LibraryHashesRepository,
    private val uploadProgress: ImportQueueUploadProgress,
    private val libraryRootUrl: String,
    private val canReadOriginals: () -> Boolean,
    private val canUpdatePhotos: () -> Boolean,
    private val isStopped: () -> Boolean,
    private val onProgress: (Progress) -> Unit,
) {
    private val log = kLogger("ImportQueueProcessor")

    private val chunkError = AtomicReference<Exception?>(null)
    private var importedCount = 0
    private var alreadyInLibraryCount = 0
    private var failedCount = 0

    fun run(): Result {
        val userId = withRetries {
            sessionService.getCurrentSession().user.uid
        }

        finishInterruptedImports(userId)

        while (!isStopped()) {
            val batch = repository.getNextBatch(libraryRootUrl)
                ?: break

            log.debug {
                "run(): processing_batch:" +
                        "\nbatchId=${batch.id}"
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

    /**
     * Finishes imports of the files which were uploaded
     * but whose import was interrupted, for example, by a lost connection.
     */
    private fun finishInterruptedImports(userId: String) {
        val itemsByUpload = repository.getUnfinishedImports(libraryRootUrl)
            .groupBy { it.batchId to it.uploadToken!! }

        for ((batchAndToken, items) in itemsByUpload) {
            if (isStopped()) {
                return
            }

            val (batchId, uploadToken) = batchAndToken

            log.debug {
                "finishInterruptedImports(): finishing:" +
                        "\nbatchId=$batchId," +
                        "\nuploadToken=$uploadToken," +
                        "\nitems=${items.size}"
            }

            finishImport(
                items = items,
                userId = userId,
                uploadToken = uploadToken,
                albums = repository.getBatch(batchId)
                    ?.let(repository::getAlbums)
                    ?: emptySet(),
                // If the upload is gone from the server and the files are not in the library,
                // it is safe to upload them again.
                isInterrupted = true,
            )
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

        // Items are marked as being uploaded only once they are actually taken,
        // so the rest of the chunk is seen as waiting.
        // Check and upload the files in parallel.
        val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS)
        val uploadedItems = mutableListOf<ImportQueueItemDbEntity>()
        try {
            executor
                .invokeAll(items.map { item ->
                    Callable {
                        // Removed or stopped meanwhile, or the chunk has already failed.
                        if (isStopped() || chunkError.get() != null || !repository.startItem(item.id)) {
                            return@Callable null
                        }

                        uploadProgress.set(item.id, ImportQueueUploadProgress.CHECKING)
                        try {
                            checkAndUploadItem(
                                item = item.copy(state = ImportQueueItemState.UPLOADING.name),
                                userId = userId,
                                uploadToken = uploadToken,
                            ).also { updatedItem ->
                                repository.updateItems(listOf(updatedItem))
                                if (updatedItem.itemState == ImportQueueItemState.ALREADY_IN_LIBRARY) {
                                    libraryHashesRepository.remember(
                                        libraryRootUrl = libraryRootUrl,
                                        sha1s = listOfNotNull(updatedItem.sha1),
                                    )
                                }
                                reportProgress(Phase.UPLOADING)
                            }
                        } catch (error: Exception) {
                            // The whole chunk is to stop, the item goes back to the queue.
                            chunkError.compareAndSet(null, error)
                            repository.updateItems(
                                listOf(
                                    item.copy(
                                        state = ImportQueueItemState.PENDING.name,
                                        uploadToken = null,
                                    )
                                )
                            )
                            null
                        } finally {
                            uploadProgress.clear(item.id)
                        }
                    }
                })
                // Collect all the results first, so none is lost.
                .forEach { future ->
                    val item = try {
                        future.get()
                    } catch (error: ExecutionException) {
                        chunkError.compareAndSet(null, (error.cause as? Exception) ?: error)
                        null
                    }
                    if (item?.itemState == ImportQueueItemState.UPLOADED) {
                        uploadedItems += item
                    }
                }
        } finally {
            executor.shutdownNow()
        }

        val error = chunkError.getAndSet(null)

        if (error is NotAllowedException) {
            failed()
            repository.failPendingItemsAsNotAllowed(
                libraryRootUrl = libraryRootUrl,
                error = error.message ?: "Not allowed",
            )
        }

        // The uploaded files are imported even if the rest of the chunk has failed,
        // unless the library can't be reached or the work is stopped.
        // Then they keep the upload token and their import is finished next time.
        if (uploadedItems.isNotEmpty() && !isStopped() && error !is LibraryUnreachableException) {
            finishImport(
                items = uploadedItems,
                userId = userId,
                uploadToken = uploadToken,
                albums = albums,
                isInterrupted = false,
            )
        }

        if (error != null) {
            throw error
        }
    }

    /**
     * @return the [item] in its new state: [ImportQueueItemState.ALREADY_IN_LIBRARY],
     * [ImportQueueItemState.UPLOADED], [ImportQueueItemState.FAILED],
     * or [ImportQueueItemState.PENDING] if the work has been stopped meanwhile.
     */
    private fun checkAndUploadItem(
        item: ImportQueueItemDbEntity,
        userId: String,
        uploadToken: String,
    ): ImportQueueItemDbEntity {
        val readableUri = getReadableUri(item)

        val sha1 = item.sha1
            ?: try {
                calculateSha1(readableUri)
            } catch (error: Exception) {
                if (isStopped()) {
                    return item.copy(state = ImportQueueItemState.PENDING.name)
                }

                log.warn(error) {
                    "checkAndUploadItem(): failed_reading_file:" +
                            "\nitem=${item.displayName}"
                }

                failed()
                return item.copy(
                    state = ImportQueueItemState.FAILED.name,
                    error = "Can't read the file: ${error.message ?: error.javaClass.simpleName}",
                    failureReason = ImportQueueFailureReason.CANT_READ.name,
                )
            }

        val itemWithHash = item.copy(sha1 = sha1)

        if (isInLibrary(sha1) == true || isRedactedCopyInLibrary(item, readableUri, sha1)) {
            alreadyInLibrary()
            return itemWithHash.copy(
                state = ImportQueueItemState.ALREADY_IN_LIBRARY.name,
                error = null,
            )
        }

        uploadProgress.set(item.id, ImportQueueUploadProgress.UPLOADING_UNKNOWN_PERCENT)

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
                                importableFile = item.toImportableFile(readableUri),
                                contentResolver = contentResolver,
                                onReadingProgress = { bytesRead ->
                                    // Cut the upload short once stopped.
                                    if (isStopped()) {
                                        throw InterruptedIOException("The work is stopped")
                                    }
                                    reportUploadProgress(item, bytesRead)
                                },
                            ),
                        )
                    ),
                )
            }

            itemWithHash.copy(
                state = ImportQueueItemState.UPLOADED.name,
                error = null,
                uploadToken = uploadToken,
            )
        } catch (error: Exception) {
            if (isStopped()) {
                return itemWithHash.copy(state = ImportQueueItemState.PENDING.name)
            }

            if (error.isNotAllowed()) {
                throw NotAllowedException(error)
            }

            // The file has just been read for the hash, so an I/O error here
            // is most likely a network one. Unless the library still responds,
            // in which case the problem is with this file.
            if (error.isConnectionError() && !isLibraryResponding(sha1)) {
                throw LibraryUnreachableException(error)
            }

            log.warn(error) {
                "checkAndUploadItem(): upload_failed:" +
                        "\nitem=${item.displayName}"
            }

            failed()
            itemWithHash.copy(
                state = ImportQueueItemState.FAILED.name,
                error = "Upload failed: ${error.describe()}",
                failureReason =
                    if ((error as? HttpException)?.code() == HTTP_ENTITY_TOO_LARGE)
                        ImportQueueFailureReason.TOO_LARGE.name
                    else
                        ImportQueueFailureReason.UPLOAD_FAILED.name,
            )
        }
    }

    /**
     * Asks the library to add the uploaded [items] and checks that they are there.
     *
     * @param isInterrupted whether this is a retry of an interrupted import,
     * in which case the files are uploaded again if the upload is gone from the server.
     */
    private fun finishImport(
        items: List<ImportQueueItemDbEntity>,
        userId: String,
        uploadToken: String,
        albums: Set<DestinationAlbum>,
        isInterrupted: Boolean,
    ) {
        val importingItems = items.map { it.copy(state = ImportQueueItemState.IMPORTING.name) }
        repository.updateItems(importingItems)
        reportProgress(Phase.IMPORTING)

        val importOutcome = processUpload(
            userId = userId,
            uploadToken = uploadToken,
            albums = albums,
        )

        log.debug {
            "finishImport(): upload_processed:" +
                    "\nuploadToken=$uploadToken," +
                    "\noutcome=$importOutcome"
        }

        confirmImport(
            items = importingItems,
            outcome = importOutcome,
            isInterrupted = isInterrupted,
        )

        reportProgress(Phase.UPLOADING)
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
                    // for example, by a previous request which timed out,
                    // or that they are gone.
                    HttpURLConnection.HTTP_NOT_FOUND ->
                        return ImportOutcome.NothingStaged

                    in 500..599 ->
                        return ImportOutcome.Unknown

                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN ->
                        return ImportOutcome.Rejected(
                            message = "Not allowed to add files to the library (HTTP $code)",
                            reason = ImportQueueFailureReason.NOT_ALLOWED,
                        )

                    else ->
                        return ImportOutcome.Rejected(
                            message = "Import rejected by the library (HTTP $code)",
                            reason = ImportQueueFailureReason.IMPORT_REJECTED,
                        )
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
        isInterrupted: Boolean,
    ) {
        val (rounds, delayMs) = when (outcome) {
            ImportOutcome.Done ->
                CONFIRM_ROUNDS_AFTER_DONE to CONFIRM_DELAY_AFTER_DONE_MS

            ImportOutcome.Unknown ->
                CONFIRM_ROUNDS_AFTER_UNKNOWN to CONFIRM_DELAY_AFTER_UNKNOWN_MS

            // After an interruption, the files are likely there already.
            // Otherwise, the import may have been done by a request which timed out.
            ImportOutcome.NothingStaged ->
                if (isInterrupted)
                    1 to 0L
                else
                    CONFIRM_ROUNDS_AFTER_UNKNOWN to CONFIRM_DELAY_AFTER_UNKNOWN_MS

            is ImportOutcome.Rejected ->
                1 to 0L
        }

        var unconfirmed = items
        for (round in 1..rounds) {
            val confirmed = unconfirmed.filter { isInLibrary(it.sha1!!) == true }
            repository.updateItems(confirmed.map {
                it.copy(
                    state = ImportQueueItemState.IMPORTED.name,
                    uploadToken = null,
                )
            })
            libraryHashesRepository.remember(
                libraryRootUrl = libraryRootUrl,
                sha1s = confirmed.mapNotNull(ImportQueueItemDbEntity::sha1),
            )
            repeat(confirmed.size) { imported() }
            confirmed.forEach(::setTakenAtIfUnknown)

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
            // The upload token is kept, the import is finished next time.
            repository.updateItems(unconfirmed.map {
                it.copy(state = ImportQueueItemState.UPLOADED.name)
            })
            return
        }

        if (isInterrupted && outcome == ImportOutcome.NothingStaged) {
            // The upload is gone and the files are not in the library,
            // so they are uploaded again.
            repository.updateItems(unconfirmed.map {
                it.copy(
                    state = ImportQueueItemState.PENDING.name,
                    uploadToken = null,
                )
            })
            return
        }

        val (error, failureReason) = when (outcome) {
            is ImportOutcome.Rejected ->
                outcome.message to outcome.reason

            else ->
                "Not found in the library after the import" to
                        ImportQueueFailureReason.NOT_FOUND_AFTER_IMPORT
        }
        repository.updateItems(unconfirmed.map {
            it.copy(
                state = ImportQueueItemState.FAILED.name,
                error = error,
                failureReason = failureReason.name,
                uploadToken = null,
            )
        })
        repeat(unconfirmed.size) { failed() }
    }

    /**
     * Sets the date the file was taken, as known on the device, to its photo
     * if the library couldn't tell it neither from the file nor from its name.
     * Otherwise, the library would use the time of the upload.
     * A failure is only logged, the import is done anyway.
     */
    private fun setTakenAtIfUnknown(item: ImportQueueItemDbEntity) {
        val takenAtMs = item.takenAtMs
            ?: return
        val sha1 = item.sha1
            ?: return

        if (!canUpdatePhotos()) {
            log.debug {
                "setTakenAtIfUnknown(): skip_as_not_allowed:" +
                        "\nitem=${item.displayName}"
            }
            return
        }

        try {
            val photoUid = filesService.getFile(sha1).photoUid
                ?: return
            val takenSrc = photosService.getPhotoDateInfo(photoUid).takenSrc
            if (!takenSrc.isNullOrEmpty()) {
                return
            }

            val update = ImportQueueTakenAt.toPhotoUpdate(takenAtMs)
            photosService.updatePhoto(
                photoUid = photoUid,
                update = update,
            )

            log.debug {
                "setTakenAtIfUnknown(): set:" +
                        "\nitem=${item.displayName}," +
                        "\nphotoUid=$photoUid," +
                        "\ntakenAtLocal=${update.takenAtLocal}," +
                        "\ntimeZone=${update.timeZone}"
            }
        } catch (error: Exception) {
            log.warn(error) {
                "setTakenAtIfUnknown(): failed:" +
                        "\nitem=${item.displayName}"
            }
        }
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
                throw LibraryUnreachableException(error)
            }
            log.warn(error) { "isInLibrary(): check_failed" }
            null
        }

    /**
     * Android zeroes the location data when a file is read without asking for the original,
     * keeping the size. The upstream app and sharing uploaded such copies.
     * Their hash differs from the original, which would be uploaded again as a duplicate.
     *
     * @param readableUri the URI the [sha1] is calculated from
     *
     * @return true if the [item] is an image read as the original
     * and its copy with the location data redacted is in the library.
     */
    private fun isRedactedCopyInLibrary(
        item: ImportQueueItemDbEntity,
        readableUri: Uri,
        sha1: String,
    ): Boolean {
        if (readableUri.getQueryParameter(REQUIRE_ORIGINAL_PARAMETER) == null
            || item.mimeType?.startsWith("image/") != true
        ) {
            return false
        }

        val redactedUri = readableUri.buildUpon()
            .clearQuery()
            .apply {
                readableUri.queryParameterNames
                    .filter { it != REQUIRE_ORIGINAL_PARAMETER }
                    .forEach { name ->
                        readableUri.getQueryParameters(name).forEach { value ->
                            appendQueryParameter(name, value)
                        }
                    }
            }
            .build()

        val redactedSha1 = try {
            calculateSha1(redactedUri)
        } catch (error: Exception) {
            if (isStopped()) {
                throw error
            }
            log.debug {
                "isRedactedCopyInLibrary(): failed_reading_redacted:" +
                        "\nitem=${item.displayName}," +
                        "\nerror=$error"
            }
            return false
        }

        return (redactedSha1 != sha1 && isInLibrary(redactedSha1) == true).also { isFound ->
            if (isFound) {
                log.debug {
                    "isRedactedCopyInLibrary(): found_redacted_copy:" +
                            "\nitem=${item.displayName}"
                }
            }
        }
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

    private fun Throwable.isNotAllowed(): Boolean =
        (this as? HttpException)?.code()
            .let { it == HttpURLConnection.HTTP_UNAUTHORIZED || it == HttpURLConnection.HTTP_FORBIDDEN }

    /**
     * @return URI to read the item content from: the original one with the location data
     * if it is a device media file and the app may read the originals.
     * The hash is calculated from the same content which is uploaded,
     * otherwise the library check would be useless.
     */
    private fun getReadableUri(item: ImportQueueItemDbEntity): Uri {
        val uri = item.contentUri.toUri()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            || uri.authority != MediaStore.AUTHORITY
            || uri.getQueryParameter(REQUIRE_ORIGINAL_PARAMETER) != null
            || !canReadOriginals()
        ) {
            return uri
        }

        val originalUri = MediaStore.setRequireOriginal(uri)
        return try {
            contentResolver.openInputStream(originalUri)?.close()
                ?: return uri
            originalUri
        } catch (error: Exception) {
            // Not allowed for this file, for example, when shared by another app.
            log.debug {
                "getReadableUri(): original_not_readable:" +
                        "\nitem=${item.displayName}," +
                        "\nerror=$error"
            }
            uri
        }
    }

    private fun calculateSha1(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Can't open input stream")

        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (isStopped()) {
                    throw InterruptedIOException("The work is stopped")
                }
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

    private fun reportUploadProgress(item: ImportQueueItemDbEntity, bytesRead: Long) {
        val size = item.reportedSize
            ?.takeIf { it > 0 }
            ?: return
        // The reported size can't be trusted,
        // so 100% is only shown once the upload is done.
        uploadProgress.set(
            itemId = item.id,
            percent = (bytesRead * 100 / size).toInt().coerceIn(0, 99),
        )
    }

    private fun reportProgress(phase: Phase) {
        val (finished, total) = repository.getProgress(libraryRootUrl)
        onProgress(
            Progress(
                finishedCount = finished,
                totalCount = total,
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

    private fun ImportQueueItemDbEntity.toImportableFile(readableUri: Uri) = ImportableFile(
        contentUri = readableUri.toString(),
        displayName = displayName,
        mimeType = mimeType,
        reportedSize = reportedSize,
        takenAtMs = takenAtMs,
    )

    class LibraryUnreachableException(cause: Throwable) :
        IOException("The library can't be reached: ${cause.message ?: cause.javaClass.simpleName}", cause)

    class NotAllowedException(cause: Throwable) :
        IOException(
            "Not allowed to upload to the library: " +
                    ((cause as? HttpException)?.let { "HTTP ${it.code()}" } ?: cause.javaClass.simpleName),
            cause
        )

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

        /**
         * The server has no files for the upload token:
         * they have been imported already or are gone.
         */
        object NothingStaged : ImportOutcome {
            override fun toString() = "NothingStaged"
        }

        data class Rejected(
            val message: String,
            val reason: ImportQueueFailureReason,
        ) : ImportOutcome
    }

    enum class Phase {
        UPLOADING,
        IMPORTING,
    }

    data class Progress(
        /**
         * Finished items of all the batches of the library.
         */
        val finishedCount: Int,
        val totalCount: Int,
        val phase: Phase,
    )

    data class Result(
        val importedCount: Int,
        val alreadyInLibraryCount: Int,
        val failedCount: Int,
    )

    private companion object {
        private const val CHUNK_SIZE = 20
        private const val HTTP_ENTITY_TOO_LARGE = 413
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
        private const val REQUIRE_ORIGINAL_PARAMETER = "requireOriginal"
    }
}
