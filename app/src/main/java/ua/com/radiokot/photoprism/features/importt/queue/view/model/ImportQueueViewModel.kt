package ua.com.radiokot.photoprism.features.importt.queue.view.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemState
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueOverview
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueuePreferences
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueRepository
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueUploadProgress
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueWorker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows the whole upload queue as one list of files,
 * with an overall status on top and a filter by result.
 */
class ImportQueueViewModel(
    private val repository: ImportQueueRepository,
    private val preferences: ImportQueuePreferences,
    uploadProgress: ImportQueueUploadProgress,
    application: Application,
) : AndroidViewModel(application) {
    private val log = kLogger("ImportQueueVM")
    private val context = getApplication<Application>()

    private val overview = MutableLiveData<ImportQueueOverview>()
    private val filter = MutableLiveData(Filter.ALL)
    private val uploadPercents = MutableLiveData<Map<Long, Int>>(emptyMap())

    private val workState: LiveData<WorkState> =
        MediatorLiveData<WorkState>().apply {
            addSource(ImportQueueWorker.getWorkInfos(context)) { workInfos ->
                value = when {
                    workInfos.any { it.state == WorkInfo.State.RUNNING } ->
                        WorkState.RUNNING

                    workInfos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                    } ->
                        WorkState.WAITING

                    else ->
                        WorkState.IDLE
                }
            }
        }

    private val _allowMobileData = MutableLiveData(preferences.allowMobileData)

    /**
     * Whether files may be uploaded over mobile data, not only over Wi-Fi.
     */
    val allowMobileData: LiveData<Boolean> = _allowMobileData

    private val _listItems = MutableLiveData<List<ListItem>>()

    /**
     * Summary on top, then the files grouped by when they were added.
     * Empty if there is nothing in the queue.
     * Built in the background, as the queue may have thousands of files.
     */
    val listItems: LiveData<List<ListItem>> = _listItems

    private val listBuilder = Executors.newSingleThreadExecutor()
    private val isListBuildScheduled = AtomicBoolean(false)

    @Volatile
    private var listInputs: ListInputs? = null

    private val listInputsMediator = MediatorLiveData<Unit>().apply {
        fun update() {
            listInputs = ListInputs(
                overview = overview.value
                    ?: return,
                workState = workState.value ?: WorkState.IDLE,
                filter = filter.value!!,
                uploadPercents = uploadPercents.value!!,
                allowMobileData = _allowMobileData.value!!,
            )
            scheduleListBuild()
        }
        addSource(overview) { update() }
        addSource(uploadPercents) { update() }
        addSource(workState) { update() }
        addSource(filter) { update() }
        addSource(_allowMobileData) { update() }
    }

    private val listInputsObserver = Observer<Unit> {}

    /**
     * Builds the list from the latest inputs.
     * Changes made while building are picked up by one more build,
     * so fast progress updates don't pile up.
     */
    private fun scheduleListBuild() {
        if (!isListBuildScheduled.compareAndSet(false, true)) {
            return
        }
        listBuilder.execute {
            isListBuildScheduled.set(false)
            val inputs = listInputs
                ?: return@execute
            _listItems.postValue(buildListItems(inputs))
        }
    }

    init {
        // Not observed by a view, as the list is built in the background.
        listInputsMediator.observeForever(listInputsObserver)

        repository.changes
            .throttleLatest(500, TimeUnit.MILLISECONDS, true)
            .observeOn(Schedulers.io())
            .map { repository.getOverview() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onNext = overview::setValue,
                onError = { error ->
                    log.error(error) {
                        "init(): failed_loading_overview"
                    }
                }
            )
            .autoDispose(this)

        uploadProgress.percentsByItemId
            .throttleLatest(PROGRESS_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS, true)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(uploadPercents::setValue)
            .autoDispose(this)
    }

    private fun buildListItems(inputs: ListInputs): List<ListItem> {
        val (overview, workState, filter, uploadPercents, allowMobileData) = inputs

        if (overview.items.isEmpty()) {
            return emptyList()
        }

        val waitingCount = overview.count(*Filter.PENDING.states)
        val uploadingCount = overview.count(ImportQueueItemState.UPLOADING)
        val uploadedCount = overview.count(ImportQueueItemState.UPLOADED)
        val importingCount = overview.count(ImportQueueItemState.IMPORTING)
        val inProgressCount = uploadingCount + uploadedCount + importingCount
        val failedCount = overview.count(ImportQueueItemState.FAILED)
        val hasWorkLeft = waitingCount > 0
        val totalCount = overview.items.size

        val status = when {
            // The uploaded files are being added to the library,
            // nothing is being uploaded meanwhile.
            hasWorkLeft && workState == WorkState.RUNNING
                    && importingCount > 0 ->
                Status.IMPORTING

            hasWorkLeft && workState == WorkState.RUNNING ->
                Status.UPLOADING

            hasWorkLeft && workState == WorkState.WAITING && !allowMobileData ->
                Status.WAITING_FOR_WIFI

            hasWorkLeft && workState == WorkState.WAITING ->
                Status.WAITING_FOR_CONNECTION

            hasWorkLeft ->
                Status.PAUSED

            failedCount > 0 ->
                Status.DONE_WITH_PROBLEMS

            else ->
                Status.DONE
        }

        // Uploading takes most of the time, adding to the library the rest.
        val uploadingProgress = overview.items.sumOf { item ->
            when (item.state) {
                ImportQueueItemState.UPLOADING ->
                    (uploadPercents[item.id] ?: 0).coerceAtLeast(0) * UPLOAD_SHARE / 100

                ImportQueueItemState.UPLOADED,
                ImportQueueItemState.IMPORTING ->
                    UPLOAD_SHARE

                else ->
                    0
            }
        }

        val result = mutableListOf<ListItem>()
        result += ListItem.Summary(
            status = status,
            finishedCount = totalCount - waitingCount,
            totalCount = totalCount,
            progress = (totalCount - waitingCount) * PROGRESS_PER_ITEM + uploadingProgress,
            progressMax = totalCount * PROGRESS_PER_ITEM,
            failedCount = failedCount,
            canPause = hasWorkLeft && workState != WorkState.IDLE,
            canResume = hasWorkLeft && workState == WorkState.IDLE,
            countsByFilter = Filter.values().associateWith { overview.count(*it.states) },
            filter = filter,
            // Can't be removed while being uploaded.
            hasRemovableWaiting = waitingCount > inProgressCount,
        )

        val itemsByBatch = overview.items
            .filter { it.state in filter.states }
            .groupBy(ImportQueueOverview.Item::batchId)

        overview.batches.forEach { batch ->
            val batchItems = itemsByBatch[batch.id]
                ?: return@forEach

            result += ListItem.Section(
                batch = batch,
                fileCount = batchItems.size,
            )
            batchItems.forEach { item ->
                result +=
                    if (filter == Filter.FAILED)
                        ListItem.Problem(item)
                    else
                        ListItem.Media(
                            item = item,
                            uploadPercent =
                                if (item.state == ImportQueueItemState.UPLOADING)
                                    uploadPercents[item.id] ?: ImportQueueUploadProgress.CHECKING
                                else
                                    null,
                        )
            }
        }

        if (itemsByBatch.isEmpty()) {
            result += ListItem.NothingInFilter
        }

        return result
    }

    fun onFilterSelected(filter: Filter) {
        if (this.filter.value != filter) {
            this.filter.value = filter
        }
    }

    fun onPauseClicked() {
        log.debug { "onPauseClicked(): pausing" }
        ImportQueueWorker.pause(context)
    }

    fun onResumeClicked() {
        log.debug { "onResumeClicked(): resuming" }
        scheduleWorker()
    }

    fun onRetryAllFailedClicked() = runInBackground {
        repository.retryAllFailedItems()
        scheduleWorker()
    }

    fun onRetryItemClicked(itemId: Long) = runInBackground {
        repository.retryItem(itemId)
        scheduleWorker()
    }

    fun onAllowMobileDataChanged(allow: Boolean) {
        if (allow == preferences.allowMobileData) {
            return
        }

        log.debug {
            "onAllowMobileDataChanged(): changing:" +
                    "\nallow=$allow"
        }

        preferences.allowMobileData = allow
        _allowMobileData.value = allow

        // Unless paused, apply the network requirement to the work right away.
        if (workState.value != WorkState.IDLE) {
            scheduleWorker(restart = true)
        }
    }

    private fun scheduleWorker(restart: Boolean = false) =
        ImportQueueWorker.schedule(
            context = context,
            allowMobileData = preferences.allowMobileData,
            restart = restart,
        )

    override fun onCleared() {
        listInputsMediator.removeObserver(listInputsObserver)
        listBuilder.shutdownNow()
        super.onCleared()
    }

    fun onRemoveItemClicked(itemId: Long) = runInBackground {
        repository.removeItem(itemId)
    }

    fun onRemoveDoneClicked() = runInBackground {
        repository.removeDoneItems()
    }

    fun onRemoveFailedClicked() = runInBackground {
        repository.removeFailedItems()
    }

    fun onRemovePendingConfirmed() = runInBackground {
        repository.removePendingItems()
    }

    private fun runInBackground(action: () -> Unit) {
        Completable.fromAction(action)
            .subscribeOn(Schedulers.io())
            .subscribeBy(
                onError = { error ->
                    log.error(error) {
                        "runInBackground(): failed"
                    }
                }
            )
            .autoDispose(this)
    }

    private data class ListInputs(
        val overview: ImportQueueOverview,
        val workState: WorkState,
        val filter: Filter,
        val uploadPercents: Map<Long, Int>,
        val allowMobileData: Boolean,
    )

    private enum class WorkState {
        RUNNING,

        /**
         * Scheduled but waiting for the network or for a retry.
         */
        WAITING,
        IDLE,
    }

    enum class Status {
        UPLOADING,
        IMPORTING,
        WAITING_FOR_WIFI,
        WAITING_FOR_CONNECTION,
        PAUSED,
        DONE,
        DONE_WITH_PROBLEMS,
    }

    enum class Filter(vararg val states: ImportQueueItemState) {
        ALL(*ImportQueueItemState.values()),
        PENDING(
            ImportQueueItemState.PENDING,
            ImportQueueItemState.UPLOADING,
            ImportQueueItemState.UPLOADED,
            ImportQueueItemState.IMPORTING,
        ),
        IMPORTED(ImportQueueItemState.IMPORTED),
        ALREADY_IN_LIBRARY(ImportQueueItemState.ALREADY_IN_LIBRARY),
        FAILED(ImportQueueItemState.FAILED),
        ;
    }

    sealed interface ListItem {
        data class Summary(
            val status: Status,
            val finishedCount: Int,
            val totalCount: Int,
            val progress: Int,
            val progressMax: Int,
            val failedCount: Int,
            val canPause: Boolean,
            val canResume: Boolean,
            val countsByFilter: Map<Filter, Int>,
            val filter: Filter,
            val hasRemovableWaiting: Boolean,
        ) : ListItem

        data class Section(
            val batch: ImportQueueOverview.Batch,
            val fileCount: Int,
        ) : ListItem

        data class Media(
            val item: ImportQueueOverview.Item,
            /**
             * Set while the file is being uploaded or checked,
             * see [ImportQueueUploadProgress.percentsByItemId].
             */
            val uploadPercent: Int?,
        ) : ListItem

        data class Problem(
            val item: ImportQueueOverview.Item,
        ) : ListItem

        object NothingInFilter : ListItem
    }

    private companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 300L
        private const val PROGRESS_PER_ITEM = 100
        private const val UPLOAD_SHARE = 90
    }
}
