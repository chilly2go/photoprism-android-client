package ua.com.radiokot.photoprism.features.importt.picker.view.model

import android.net.Uri
import android.os.Build
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
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Picks local media to import, hiding what is already in the library.
 *
 * Local files are hashed (with the hashes cached) and the hashes are checked
 * against the library in bundles, the selected folder first.
 * What is known about the files from previous checks is shown right away,
 * and only re-checked in the background once in a while.
 *
 * The list is built in the background from a snapshot of the state,
 * so selecting many files at once by dragging stays smooth with thousands of them.
 */
class LocalMediaPickerViewModel(
    private val reader: LocalMediaReader,
    private val hasher: LocalMediaHasher,
    private val libraryHashChecker: LibraryHashChecker,
) : ViewModel() {
    private val log = kLogger("LocalMediaPickerVM")

    private val lock = Any()

    // Guarded by the lock.
    private var allMedia: List<LocalMedia> = emptyList()

    /**
     * Local midnight of the day each media of [allMedia] was taken, by index.
     */
    private var dayStarts: LongArray = LongArray(0)
    private val statusById = HashMap<Long, Status>()
    private var mediaToRecheck: List<LocalMedia> = emptyList()
    private val selectedIds = linkedSetOf<Long>()
    private var selectedBucketId: String? = null
    private var hideInLibrary = true
    private var dragSelectionBase: Set<Long>? = null
    private var dragSelectionSelects = true
    private var areBucketsOutdated = true
    private var lastBuckets: List<Bucket> = emptyList()

    @Volatile
    private var isLibraryUnavailable = false

    /**
     * Whether the hashes are of the original files.
     * Otherwise, the location data is redacted, so they never match the library.
     */
    @Volatile
    private var areOriginalsReadable = true

    @Volatile
    private var visibleMediaIds: List<Long> = emptyList()

    @Volatile
    private var isCleared = false

    /**
     * Set to stop the current check, as the media is to be loaded again.
     */
    @Volatile
    private var isReloadRequested = false
    private var isLoaded = false
    private val isChecking = AtomicBoolean(false)

    private val checkExecutor = Executors.newSingleThreadExecutor()
    private val hashExecutor = Executors.newFixedThreadPool(HASHING_THREADS)
    private val stateExecutor = Executors.newSingleThreadExecutor()
    private val isStateUpdateScheduled = AtomicBoolean(false)

    private val _items = MutableLiveData<List<ListItem>>(emptyList())

    /**
     * Media of the selected folder grouped by the day taken, from the newest.
     */
    val items: LiveData<List<ListItem>> = _items
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
    private val _isLocationAccessMissing = MutableLiveData(false)

    /**
     * Whether the app can't read the photo locations,
     * so it can't recognize the files which are in the library.
     */
    val isLocationAccessMissing: LiveData<Boolean> = _isLocationAccessMissing
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

        val canReadOriginals = reader.canReadOriginals()
        _isLocationAccessMissing.value = !canReadOriginals

        // Allowed later: read the originals, as the hashes differ.
        if (isLoaded && canReadOriginals == areOriginalsReadable) {
            return
        }
        if (isLoaded) {
            isReloadRequested = true
        }
        isLoaded = true

        checkExecutor.execute {
            try {
                loadAndCheck()
            } catch (error: Exception) {
                log.error(error) { "onPermissionsChecked(): loading_failed" }
            }
        }
    }

    /**
     * Picks up permissions changed in the system settings meanwhile.
     */
    fun onScreenResumed() {
        if (isLoaded) {
            onPermissionsChecked()
        }
    }

    private fun loadAndCheck() {
        isReloadRequested = false
        val isOriginal = reader.canReadOriginals()
        val media = reader.readAll()
        val known =
            try {
                libraryHashChecker.getKnown()
            } catch (error: Exception) {
                log.warn(error) { "loadAndCheck(): failed_getting_known" }
                emptyMap()
            }
        val now = System.currentTimeMillis()
        val toRecheck = mutableListOf<LocalMedia>()
        val calendar = Calendar.getInstance()

        hasher.forgetAllExcept(media.map(LocalMedia::id))

        synchronized(lock) {
            allMedia = media
            areOriginalsReadable = isOriginal
            dayStarts = LongArray(media.size) { index ->
                calendar.timeInMillis = media[index].dateTakenMs
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            statusById.clear()
            media.forEach { item ->
                val knownItem = hasher.getCached(item)?.let(known::get)
                statusById[item.id] = when {
                    knownItem == null ->
                        Status.UNKNOWN

                    knownItem.isInLibrary -> {
                        if (knownItem.checkedAtMs < now - IN_LIBRARY_RECHECK_INTERVAL_MS) {
                            toRecheck += item
                        }
                        Status.IN_LIBRARY
                    }

                    // Not asked about each time, but checked again
                    // as it may have been uploaded elsewhere meanwhile.
                    knownItem.checkedAtMs >= now - MISSING_RECHECK_INTERVAL_MS ->
                        Status.MISSING

                    else ->
                        Status.UNKNOWN
                }
            }
            mediaToRecheck = toRecheck
            areBucketsOutdated = true
        }

        log.debug {
            "loadAndCheck(): loaded:" +
                    "\nmedia=${media.size}," +
                    "\nisOriginal=$isOriginal," +
                    "\ntoRecheck=${toRecheck.size}"
        }

        scheduleStateUpdate()
        checkUnknown()
    }

    /**
     * Checks the media of unknown status bundle by bundle,
     * then re-checks what was in the library long ago.
     * Must be called on the [checkExecutor].
     */
    private fun checkUnknown() {
        if (!isChecking.compareAndSet(false, true)) {
            return
        }

        try {
            var failedBundlesInRow = 0
            while (!isCleared && !isReloadRequested) {
                val bundle = getNextBundle()
                    ?: break
                if (checkBundle(bundle, hashExecutor)) {
                    failedBundlesInRow = 0
                } else if (++failedBundlesInRow >= MAX_FAILED_BUNDLES_IN_ROW
                    // Nothing more to check, so the failure must be shown.
                    || getNextBundle() == null
                ) {
                    log.debug { "checkUnknown(): library_unavailable" }
                    isLibraryUnavailable = true
                }
                synchronized(lock) {
                    areBucketsOutdated = true
                }
                scheduleStateUpdate()
            }

            recheckKnownInLibrary()
        } finally {
            isChecking.set(false)
        }

        log.debug { "checkUnknown(): done" }
    }

    /**
     * Checks whether the files known to be in the library are still there,
     * as they may have been deleted meanwhile.
     * Their status is kept unless they are found missing.
     */
    private fun recheckKnownInLibrary() {
        val toRecheck = synchronized(lock) {
            mediaToRecheck.also { mediaToRecheck = emptyList() }
        }

        for (bundle in toRecheck.chunked(BUNDLE_SIZE)) {
            if (isCleared || isReloadRequested || isLibraryUnavailable) {
                return
            }

            // The hashes have been cached, otherwise the files wouldn't be here.
            val hashes = bundle.associateWith { hasher.getCached(it) }
            val hashesInLibrary = try {
                libraryHashChecker.getHashesInLibrary(hashes.values.filterNotNull().toSet())
            } catch (error: Exception) {
                log.warn(error) { "recheckKnownInLibrary(): check_failed" }
                return
            }

            val notFound = hashes.filterValues { it != null && it !in hashesInLibrary }.keys
            val missing = notFound - findRedactedCopiesInLibrary(
                media = notFound,
                hashExecutor = hashExecutor,
            )
            if (missing.isNotEmpty()) {
                synchronized(lock) {
                    missing.forEach { statusById[it.id] = Status.MISSING }
                    areBucketsOutdated = true
                }
                scheduleStateUpdate()
            }

            log.debug {
                "recheckKnownInLibrary(): rechecked:" +
                        "\nbundle=${bundle.size}," +
                        "\nmissing=${missing.size}"
            }
        }
    }

    private fun getNextBundle(): List<LocalMedia>? = synchronized(lock) {
        val bucketId = selectedBucketId
        val preferred = ArrayList<LocalMedia>(BUNDLE_SIZE)
        val rest = ArrayList<LocalMedia>(BUNDLE_SIZE)
        for (media in allMedia) {
            if (statusById[media.id] != Status.UNKNOWN) {
                continue
            }
            if (bucketId != null && media.bucketId == bucketId) {
                preferred += media
                if (preferred.size == BUNDLE_SIZE) {
                    break
                }
            } else if (rest.size < BUNDLE_SIZE) {
                rest += media
            }
        }

        (preferred + rest)
            .take(BUNDLE_SIZE)
            .takeIf(List<LocalMedia>::isNotEmpty)
    }

    /**
     * @return false if the library couldn't be asked
     */
    private fun checkBundle(
        bundle: List<LocalMedia>,
        hashExecutor: ExecutorService,
    ): Boolean {
        val newHashes = mutableMapOf<LocalMedia, String>()
        val hashes: Map<LocalMedia, String?> = hashExecutor
            .invokeAll(bundle.map { media ->
                Callable {
                    media to (hasher.getCached(media)
                        ?: try {
                            hasher.calculate(media, isCancelled = { isCleared || isReloadRequested }).also { sha1 ->
                                synchronized(newHashes) { newHashes[media] = sha1 }
                            }
                        } catch (error: Exception) {
                            if (!isCleared) {
                                log.warn(error) {
                                    "checkBundle(): hashing_failed:" +
                                            "\nmedia=${media.displayName}"
                                }
                            }
                            null
                        })
                }
            })
            .associate { it.get() }

        hasher.save(newHashes)

        if (isCleared) {
            return true
        }

        val hashesInLibrary: Set<String>? =
            if (isLibraryUnavailable)
                null
            else
                getHashesInLibraryWithRetries(hashes.values.filterNotNull().toSet())

        val redactedCopiesInLibrary: Set<LocalMedia> =
            if (hashesInLibrary == null || isCleared)
                emptySet()
            else
                findRedactedCopiesInLibrary(
                    media = hashes.filterValues { it != null && it !in hashesInLibrary }.keys,
                    hashExecutor = hashExecutor,
                )

        synchronized(lock) {
            hashes.forEach { (media, sha1) ->
                statusById[media.id] = when {
                    sha1 == null || hashesInLibrary == null ->
                        Status.NOT_CHECKED

                    sha1 in hashesInLibrary || media in redactedCopiesInLibrary ->
                        Status.IN_LIBRARY

                    else ->
                        Status.MISSING
                }
            }
        }

        return hashesInLibrary != null || isLibraryUnavailable
    }

    /**
     * Android zeroes the location data when a file is read without asking for the original,
     * keeping the size. The upstream app and sharing uploaded such copies.
     * Their hash differs from the original, which would be uploaded again as a duplicate,
     * so the images are also looked for by the hash of their redacted contents.
     *
     * @return the [media] whose redacted copy is in the library,
     * which is also remembered for their original hash.
     */
    private fun findRedactedCopiesInLibrary(
        media: Collection<LocalMedia>,
        hashExecutor: ExecutorService,
    ): Set<LocalMedia> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isLibraryUnavailable) {
            return emptySet()
        }
        val originalHashes: Map<LocalMedia, String> = media
            .filter { item -> !item.isVideo && item.isOriginal }
            .mapNotNull { item -> hasher.getCached(item)?.let { item to it } }
            .toMap()
        val candidates = originalHashes.keys
        if (candidates.isEmpty()) {
            return emptySet()
        }

        val newRedactedHashes = mutableMapOf<LocalMedia, String>()
        val redactedHashes: Map<LocalMedia, String> = hashExecutor
            .invokeAll(candidates.map { item ->
                Callable {
                    item to (hasher.getCachedRedacted(item)
                        ?: try {
                            hasher.calculateRedacted(
                                media = item,
                                originalSha1 = originalHashes.getValue(item),
                                isCancelled = { isCleared || isReloadRequested },
                            ).also { sha1 ->
                                synchronized(newRedactedHashes) { newRedactedHashes[item] = sha1 }
                            }
                        } catch (error: Exception) {
                            if (!isCleared) {
                                log.warn(error) {
                                    "findRedactedCopiesInLibrary(): hashing_failed:" +
                                            "\nmedia=${item.displayName}"
                                }
                            }
                            null
                        })
                }
            })
            .mapNotNull { future ->
                val (item, redactedSha1) = future.get()
                redactedSha1?.let { item to it }
            }
            .toMap()

        hasher.saveRedacted(newRedactedHashes)

        // Files without location data read the same either way.
        val toCheck = redactedHashes.filter { (item, redactedSha1) ->
            redactedSha1 != originalHashes[item]
        }
        if (toCheck.isEmpty() || isCleared) {
            return emptySet()
        }

        val inLibrary = getHashesInLibraryWithRetries(toCheck.values.toSet())
            ?: return emptySet()
        val found = toCheck.filterValues(inLibrary::contains).keys
        libraryHashChecker.remember(found.mapNotNull(originalHashes::get))

        log.debug {
            "findRedactedCopiesInLibrary(): checked:" +
                    "\ncandidates=${candidates.size}," +
                    "\nredacted=${toCheck.size}," +
                    "\nfound=${found.size}"
        }

        return found
    }

    private fun getHashesInLibraryWithRetries(hashes: Set<String>): Set<String>? {
        for (attempt in 0..BUNDLE_RETRY_DELAYS_MS.size) {
            try {
                return libraryHashChecker.getHashesInLibrary(hashes)
            } catch (error: Exception) {
                log.warn(error) {
                    "getHashesInLibraryWithRetries(): check_failed:" +
                            "\nattempt=$attempt"
                }
                val delay = BUNDLE_RETRY_DELAYS_MS.getOrNull(attempt)
                    ?: return null
                if (isCleared) {
                    return null
                }
                Thread.sleep(delay)
            }
        }
        return null
    }

    /**
     * Updates the shown state in the background.
     * Updates requested meanwhile are merged into one.
     */
    private fun scheduleStateUpdate() {
        if (!isStateUpdateScheduled.compareAndSet(false, true)) {
            return
        }
        try {
            stateExecutor.execute {
                isStateUpdateScheduled.set(false)
                try {
                    postState()
                } catch (error: Exception) {
                    log.error(error) { "scheduleStateUpdate(): failed" }
                }
            }
        } catch (_: Exception) {
            // Already shut down.
        }
    }

    /**
     * Must be called on the [stateExecutor].
     */
    private fun postState() {
        // Take a snapshot, so the rest doesn't block the selection on the main thread.
        val media: List<LocalMedia>
        val dayStarts: LongArray
        val statuses: Map<Long, Status>
        val selected: Set<Long>
        val bucketId: String?
        val hideInLibrary: Boolean
        val areBucketsOutdated: Boolean
        synchronized(lock) {
            media = allMedia
            dayStarts = this.dayStarts
            statuses = HashMap(statusById)
            selected = selectedIds.toHashSet()
            bucketId = selectedBucketId
            hideInLibrary = this.hideInLibrary
            areBucketsOutdated = this.areBucketsOutdated
            this.areBucketsOutdated = false
        }
        val canSelectInBulk = canSelectInBulk()
        val isLibraryUnavailable = isLibraryUnavailable

        val buckets =
            if (areBucketsOutdated)
                getBuckets(media, statuses, isLibraryUnavailable).also { lastBuckets = it }
            else
                lastBuckets

        val mediaItems = ArrayList<Item>()
        val itemDayStarts = ArrayList<Long>()
        val hiddenSelectedIds = mutableListOf<Long>()
        var checked = 0
        var inLibrary = 0
        var total = 0
        media.forEachIndexed { index, item ->
            if (bucketId != null && item.bucketId != bucketId) {
                return@forEachIndexed
            }
            val status = statuses[item.id] ?: Status.UNKNOWN
            total++
            if (status != Status.UNKNOWN) {
                checked++
            }
            if (status == Status.IN_LIBRARY) {
                inLibrary++
            }

            // Keep the selection only for media which can be seen.
            if (hideInLibrary && (status == Status.IN_LIBRARY || status == Status.UNKNOWN)) {
                if (item.id in selected) {
                    hiddenSelectedIds += item.id
                }
            } else {
                mediaItems += Item(
                    media = item,
                    status = status,
                    isSelected = item.id in selected,
                    isSelectableInBulk = canSelectInBulk && status.isSelectableInBulk,
                )
                itemDayStarts += dayStarts[index]
            }
        }

        val selectedCount = synchronized(lock) {
            hiddenSelectedIds.forEach { id ->
                // Unless shown again meanwhile.
                val status = statusById[id]
                if (this.hideInLibrary && (status == Status.IN_LIBRARY || status == Status.UNKNOWN)) {
                    selectedIds.remove(id)
                }
            }
            selectedIds.size
        }

        visibleMediaIds = mediaItems.map { it.media.id }

        _buckets.postValue(buckets)
        _selectedBucket.postValue(buckets.find { it.id == bucketId })
        _items.postValue(groupByDay(mediaItems, itemDayStarts))
        _checkProgress.postValue(
            CheckProgress(
                checked = checked,
                total = total,
                inLibrary = inLibrary,
                isLibraryUnavailable = isLibraryUnavailable,
            )
        )
        _selectedCount.postValue(selectedCount)
    }

    private fun getBuckets(
        media: List<LocalMedia>,
        statuses: Map<Long, Status>,
        isLibraryUnavailable: Boolean,
    ): List<Bucket> =
        media
            .filter { it.bucketId != null }
            .groupBy { it.bucketId!! }
            .map { (bucketId, bucketMedia) ->
                // Media is sorted from the newest.
                val newest = bucketMedia.first()
                val isFullyChecked = bucketMedia.none { statuses[it.id] == Status.UNKNOWN }
                Bucket(
                    id = bucketId,
                    name = newest.bucketName ?: bucketId,
                    path = newest.bucketPath
                        ?.trim('/')
                        ?.takeIf(String::isNotEmpty),
                    count = bucketMedia.size,
                    missingCount =
                        if (isFullyChecked && !isLibraryUnavailable)
                            bucketMedia.count { statuses[it.id] != Status.IN_LIBRARY }
                        else
                            null,
                    isOnSdCard = newest.isOnSdCard,
                    coverUri = newest.uri,
                )
            }
            .sortedByDescending(Bucket::count)

    /**
     * The media is sorted from the newest, so each day comes in one piece.
     */
    private fun groupByDay(
        mediaItems: List<Item>,
        dayStarts: List<Long>,
    ): List<ListItem> {
        val result = ArrayList<ListItem>(mediaItems.size + mediaItems.size / 4)
        var dayItems = mutableListOf<Item>()
        var dayStartMs: Long? = null

        fun addDay() {
            val start = dayStartMs
                ?: return
            val selectableItems = dayItems.filter(Item::isSelectableInBulk)
            result += ListItem.Day(
                dayStartMs = start,
                mediaIds = dayItems.map { it.media.id },
                selectableIds = selectableItems.map { it.media.id },
                selectedCount = dayItems.count(Item::isSelected),
                selectableSelectedCount = selectableItems.count(Item::isSelected),
            )
            dayItems.mapTo(result) { ListItem.Media(it) }
        }

        mediaItems.forEachIndexed { index, item ->
            val itemDayStartMs = dayStarts[index]
            if (itemDayStartMs != dayStartMs) {
                addDay()
                dayStartMs = itemDayStartMs
                dayItems = mutableListOf()
            }
            dayItems += item
        }
        addDay()

        return result
    }

    fun onBucketSelected(bucketId: String?) {
        synchronized(lock) {
            selectedBucketId = bucketId
        }
        scheduleStateUpdate()
    }

    fun onHideInLibraryChanged(hide: Boolean) {
        if (hide == hideInLibrary) {
            return
        }
        synchronized(lock) {
            hideInLibrary = hide
        }
        isHidingInLibrary.value = hide
        scheduleStateUpdate()
    }

    fun onItemClicked(item: Item) {
        synchronized(lock) {
            if (!selectedIds.remove(item.media.id)) {
                selectedIds += item.media.id
            }
        }
        scheduleStateUpdate()
    }

    fun onSelectAllClicked() {
        synchronized(lock) {
            selectedIds += visibleMediaIds.filterSelectableInBulk()
        }
        scheduleStateUpdate()
    }

    /**
     * Selects the whole day, or unselects it if it is all selected.
     */
    fun onDayClicked(day: ListItem.Day) {
        synchronized(lock) {
            if (day.isAllSelected) {
                selectedIds -= day.mediaIds.toSet()
            } else {
                selectedIds += day.selectableIds.filterSelectableInBulk()
            }
        }
        scheduleStateUpdate()
    }

    /**
     * Starts selecting by dragging from the media with the [mediaId]:
     * from a selected one, the dragged over media is unselected, otherwise selected.
     */
    fun onDragSelectionStarted(mediaId: Long) {
        synchronized(lock) {
            dragSelectionBase = selectedIds.toSet()
            dragSelectionSelects = mediaId !in selectedIds
        }
        onDragSelectionChanged(listOf(mediaId))
    }

    /**
     * @param mediaIds the media dragged over so far, from the start to where the finger is.
     */
    fun onDragSelectionChanged(mediaIds: Collection<Long>) {
        synchronized(lock) {
            val base = dragSelectionBase
                ?: return
            selectedIds.clear()
            selectedIds += base
            if (dragSelectionSelects) {
                selectedIds += mediaIds.filterSelectableInBulk()
            } else {
                selectedIds -= mediaIds.toSet()
            }
        }
        scheduleStateUpdate()
    }

    fun onDragSelectionEnded() {
        synchronized(lock) {
            dragSelectionBase = null
        }
    }

    fun onClearSelectionClicked() {
        synchronized(lock) {
            selectedIds.clear()
        }
        scheduleStateUpdate()
    }

    /**
     * Checks again the media which couldn't be checked,
     * for example, because the library was unreachable.
     */
    fun onCheckAgainClicked() {
        isLibraryUnavailable = false
        synchronized(lock) {
            statusById.entries.forEach { entry ->
                if (entry.value == Status.NOT_CHECKED) {
                    entry.setValue(Status.UNKNOWN)
                }
            }
            areBucketsOutdated = true
        }
        scheduleStateUpdate()
        checkExecutor.execute(::checkUnknown)
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
                        takenAtMs = media.dateTakenMs.takeIf { it > 0 },
                    )
                }
        }

        if (files.isNotEmpty()) {
            eventsSubject.onNext(Event.OpenImport(files))
        }
    }

    override fun onCleared() {
        isCleared = true
        checkExecutor.shutdownNow()
        hashExecutor.shutdownNow()
        stateExecutor.shutdownNow()
    }

    /**
     * Without the originals, the hashes never match the library,
     * so selecting many would pick files which are already there.
     */
    private fun canSelectInBulk(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || areOriginalsReadable

    /**
     * Selecting many at once skips media which is already in the library
     * or couldn't be checked, so it is only uploaded when picked one by one.
     * Must be called under the [lock].
     */
    private fun Collection<Long>.filterSelectableInBulk(): List<Long> =
        if (!canSelectInBulk())
            emptyList()
        else
            filter { statusById[it]?.isSelectableInBulk != false }

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
        ;

        /**
         * @see filterSelectableInBulk
         */
        val isSelectableInBulk: Boolean
            get() = this != IN_LIBRARY && this != NOT_CHECKED
    }

    data class Item(
        val media: LocalMedia,
        val status: Status,
        val isSelected: Boolean,
        /**
         * @see filterSelectableInBulk
         */
        val isSelectableInBulk: Boolean,
    )

    sealed interface ListItem {
        data class Day(
            /**
             * Local midnight of the day.
             */
            val dayStartMs: Long,
            val mediaIds: List<Long>,
            /**
             * The media of the day which selecting the day selects,
             * without the media already in the library.
             */
            val selectableIds: List<Long>,
            val selectedCount: Int,
            val selectableSelectedCount: Int,
        ) : ListItem {
            /**
             * Whether selecting the day has nothing more to select,
             * so tapping it unselects the whole day.
             */
            val isAllSelected: Boolean
                get() = selectedCount > 0 && selectableSelectedCount == selectableIds.size
        }

        data class Media(
            val item: Item,
        ) : ListItem
    }

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
        private const val IN_LIBRARY_RECHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val MISSING_RECHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val HASHING_THREADS = 2
        private const val MAX_FAILED_BUNDLES_IN_ROW = 2
        private val BUNDLE_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)
    }
}
