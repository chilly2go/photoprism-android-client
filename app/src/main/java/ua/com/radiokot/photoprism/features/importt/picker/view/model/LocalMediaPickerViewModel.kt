package ua.com.radiokot.photoprism.features.importt.picker.view.model

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.importt.picker.data.model.LocalMedia
import ua.com.radiokot.photoprism.features.importt.picker.logic.LibraryHashChecker
import ua.com.radiokot.photoprism.features.importt.picker.logic.LocalMediaHasher
import ua.com.radiokot.photoprism.features.importt.picker.logic.LocalMediaReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Picks local media to import, hiding what is already in the library.
 *
 * Local files are hashed (with the hashes cached) and the hashes are checked
 * against the library in bundles, the selected folder first.
 */
class LocalMediaPickerViewModel(
    private val reader: LocalMediaReader,
    private val hasher: LocalMediaHasher,
    private val libraryHashChecker: LibraryHashChecker,
) : ViewModel() {
    private val log = kLogger("LocalMediaPickerVM")

    private val lock = Any()
    private var allMedia: List<LocalMedia> = emptyList()
    private val statusById = mutableMapOf<Long, Status>()
    private val selectedIds = linkedSetOf<Long>()
    private var selectedBucketId: String? = null
    private var hideInLibrary = true
    private var isLibraryUnavailable = false

    @Volatile
    private var isCleared = false
    private var isLoaded = false

    private val _items = MutableLiveData<List<Item>>(emptyList())
    val items: LiveData<List<Item>> = _items
    private val _buckets = MutableLiveData<List<Bucket>>(emptyList())
    val buckets: LiveData<List<Bucket>> = _buckets
    private val _selectedBucket = MutableLiveData<Bucket?>(null)

    /**
     * The folder to show the media from, or null for all of them.
     */
    val selectedBucket: LiveData<Bucket?> = _selectedBucket
    private val _checkProgress = MutableLiveData<CheckProgress?>(null)
    val checkProgress: LiveData<CheckProgress?> = _checkProgress
    private val _selectedCount = MutableLiveData(0)
    val selectedCount: LiveData<Int> = _selectedCount
    val isHidingInLibrary = MutableLiveData(true)
    private val _isPermissionMissing = MutableLiveData(false)
    val isPermissionMissing: LiveData<Boolean> = _isPermissionMissing
    private val eventsSubject = PublishSubject.create<Event>()
    val events = eventsSubject.observeOnMain()

    val permissionsToRequest: Array<String>
        get() = reader.permissionsToRequest

    fun onPermissionsChecked() {
        if (!reader.canRead()) {
            _isPermissionMissing.value = true
            return
        }

        _isPermissionMissing.value = false

        if (isLoaded) {
            return
        }
        isLoaded = true

        thread(name = "LocalMediaPicker", isDaemon = true) {
            try {
                loadAndCheck()
            } catch (error: Exception) {
                log.error(error) { "onPermissionsChecked(): loading_failed" }
            }
        }
    }

    private fun loadAndCheck() {
        val media = reader.readAll()

        synchronized(lock) {
            allMedia = media
            media.forEach { statusById[it.id] = Status.UNKNOWN }
        }

        postState()

        val hashExecutor = Executors.newFixedThreadPool(HASHING_THREADS)
        try {
            while (!isCleared) {
                val bundle = getNextBundle()
                    ?: break
                checkBundle(bundle, hashExecutor)
                postState()
            }
        } finally {
            hashExecutor.shutdownNow()
        }

        log.debug { "loadAndCheck(): done" }
    }

    /**
     * Must be called under the [lock].
     */
    private fun getBuckets(): List<Bucket> =
        allMedia
            .filter { it.bucketId != null }
            .groupBy { it.bucketId!! }
            .map { (bucketId, bucketMedia) ->
                // Media is sorted from the newest.
                val newest = bucketMedia.first()
                val isFullyChecked = bucketMedia.none { statusById[it.id] == Status.UNKNOWN }
                Bucket(
                    id = bucketId,
                    name = newest.bucketName ?: bucketId,
                    path = newest.bucketPath
                        ?.trim('/')
                        ?.takeIf(String::isNotEmpty),
                    count = bucketMedia.size,
                    missingCount =
                        if (isFullyChecked && !isLibraryUnavailable)
                            bucketMedia.count { statusById[it.id] != Status.IN_LIBRARY }
                        else
                            null,
                    isOnSdCard = newest.isOnSdCard,
                    coverUri = newest.uri,
                )
            }
            .sortedByDescending(Bucket::count)

    private fun getNextBundle(): List<LocalMedia>? = synchronized(lock) {
        val unknown = allMedia.filter { statusById[it.id] == Status.UNKNOWN }
        if (unknown.isEmpty()) {
            return@synchronized null
        }

        val bucketId = selectedBucketId
        val preferred =
            if (bucketId != null)
                unknown.filter { it.bucketId == bucketId }
            else
                emptyList()

        (preferred + unknown).distinct().take(BUNDLE_SIZE)
    }

    private fun checkBundle(
        bundle: List<LocalMedia>,
        hashExecutor: java.util.concurrent.ExecutorService,
    ) {
        val newHashes = mutableMapOf<LocalMedia, String>()
        val hashes: Map<LocalMedia, String?> = hashExecutor
            .invokeAll(bundle.map { media ->
                Callable {
                    media to (hasher.getCached(media)
                        ?: try {
                            hasher.calculate(media).also { sha1 ->
                                synchronized(newHashes) { newHashes[media] = sha1 }
                            }
                        } catch (error: Exception) {
                            log.warn(error) {
                                "checkBundle(): hashing_failed:" +
                                        "\nmedia=${media.displayName}"
                            }
                            null
                        })
                }
            })
            .associate { it.get() }

        hasher.save(newHashes)

        val hashesInLibrary: Set<String>? =
            if (isLibraryUnavailable)
                null
            else
                try {
                    libraryHashChecker.getHashesInLibrary(hashes.values.filterNotNull().toSet())
                } catch (error: Exception) {
                    log.warn(error) { "checkBundle(): library_check_failed" }
                    isLibraryUnavailable = true
                    null
                }

        synchronized(lock) {
            hashes.forEach { (media, sha1) ->
                statusById[media.id] = when {
                    sha1 == null || hashesInLibrary == null ->
                        Status.NOT_CHECKED

                    sha1 in hashesInLibrary ->
                        Status.IN_LIBRARY

                    else ->
                        Status.MISSING
                }
            }
        }
    }

    private fun postState() {
        val items: List<Item>
        val progress: CheckProgress
        val selectedCount: Int
        val buckets: List<Bucket>

        synchronized(lock) {
            buckets = getBuckets()
            // Keep the selection only for media which can be seen.
            items = allMedia
                .asSequence()
                .filter { selectedBucketId == null || it.bucketId == selectedBucketId }
                .mapNotNull { media ->
                    val status = statusById[media.id] ?: Status.UNKNOWN
                    if (hideInLibrary && (status == Status.IN_LIBRARY || status == Status.UNKNOWN)) {
                        selectedIds.remove(media.id)
                        null
                    } else {
                        Item(
                            media = media,
                            status = status,
                            isSelected = media.id in selectedIds,
                        )
                    }
                }
                .toList()

            val scope = allMedia.filter { selectedBucketId == null || it.bucketId == selectedBucketId }
            progress = CheckProgress(
                checked = scope.count { statusById[it.id] != Status.UNKNOWN },
                total = scope.size,
                inLibrary = scope.count { statusById[it.id] == Status.IN_LIBRARY },
                isLibraryUnavailable = isLibraryUnavailable,
            )
            selectedCount = selectedIds.size
        }

        _buckets.postValue(buckets)
        _selectedBucket.postValue(buckets.find { it.id == selectedBucketId })
        _items.postValue(items)
        _checkProgress.postValue(progress)
        _selectedCount.postValue(selectedCount)
    }

    fun onBucketSelected(bucketId: String?) {
        synchronized(lock) {
            selectedBucketId = bucketId
        }
        postState()
    }

    fun onHideInLibraryChanged(hide: Boolean) {
        if (hide == hideInLibrary) {
            return
        }
        synchronized(lock) {
            hideInLibrary = hide
        }
        isHidingInLibrary.value = hide
        postState()
    }

    fun onItemClicked(item: Item) {
        synchronized(lock) {
            if (!selectedIds.remove(item.media.id)) {
                selectedIds += item.media.id
            }
        }
        postState()
    }

    fun onSelectAllClicked() {
        synchronized(lock) {
            _items.value?.mapTo(selectedIds) { it.media.id }
        }
        postState()
    }

    fun onClearSelectionClicked() {
        synchronized(lock) {
            selectedIds.clear()
        }
        postState()
    }

    fun onContinueClicked() {
        val files = synchronized(lock) {
            allMedia
                .filter { it.id in selectedIds }
                .map { media ->
                    ImportableFile(
                        contentUri = media.contentUri.toString(),
                        displayName = media.displayName,
                        mimeType = media.mimeType,
                        reportedSize = media.size.takeIf { it > 0 },
                    )
                }
        }

        if (files.isNotEmpty()) {
            eventsSubject.onNext(Event.OpenImport(files))
        }
    }

    override fun onCleared() {
        isCleared = true
    }

    enum class Status {
        /**
         * Not checked yet.
         */
        UNKNOWN,

        /**
         * Couldn't be hashed or checked.
         */
        NOT_CHECKED,
        IN_LIBRARY,
        MISSING,
    }

    data class Item(
        val media: LocalMedia,
        val status: Status,
        val isSelected: Boolean,
    )

    data class Bucket(
        val id: String,
        val name: String,
        /**
         * Path of the folder on its storage, like DCIM/Camera.
         */
        val path: String?,
        val count: Int,
        /**
         * Count of media which is not in the library yet,
         * or null if it is not known yet.
         */
        val missingCount: Int?,
        val isOnSdCard: Boolean,
        val coverUri: Uri,
    )

    data class CheckProgress(
        val checked: Int,
        val total: Int,
        val inLibrary: Int,
        val isLibraryUnavailable: Boolean,
    ) {
        val isDone: Boolean
            get() = checked >= total
    }

    sealed interface Event {
        class OpenImport(val files: List<ImportableFile>) : Event
    }

    private companion object {
        private const val BUNDLE_SIZE = 100
        private const val HASHING_THREADS = 2
    }
}
